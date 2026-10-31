package dev.horizon.android

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exercises the actual launcher and editor without requiring Minecraft assets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherUiTest {
    @get:Rule val compose = createAndroidComposeRule<LauncherActivity>()

    @Test fun launcherOpens() {
        compose.onNodeWithText("Horizon Pocket").assertIsDisplayed()
        screenshot("launcher")
    }

    @Test fun controlEditorOpens() {
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Arrange your touch controls").assertIsDisplayed()
        screenshot("controls")
    }

    @Test fun settingsOpen() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Render distance").assertIsDisplayed()
        screenshot("settings")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val destination = File("build/reports/screenshots/$name.png")
        requireNotNull(destination.parentFile).mkdirs()
        // PixelCopy's platform callback is not driven by Robolectric. Render the
        // measured Activity tree with its native graphics backend instead.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            destination.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
        }
    }
}
