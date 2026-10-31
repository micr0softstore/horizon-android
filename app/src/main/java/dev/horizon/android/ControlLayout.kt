package dev.horizon.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ControlKind { BUTTON, MOVE_STICK, LOOK_STICK }

/** Commands are fixed here, never accepted as arbitrary input from layout files. */
enum class ControlAction(val title: String, val label: String, val command: String) {
    JUMP("Jump / confirm", "\u2b1b", "pad south"),
    ATTACK("Attack / Primary", "PRI", "pad rt"),
    USE("Use / Secondary", "SEC", "pad lt"),
    FORWARD("Forward (W)", "\u25b2", "key w"),
    BACKWARD("Backward (S)", "\u25bc", "key s"),
    LEFT("Left (A)", "\u25c0", "key a"),
    RIGHT("Right (D)", "\u25b6", "key d"),
    SNEAK("Crouch / Shift", "\u25c7", "pad rs"),
    SPRINT("Sprint", "Sprint", "pad ls"),
    INVENTORY("Inventory (E)", "INV", "pad north"),
    PAUSE("Pause / menu back", "Pause", "pad start"),
    BACK("Drop / menu back", "Back", "pad east"),
    SWAP_HANDS("Swap hands (F)", "Swap", "pad west"),
    DEBUG("Debug screen (F3)", "DEBUG", "key f3"),
    CHAT("Open chat (T)", "CHAT", "key t"),
    KEYBOARD("Toggle keyboard", "KEYBOARD", "builtin:keyboard"),
    TAB("Player list (Tab)", "TAB", "key tab"),
    THIRD_PERSON("3rd Person view (F5)", "3RD", "key f5"),
    ESCAPE("Escape", "ESC", "key esc"),
    ESC("Escape", "ESC", "key esc"),
    MOUSE("Virtual mouse", "MOUSE", "builtin:mouse"),
    GUI("Toggle UI overlay", "GUI", "builtin:gui"),
    DROP("Drop item (Q)", "DROP", "key q"),
    HOTBAR_PREVIOUS("Previous hotbar slot", "\u2039 Slot", "pad lb"),
    HOTBAR_NEXT("Next hotbar slot", "Slot \u203a", "pad rb"),
    MENU_UP("Menu up", "\u2191", "pad up"),
    MENU_DOWN("Menu down", "\u2193", "pad down"),
    MENU_LEFT("Menu left", "\u2190", "pad left"),
    MENU_RIGHT("Menu right", "\u2192", "pad right"),
    ENTER("Enter / submit", "Enter", "key enter"),
    BACKSPACE("Backspace", "\u232b", "key backspace");
}

data class ControlPlacement(
    val id: String = UUID.randomUUID().toString(),
    val kind: ControlKind = ControlKind.BUTTON,
    val action: ControlAction = ControlAction.JUMP,
    val name: String = "",
    /** Centre coordinates, normalized to the screen area (0..1). */
    val x: Float,
    val y: Float,
    /** Width and height in density-independent pixels (dp). */
    val widthDp: Float = 76f,
    val heightDp: Float = 30f,
    /** Legacy normalized width/height kept for backward compatibility with older layout files. */
    val width: Float = 0.085f,
    val height: Float = 0.07f,
    val size: Float = 0.14f,
    val opacity: Float = 1.0f,
    val isToggle: Boolean = false,
    val passThru: Boolean = false,
    val swipeable: Boolean = false,
    val forwardLock: Boolean = false,
    val cornerRadius: Float = 0f,
    val strokeWidth: Float = 0f,
    val bgColor: Long = 0x66000000L,
    val strokeColor: Long = 0xFFFFFFFFL,
) {
    val label: String get() = when {
        name.isNotBlank() -> name
        kind == ControlKind.MOVE_STICK -> "Move"
        kind == ControlKind.LOOK_STICK -> "Look"
        else -> action.label
    }

    fun sanitized(): ControlPlacement {
        val safeW = if (widthDp > 0f) widthDp.finiteOr(76f) else (width * 800f).finiteOr(76f)
        val safeH = if (heightDp > 0f) heightDp.finiteOr(30f) else (height * 400f).finiteOr(30f)
        return copy(
            x = x.finiteOr(0.5f).coerceIn(0f, 1f),
            y = y.finiteOr(0.5f).coerceIn(0f, 1f),
            widthDp = safeW.coerceIn(24f, 320f),
            heightDp = safeH.coerceIn(20f, 240f),
            width = (safeW / 800f).coerceIn(0.02f, 0.50f),
            height = (safeH / 400f).coerceIn(0.02f, 0.50f),
            size = size.finiteOr(0.14f).coerceIn(0.05f, 0.50f),
            opacity = opacity.finiteOr(1.0f).coerceIn(0.1f, 1f),
            cornerRadius = cornerRadius.finiteOr(0f).coerceIn(0f, 50f),
            strokeWidth = strokeWidth.finiteOr(0f).coerceIn(0f, 10f),
        )
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

fun defaultControlLayout(): List<ControlPlacement> = listOf(
    // Top Row: rectangular buttons matching Mojolauncher (76x30 dp or 86x30 dp)
    ControlPlacement(id = "debug", kind = ControlKind.BUTTON, action = ControlAction.DEBUG, name = "DEBUG", x = 0.070f, y = 0.055f, widthDp = 76f, heightDp = 30f),
    ControlPlacement(id = "chat", kind = ControlKind.BUTTON, action = ControlAction.CHAT, name = "CHAT", x = 0.210f, y = 0.055f, widthDp = 76f, heightDp = 30f),
    ControlPlacement(id = "keyboard", kind = ControlKind.BUTTON, action = ControlAction.KEYBOARD, name = "KEYBOARD", x = 0.355f, y = 0.055f, widthDp = 86f, heightDp = 30f),
    ControlPlacement(id = "tab", kind = ControlKind.BUTTON, action = ControlAction.TAB, name = "TAB", x = 0.500f, y = 0.055f, widthDp = 76f, heightDp = 30f),
    ControlPlacement(id = "mouse", kind = ControlKind.BUTTON, action = ControlAction.MOUSE, name = "MOUSE", x = 0.930f, y = 0.055f, widthDp = 76f, heightDp = 30f),

    // Left Column
    ControlPlacement(id = "third", kind = ControlKind.BUTTON, action = ControlAction.THIRD_PERSON, name = "3RD", x = 0.070f, y = 0.175f, widthDp = 76f, heightDp = 30f),
    ControlPlacement(id = "esc", kind = ControlKind.BUTTON, action = ControlAction.ESCAPE, name = "ESC", x = 0.070f, y = 0.295f, widthDp = 76f, heightDp = 30f),

    // D-Pad Row 1 (Square 50x50 dp)
    ControlPlacement(id = "pri", kind = ControlKind.BUTTON, action = ControlAction.ATTACK, name = "PRI", x = 0.070f, y = 0.535f, widthDp = 50f, heightDp = 50f),
    ControlPlacement(id = "up", kind = ControlKind.BUTTON, action = ControlAction.FORWARD, name = "\u25b2", x = 0.165f, y = 0.535f, widthDp = 50f, heightDp = 50f),
    ControlPlacement(id = "sec", kind = ControlKind.BUTTON, action = ControlAction.USE, name = "SEC", x = 0.260f, y = 0.535f, widthDp = 50f, heightDp = 50f),

    // D-Pad Row 2 (Square 50x50 dp)
    ControlPlacement(id = "left", kind = ControlKind.BUTTON, action = ControlAction.LEFT, name = "\u25c0", x = 0.070f, y = 0.725f, widthDp = 50f, heightDp = 50f),
    ControlPlacement(id = "sneak", kind = ControlKind.BUTTON, action = ControlAction.SNEAK, name = "\u25c7", x = 0.165f, y = 0.725f, widthDp = 50f, heightDp = 50f, isToggle = true),
    ControlPlacement(id = "right", kind = ControlKind.BUTTON, action = ControlAction.RIGHT, name = "\u25b6", x = 0.260f, y = 0.725f, widthDp = 50f, heightDp = 50f),

    // D-Pad Row 3 (Square 50x50 dp)
    ControlPlacement(id = "gui", kind = ControlKind.BUTTON, action = ControlAction.GUI, name = "GUI", x = 0.070f, y = 0.915f, widthDp = 50f, heightDp = 50f),
    ControlPlacement(id = "down", kind = ControlKind.BUTTON, action = ControlAction.BACKWARD, name = "\u25bc", x = 0.165f, y = 0.915f, widthDp = 50f, heightDp = 50f),
    ControlPlacement(id = "inv", kind = ControlKind.BUTTON, action = ControlAction.INVENTORY, name = "INV", x = 0.260f, y = 0.915f, widthDp = 50f, heightDp = 50f),

    // Bottom Right Jump Button (Square 56x56 dp)
    ControlPlacement(id = "jump", kind = ControlKind.BUTTON, action = ControlAction.JUMP, name = "\u2b1b", x = 0.910f, y = 0.820f, widthDp = 56f, heightDp = 56f),
)

/** Atomic files are deliberately re-read: launcher and GameActivity use separate processes. */
class ControlLayoutStore(context: Context, profileId: String = "default") {
    init { require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
    private val directory = File(context.filesDir, "horizon/profiles/$profileId")

    fun load(landscape: Boolean = true): List<ControlPlacement> = runCatching {
        val json = JSONObject(atomicFile(landscape).openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 2)
        val controls = json.getJSONArray("controls")
        require(controls.length() in 1..40)
        (0 until controls.length()).map { index ->
            val item = controls.getJSONObject(index)
            val action = ControlAction.valueOf(item.optString("action", "JUMP"))
            val widthVal = item.optDouble("widthDp", -1.0).takeIf { it > 0 }
                ?: item.optDouble("width", -1.0).takeIf { it >= 1.0 }
                ?: item.optDouble("size", -1.0).takeIf { it >= 1.0 }
                ?: when (action) {
                    ControlAction.FORWARD, ControlAction.BACKWARD, ControlAction.LEFT, ControlAction.RIGHT,
                    ControlAction.SNEAK, ControlAction.ATTACK, ControlAction.USE, ControlAction.GUI,
                    ControlAction.INVENTORY -> 50.0
                    ControlAction.JUMP -> 56.0
                    else -> 76.0
                }
            val heightVal = item.optDouble("heightDp", -1.0).takeIf { it > 0 }
                ?: item.optDouble("height", -1.0).takeIf { it >= 1.0 }
                ?: item.optDouble("size", -1.0).takeIf { it >= 1.0 }
                ?: when (action) {
                    ControlAction.FORWARD, ControlAction.BACKWARD, ControlAction.LEFT, ControlAction.RIGHT,
                    ControlAction.SNEAK, ControlAction.ATTACK, ControlAction.USE, ControlAction.GUI,
                    ControlAction.INVENTORY -> 50.0
                    ControlAction.JUMP -> 56.0
                    else -> 30.0
                }

            ControlPlacement(
                id = item.getString("id").take(64),
                kind = ControlKind.valueOf(item.getString("kind")),
                action = action,
                name = item.optString("name", ""),
                x = item.getDouble("x").toFloat(),
                y = item.getDouble("y").toFloat(),
                widthDp = widthVal.toFloat(),
                heightDp = heightVal.toFloat(),
                width = item.optDouble("width", widthVal / 800.0).toFloat(),
                height = item.optDouble("height", heightVal / 400.0).toFloat(),
                size = item.optDouble("size", 0.14).toFloat(),
                opacity = item.optDouble("opacity", 1.0).toFloat(),
                isToggle = item.optBoolean("isToggle", false),
                passThru = item.optBoolean("passThru", false),
                swipeable = item.optBoolean("swipeable", false),
                forwardLock = item.optBoolean("forwardLock", false),
                cornerRadius = item.optDouble("cornerRadius", 0.0).toFloat(),
                strokeWidth = item.optDouble("strokeWidth", 0.0).toFloat(),
                bgColor = item.optLong("bgColor", 0x66000000L),
                strokeColor = item.optLong("strokeColor", 0xFFFFFFFFL),
            ).sanitized()
        }.also { items -> require(items.map { it.id }.distinct().size == items.size) }
    }.getOrElse { defaultControlLayout() }

    fun save(controls: List<ControlPlacement>, landscape: Boolean = true) {
        require(controls.size in 1..40) { "Keep between 1 and 40 controls." }
        require(controls.map { it.id }.distinct().size == controls.size) { "Control IDs must be unique." }
        val array = JSONArray()
        controls.forEach { control ->
            val clean = control.sanitized()
            array.put(
                JSONObject()
                    .put("id", clean.id)
                    .put("kind", clean.kind.name)
                    .put("action", clean.action.name)
                    .put("name", clean.name)
                    .put("x", clean.x)
                    .put("y", clean.y)
                    .put("widthDp", clean.widthDp)
                    .put("heightDp", clean.heightDp)
                    .put("width", clean.width)
                    .put("height", clean.height)
                    .put("size", clean.size)
                    .put("opacity", clean.opacity)
                    .put("isToggle", clean.isToggle)
                    .put("passThru", clean.passThru)
                    .put("swipeable", clean.swipeable)
                    .put("forwardLock", clean.forwardLock)
                    .put("cornerRadius", clean.cornerRadius)
                    .put("strokeWidth", clean.strokeWidth)
                    .put("bgColor", clean.bgColor)
                    .put("strokeColor", clean.strokeColor),
            )
        }
        val root = JSONObject().put("version", 2).put("controls", array)
        val file = atomicFile(landscape)
        val parent = file.baseFile.parentFile ?: error("Layout needs a parent directory.")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create ${parent.name}." }
        val stream = file.startWrite()
        try {
            stream.bufferedWriter().use { it.write(root.toString()) }
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun reset(landscape: Boolean = true) {
        atomicFile(landscape).delete()
    }

    private fun atomicFile(landscape: Boolean): AtomicFile {
        val suffix = if (landscape) "landscape" else "portrait"
        return AtomicFile(File(directory, "controls.$suffix.json"))
    }
}
