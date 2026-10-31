package dev.horizon.android

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Dedicated full-screen landscape activity for customizing touch controls,
 * opened with a clean black background from the launcher.
 */
class ControlEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val profileId = intent.getStringExtra(HorizonLauncher.EXTRA_PROFILE_ID) ?: HorizonPaths.DEFAULT_PROFILE_ID
        val settings = LaunchSettingsStore(this)
        val ignoreNotch = intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, settings.ignoreNotch(profileId))

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (ignoreNotch) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
        }
        hideSystemBars()

        val layoutStore = ControlLayoutStore(this, profileId)

        setContent {
            HorizonTheme {
                ControlsEditor(
                    layoutStore = layoutStore,
                    embedded = false,
                    ignoreNotch = ignoreNotch,
                    onDone = { finish() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
