package dev.horizon.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControlLayoutTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultLayoutHasUncompressedDpDimensions() {
        val layout = defaultControlLayout()
        assertTrue(layout.isNotEmpty())

        // Top bar buttons should be 76x30 or 86x30 dp (crisp rectangles)
        val debugBtn = layout.first { it.id == "debug" }
        assertEquals(76f, debugBtn.widthDp)
        assertEquals(30f, debugBtn.heightDp)

        // D-pad movement buttons should be perfectly square (50x50 dp)
        val upBtn = layout.first { it.id == "up" }
        assertEquals(50f, upBtn.widthDp)
        assertEquals(50f, upBtn.heightDp)

        val priBtn = layout.first { it.id == "pri" }
        assertEquals(50f, priBtn.widthDp)
        assertEquals(50f, priBtn.heightDp)

        // Jump button should be square (56x56 dp)
        val jumpBtn = layout.first { it.id == "jump" }
        assertEquals(56f, jumpBtn.widthDp)
        assertEquals(56f, jumpBtn.heightDp)
    }

    @Test
    fun defaultLayoutHasNoOverlappingControls() {
        val layout = defaultControlLayout()
        // Test standard phone landscape resolutions in dp (e.g. 640x290, 720x320, 800x360)
        for ((w, h) in listOf(640f to 290f, 720f to 320f, 800f to 360f)) {
            val boxes = layout.map { ctrl ->
                val left = max(0f, min(w - ctrl.widthDp, ctrl.x * w - ctrl.widthDp / 2f))
                val top = max(0f, min(h - ctrl.heightDp, ctrl.y * h - ctrl.heightDp / 2f))
                ctrl.id to (left to (left + ctrl.widthDp) to (top to (top + ctrl.heightDp)))
            }

            for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    val (id1, coords1) = boxes[i]
                    val (left1, right1) = coords1.first
                    val (top1, bottom1) = coords1.second

                    val (id2, coords2) = boxes[j]
                    val (left2, right2) = coords2.first
                    val (top2, bottom2) = coords2.second

                    val overlaps = !(right1 <= left2 + 0.1f || right2 <= left1 + 0.1f ||
                        bottom1 <= top2 + 0.1f || bottom2 <= top1 + 0.1f)

                    assertFalse(
                        "Buttons '$id1' and '$id2' must not overlap at resolution ${w.toInt()}x${h.toInt()} dp",
                        overlaps,
                    )
                }
            }
        }
    }

    @Test
    fun controlLayoutStoreSavesAndLoadsDpDimensions() {
        val store = ControlLayoutStore(context, "test_profile")
        val customLayout = listOf(
            ControlPlacement(
                id = "custom_btn",
                kind = ControlKind.BUTTON,
                action = ControlAction.ATTACK,
                name = "PUNCH",
                x = 0.4f,
                y = 0.6f,
                widthDp = 80f,
                heightDp = 35f,
                isToggle = true,
                passThru = true,
                forwardLock = true,
                cornerRadius = 8f,
                strokeWidth = 2f,
            ),
        )

        store.save(customLayout)
        val loaded = store.load()

        assertEquals(1, loaded.size)
        val btn = loaded.first()
        assertEquals("custom_btn", btn.id)
        assertEquals("PUNCH", btn.name)
        assertEquals(80f, btn.widthDp)
        assertEquals(35f, btn.heightDp)
        assertTrue(btn.isToggle)
        assertTrue(btn.passThru)
        assertTrue(btn.forwardLock)
        assertEquals(8f, btn.cornerRadius)
        assertEquals(2f, btn.strokeWidth)
    }

    @Test
    fun activeLaunchStoreDoesNotWriteIgnoreNotchToPreventRustCrash() {
        val profile = HorizonPaths.forProfile(context, "default")
        profile.ensureDirectories()

        ActiveLaunchStore.write(context, profile, 16)
        val file = HorizonPaths.activeLaunchFile(context)
        assertTrue(file.isFile)

        val jsonText = file.readText()
        assertFalse(
            "active-launch.json must not contain ignoreNotch as Rust serde denies unknown fields",
            jsonText.contains("ignoreNotch"),
        )

        val config = ActiveLaunchStore.read(context)
        assertNotNull(config)
        assertEquals(16, config?.renderDistance)
    }

    @Test
    fun launchSettingsStoreSavesIgnoreNotch() {
        val settings = LaunchSettingsStore(context)
        assertFalse(settings.ignoreNotch("default"))

        settings.setIgnoreNotch("default", true)
        assertTrue(settings.ignoreNotch("default"))

        settings.setIgnoreNotch("default", false)
        assertFalse(settings.ignoreNotch("default"))
    }
}
