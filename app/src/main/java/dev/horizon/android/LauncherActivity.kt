package dev.horizon.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent

/** The launcher remains in the default app process; GameActivity is isolated in :game. */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profile = HorizonPaths.forProfile(this)
        setContent {
            HorizonTheme {
                LauncherScreen(
                    profile = profile,
                    onLaunch = { distance -> HorizonLauncher.launch(this, profile, distance) },
                )
            }
        }
    }
}
