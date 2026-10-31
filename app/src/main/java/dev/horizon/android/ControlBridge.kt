package dev.horizon.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Locale

enum class ControlConnection { CONNECTING, CONNECTED, RECONNECTING, CLOSED }

/**
 * One authenticated loopback connection, owned by the game process. Button edges stay ordered;
 * stick and camera turn samples are coalesced. An interrupted connection discards input and starts with a release,
 * so a gesture from a loading screen can never replay in a newly loaded world.
 */
class ControlBridge(private val endpoint: File) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private val axes = linkedMapOf<String, String>()
    private val pressed = mutableMapOf<String, ControlAction>()
    private val stickOwners = mutableMapOf<String, String>()
    private var pendingTurnYaw = 0f
    private var pendingTurnPitch = 0f
    private var releaseRequested = true
    private var started = false
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var forceStatusPoll = false

    private val mutableState = MutableStateFlow(ControlConnection.CONNECTING)
    val state: StateFlow<ControlConnection> = mutableState

    private val mutableIsMenuOpen = MutableStateFlow(true)
    val isMenuOpen: StateFlow<Boolean> = mutableIsMenuOpen.asStateFlow()

    private val mutableCurrentScreen = MutableStateFlow("title")
    val currentScreen: StateFlow<String> = mutableCurrentScreen.asStateFlow()

    fun requestStatusUpdate() {
        forceStatusPoll = true
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            var connectedOnce = false
            while (isActive && running) {
                try {
                    mutableState.value = if (connectedOnce) ControlConnection.RECONNECTING else ControlConnection.CONNECTING
                    if (!endpoint.isFile) {
                        delay(200)
                        continue
                    }
                    val descriptor = JSONObject(endpoint.readText())
                    require(descriptor.getString("transport") == "tcp")
                    val port = descriptor.getInt("port")
                    val token = descriptor.getString("token")
                    require(port in 1..65535 && token.matches(Regex("[0-9a-fA-F]{32}")))
                    Socket().use { connected ->
                        socket = connected
                        connected.tcpNoDelay = true
                        connected.soTimeout = 2500
                        connected.connect(InetSocketAddress("127.0.0.1", port), 1500)
                        val reader = connected.getInputStream().bufferedReader()
                        val writer = connected.getOutputStream().bufferedWriter()
                        exchange(writer, reader, "auth $token")
                        exchange(writer, reader, "pad release")
                        exchange(writer, reader, "release")
                        synchronized(lock) {
                            clearInputLocked()
                            releaseRequested = false
                            mutableState.value = ControlConnection.CONNECTED
                        }
                        connectedOnce = true
                        var lastExchange = System.nanoTime()
                        var lastStatusPollMs = 0L
                        while (isActive && running) {
                            val commands = synchronized(lock) {
                                if (releaseRequested) {
                                    clearInputLocked()
                                    releaseRequested = false
                                    listOf("pad release", "release")
                                } else {
                                    buildList {
                                        repeat(minOf(events.size, 32)) { add(events.removeFirst()) }
                                        addAll(axes.values)
                                        axes.clear()
                                        if (pendingTurnYaw != 0f || pendingTurnPitch != 0f) {
                                            add(String.format(Locale.ROOT, "turn %.3f %.3f", pendingTurnYaw, pendingTurnPitch))
                                            pendingTurnYaw = 0f
                                            pendingTurnPitch = 0f
                                        }
                                    }
                                }
                            }
                            commands.forEach { command -> exchange(writer, reader, command) }
                            if (commands.isNotEmpty()) lastExchange = System.nanoTime()

                            val nowMs = System.currentTimeMillis()
                            if (forceStatusPoll || (nowMs - lastStatusPollMs >= 250)) {
                                forceStatusPoll = false
                                lastStatusPollMs = nowMs
                                try {
                                    val statusJson = exchange(writer, reader, "status")
                                    val screen = statusJson.optString("screen")
                                    if (screen.isNotEmpty()) {
                                        mutableCurrentScreen.value = screen
                                        mutableIsMenuOpen.value = (screen != "playing")
                                    }
                                } catch (_: Exception) {
                                    // Ignore parse errors from status
                                }
                                lastExchange = System.nanoTime()
                            }

                            // Check even idle connections; native shutdown should be visible promptly.
                            if (commands.isEmpty()) {
                                if (System.nanoTime() - lastExchange > 1_500_000_000L) {
                                    exchange(writer, reader, "pad status")
                                    lastExchange = System.nanoTime()
                                }
                                delay(16)
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (running) {
                        synchronized(lock) {
                            clearInputLocked()
                            mutableState.value = ControlConnection.RECONNECTING
                        }
                        delay(350)
                    }
                } finally {
                    socket = null
                }
            }
            mutableState.value = ControlConnection.CLOSED
        }
    }

    fun button(source: String, action: ControlAction, down: Boolean) = synchronized(lock) {
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        if (action.command.startsWith("builtin:")) return@synchronized
        if (action == ControlAction.INVENTORY || action == ControlAction.PAUSE) {
            forceStatusPoll = true
        }
        if (down) {
            if (source in pressed) return@synchronized
            val alreadyPressed = action in pressed.values
            pressed[source] = action
            if (!alreadyPressed) {
                when (action) {
                    ControlAction.ATTACK -> {
                        queueLocked("pad trigger right 1.0")
                        queueLocked("mouse left down")
                    }
                    ControlAction.USE -> {
                        queueLocked("pad trigger left 1.0")
                        queueLocked("mouse right down")
                    }
                    ControlAction.TAB -> {
                        queueLocked("key tab down")
                    }
                    else -> queueLocked("${action.command} down")
                }
            }
        } else {
            val oldAction = pressed.remove(source) ?: return@synchronized
            if (oldAction !in pressed.values) {
                when (oldAction) {
                    ControlAction.ATTACK -> {
                        queueLocked("pad trigger right 0.0")
                        queueLocked("mouse left up")
                    }
                    ControlAction.USE -> {
                        queueLocked("pad trigger left 0.0")
                        queueLocked("mouse right up")
                    }
                    ControlAction.TAB -> {
                        queueLocked("key tab up")
                    }
                    else -> queueLocked("${oldAction.command} up")
                }
            }
        }
    }

    fun stick(source: String, side: String, x: Float, y: Float, released: Boolean = false) = synchronized(lock) {
        require(side == "left" || side == "right")
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        if (released) {
            if (stickOwners[side] != source) return@synchronized
            stickOwners.remove(side)
        } else {
            val owner = stickOwners[side]
            if (owner != null && owner != source) return@synchronized
            stickOwners[side] = source
        }
        val safeX = if (released || !x.isFinite()) 0f else x.coerceIn(-1f, 1f)
        val safeY = if (released || !y.isFinite()) 0f else y.coerceIn(-1f, 1f)
        axes[side] = String.format(Locale.ROOT, "pad stick %s %.4f %.4f", side, safeX, safeY)
    }

    /** Coalesced camera turn deltas in degrees. */
    fun turn(yaw: Float, pitch: Float) = synchronized(lock) {
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        if (!yaw.isFinite() || !pitch.isFinite()) return@synchronized
        pendingTurnYaw += yaw
        pendingTurnPitch += pitch
    }

    /** Select a hotbar slot (1..9). */
    fun hotbar(slot: Int) = synchronized(lock) {
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        if (slot in 1..9) {
            queueLocked("hotbar $slot")
        }
    }

    /** Mouse button command (e.g. "left", "click"). */
    fun mouse(button: String, action: String) = synchronized(lock) {
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        queueLocked("mouse $button $action")
    }

    fun tap(action: ControlAction) = synchronized(lock) {
        if (mutableState.value == ControlConnection.CONNECTED && !releaseRequested) {
            if (action.command.startsWith("builtin:")) return@synchronized
            if (action == ControlAction.INVENTORY || action == ControlAction.PAUSE) {
                forceStatusPoll = true
            }
            when (action) {
                ControlAction.ATTACK -> {
                    queueLocked("pad trigger right 1.0")
                    queueLocked("mouse left click")
                    queueLocked("pad trigger right 0.0")
                }
                ControlAction.USE -> {
                    queueLocked("pad trigger left 1.0")
                    queueLocked("mouse right click")
                    queueLocked("pad trigger left 0.0")
                }
                ControlAction.TAB -> {
                    queueLocked("key tab tap")
                }
                else -> queueLocked("${action.command} tap")
            }
        }
    }

    /** The wire protocol is line-based: text must never inject an extra command. */
    fun typeText(text: String, submit: Boolean) = synchronized(lock) {
        if (mutableState.value != ControlConnection.CONNECTED || releaseRequested) return@synchronized
        val safe = text.replace('\n', ' ').replace('\r', ' ').replace('\u0000', ' ').take(2048)
        if (safe.isNotEmpty()) queueLocked("type $safe")
        if (submit) queueLocked("key enter tap")
    }

    fun releaseAll() = synchronized(lock) {
        clearInputLocked()
        releaseRequested = true
    }

    /** A system Back press must pause even if rendering is too busy to answer within one frame. */
    fun releaseAndTap(action: ControlAction) = synchronized(lock) {
        clearInputLocked()
        if (mutableState.value == ControlConnection.CONNECTED) {
            releaseRequested = false
            events.addLast("pad release")
            events.addLast("release")
            events.addLast("${action.command} tap")
        } else {
            releaseRequested = true
        }
    }

    private fun clearInputLocked() {
        events.clear()
        axes.clear()
        pressed.clear()
        stickOwners.clear()
        pendingTurnYaw = 0f
        pendingTurnPitch = 0f
    }

    private fun queueLocked(command: String) {
        if (events.size >= 256) {
            clearInputLocked()
            releaseRequested = true
        } else {
            events.addLast(command)
        }
    }

    private fun exchange(writer: BufferedWriter, reader: BufferedReader, command: String): JSONObject {
        writer.write(command)
        writer.newLine()
        writer.flush()
        val response = reader.readLine() ?: throw IOException("Game control connection closed")
        if (response.length > 1_048_576) throw IOException("Oversized control response")
        val json = JSONObject(response)
        if (command.startsWith("auth ") && !json.optBoolean("ok")) throw IOException("Control authentication failed")
        // Other command errors are contextual (e.g. typing while no text field is open), not a
        // reason to reconnect or replay commands. The reply is always consumed before the next.
        return json
    }

    override fun close() {
        releaseAll()
        scope.launch {
            // Give the owning IO loop a chance to deliver the release before closing its socket.
            delay(120)
            running = false
            runCatching { socket?.close() }
            mutableState.value = ControlConnection.CLOSED
            scope.cancel()
        }
    }
}
