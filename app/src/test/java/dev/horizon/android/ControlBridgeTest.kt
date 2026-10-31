package dev.horizon.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ControlBridgeTest {
    @Test
    fun authenticatesAndPreservesButtonEdgesIncludingSharedBindings() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        assertEquals("auth ${game.token}", game.commands.first().text)
        assertEquals(listOf("pad release", "release"), game.commands.drop(1).take(2).map { it.text })

        bridge.button("first", ControlAction.JUMP, true)
        game.awaitCommand("pad south down")
        bridge.button("second", ControlAction.JUMP, true)
        bridge.button("first", ControlAction.JUMP, false)
        bridge.tap(ControlAction.ENTER) // round-trip barrier after first release
        game.awaitCommand("key enter tap")
        assertFalse(game.commands.any { it.text == "pad south up" })
        bridge.button("second", ControlAction.JUMP, false)
        game.awaitCommand("pad south up")
        assertEquals(listOf("pad south down", "pad south up"), game.commands.filter { it.text.startsWith("pad south ") }.map { it.text })
    }

    @Test
    fun coalescesAnalogSamplesWithoutDroppingButtonEdges() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        game.blockOn("key t tap")
        bridge.tap(ControlAction.CHAT)
        assertTrue(game.blocked.await(3, TimeUnit.SECONDS))
        repeat(100) { bridge.stick("move", "left", it / 99f, -.5f) }
        bridge.button("jump", ControlAction.JUMP, true)
        bridge.button("jump", ControlAction.JUMP, false)
        game.resumeReplies()
        game.awaitCommand("pad stick left 1.0000 -0.5000")
        game.awaitCommand("pad south up")
        assertEquals(1, game.commands.count { it.text.startsWith("pad stick left ") })
        assertEquals(listOf("pad south down", "pad south up"), game.commands.filter { it.text.startsWith("pad south ") }.map { it.text })
        bridge.stick("move", "left", 0f, 0f, released = true)
        game.awaitCommand("pad stick left 0.0000 0.0000")
    }

    @Test
    fun reconnectReleasesOldInputsAndDoesNotReplayQueuedGestures() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.button("jump", ControlAction.JUMP, true)
        game.awaitCommand("pad south down")
        game.blockOn("key t tap")
        bridge.tap(ControlAction.CHAT)
        assertTrue(game.blocked.await(3, TimeUnit.SECONDS))
        bridge.button("attack", ControlAction.ATTACK, true)
        bridge.stick("move", "left", .8f, .9f)
        game.disconnectClient()
        game.resumeReplies()
        game.awaitCommand("release", connection = 2)
        await { bridge.state.value == ControlConnection.CONNECTED }
        bridge.tap(ControlAction.ENTER)
        game.awaitCommand("key enter tap", connection = 2)
        val afterReconnect = game.commands.filter { it.connection == 2 }.map { it.text }
        assertEquals(listOf("auth ${game.token}", "pad release", "release"), afterReconnect.take(3))
        assertFalse(afterReconnect.contains("pad rt down"))
        assertFalse(afterReconnect.contains("pad trigger right 1.0"))
        assertFalse(afterReconnect.contains("mouse left down"))
        assertFalse(afterReconnect.any { it.startsWith("pad stick left") })
    }

    @Test
    fun releaseAllClearsPendingActionsAndTextCannotInjectCommands() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        game.blockOn("key t tap")
        bridge.tap(ControlAction.CHAT)
        assertTrue(game.blocked.await(3, TimeUnit.SECONDS))
        bridge.button("attack", ControlAction.ATTACK, true)
        bridge.stick("look", "right", 1f, 1f)
        bridge.releaseAll()
        game.resumeReplies()
        await { game.commands.count { it.text == "pad release" } == 2 }
        bridge.typeText("hello\nkey q tap\rworld", submit = true)
        game.awaitCommand("key enter tap")
        assertTrue(game.commands.any { it.text == "type hello key q tap world" })
        assertFalse(game.commands.any { it.text == "key q tap" || it.text == "pad rt down" || it.text.startsWith("pad stick right") })
    }

    @Test
    fun pauseIsOrderedAfterReleaseEvenWhenTheGameIsBusy() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        game.blockOn("key t tap")
        bridge.tap(ControlAction.CHAT)
        assertTrue(game.blocked.await(3, TimeUnit.SECONDS))
        bridge.button("attack", ControlAction.ATTACK, true)
        bridge.releaseAndTap(ControlAction.PAUSE)
        game.resumeReplies()
        game.awaitCommand("pad start tap")
        assertEquals(listOf("pad release", "release", "pad start tap"), game.commands.takeLast(3).map { it.text })
        assertFalse(game.commands.any { it.text == "pad rt down" })
    }

    @Test
    fun turnCoalescesAndSendsTurnCommand() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.turn(1.5f, -0.5f)
        bridge.turn(2.0f, 1.0f)
        game.awaitCommand("turn 3.500 0.500")
        assertTrue(game.commands.any { it.text == "turn 3.500 0.500" })
    }

    @Test
    fun hotbarSendsHotbarSlotCommand() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.hotbar(3)
        game.awaitCommand("hotbar 3")
        assertTrue(game.commands.any { it.text == "hotbar 3" })
    }

    @Test
    fun mouseSendsMouseActionCommand() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.mouse("left", "click")
        game.awaitCommand("mouse left click")
        assertTrue(game.commands.any { it.text == "mouse left click" })
    }

    @Test
    fun attackActionPullsRightTriggerAndMouseLeft() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.button("pri", ControlAction.ATTACK, true)
        game.awaitCommand("pad trigger right 1.0")
        game.awaitCommand("mouse left down")

        bridge.button("pri", ControlAction.ATTACK, false)
        game.awaitCommand("pad trigger right 0.0")
        game.awaitCommand("mouse left up")
    }

    @Test
    fun useActionPullsLeftTriggerAndMouseRight() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.button("sec", ControlAction.USE, true)
        game.awaitCommand("pad trigger left 1.0")
        game.awaitCommand("mouse right down")

        bridge.button("sec", ControlAction.USE, false)
        game.awaitCommand("pad trigger left 0.0")
        game.awaitCommand("mouse right up")
    }

    @Test
    fun tabActionSendsTabDownAndUp() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        bridge.button("tab", ControlAction.TAB, true)
        game.awaitCommand("key tab down")

        bridge.button("tab", ControlAction.TAB, false)
        game.awaitCommand("key tab up")
    }

    @Test
    fun statusPollUpdatesMenuState() = withGame { game, bridge ->
        game.awaitConnection(bridge)
        assertTrue(bridge.isMenuOpen.value)

        game.statusScreen = "playing"
        bridge.requestStatusUpdate()
        await { !bridge.isMenuOpen.value && bridge.currentScreen.value == "playing" }

        game.statusScreen = "inventory"
        bridge.requestStatusUpdate()
        await { bridge.isMenuOpen.value && bridge.currentScreen.value == "inventory" }
    }

    private fun withGame(block: (FakeGame, ControlBridge) -> Unit) {
        FakeGame().use { game ->
            ControlBridge(game.endpoint).use { bridge ->
                bridge.start()
                block(game, bridge)
            }
        }
    }

    private fun await(timeoutMillis: Long = 6000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for control bridge", condition())
    }

    private inner class FakeGame : Closeable {
        val token = "0123456789abcdef0123456789abcdef"
        private val directory = Files.createTempDirectory("horizon-control-test").toFile()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val endpoint = File(directory, "control.json").apply {
            writeText(JSONObject().put("transport", "tcp").put("port", server.localPort).put("token", token).toString())
        }
        val commands = CopyOnWriteArrayList<Command>()
        private val connectionCount = AtomicInteger()
        @Volatile private var client: Socket? = null
        @Volatile private var holdCommand: String? = null
        @Volatile var statusScreen = "title"
        val blocked = CountDownLatch(1)
        private val continueReplies = CountDownLatch(1)
        private val worker = thread(name = "fake-horizon-control", isDaemon = true) {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        client = socket
                        val number = connectionCount.incrementAndGet()
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        while (!socket.isClosed) {
                            val line = reader.readLine() ?: break
                            commands.add(Command(number, line))
                            if (line == holdCommand) {
                                blocked.countDown()
                                continueReplies.await(5, TimeUnit.SECONDS)
                            }
                            if (line == "status") {
                                writer.write("{\"ok\":true,\"screen\":\"$statusScreen\"}\n")
                            } else {
                                writer.write("{\"ok\":true}\n")
                            }
                            writer.flush()
                        }
                    }
                }
            }
        }

        fun awaitConnection(bridge: ControlBridge) = await { bridge.state.value == ControlConnection.CONNECTED }
        fun awaitCommand(command: String, connection: Int = 1) = await { commands.any { it.connection == connection && it.text == command } }
        fun blockOn(command: String) { holdCommand = command }
        fun resumeReplies() { holdCommand = null; continueReplies.countDown() }
        fun disconnectClient() { client?.close() }

        override fun close() {
            resumeReplies()
            client?.close()
            server.close()
            directory.deleteRecursively()
        }
    }

    private data class Command(val connection: Int, val text: String)
}
