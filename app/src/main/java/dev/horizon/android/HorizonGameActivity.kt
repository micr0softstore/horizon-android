package dev.horizon.android

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.androidgamesdk.GameActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * GameActivity owns the native SurfaceView; the ComposeView is an ordinary sibling in its window.
 * Nothing is drawn over other apps and no accessibility or system-overlay permission is needed.
 * The manifest isolates this Activity in :game because Winit permits one event loop per process.
 */
class HorizonGameActivity : GameActivity() {
    private var controls: ControlBridge? = null
    private var confirmExit by mutableStateOf(false)
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var isDragMotion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ignoreNotch = intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false)
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

        // When ignoreNotch is disabled, adjust mSurfaceView margins to prevent the game surface
        // from extending beneath display cutouts/notches.
        if (!ignoreNotch && Build.VERSION.SDK_INT >= 28) {
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
                val cutout = insets.displayCutout
                if (cutout != null) {
                    val surface = mSurfaceView
                    if (surface != null) {
                        val lp = surface.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null && (
                            lp.leftMargin != cutout.safeInsetLeft ||
                            lp.topMargin != cutout.safeInsetTop ||
                            lp.rightMargin != cutout.safeInsetRight ||
                            lp.bottomMargin != cutout.safeInsetBottom
                        )) {
                            lp.setMargins(
                                cutout.safeInsetLeft,
                                cutout.safeInsetTop,
                                cutout.safeInsetRight,
                                cutout.safeInsetBottom,
                            )
                            surface.layoutParams = lp
                        }
                    }
                }
                insets
            }
        }

        val profileId = intent.getStringExtra(HorizonLauncher.EXTRA_PROFILE_ID) ?: HorizonPaths.DEFAULT_PROFILE_ID
        val profile = HorizonPaths.forProfile(this, profileId)
        val bridge = ControlBridge(profile.controlEndpoint)
        controls = bridge
        bridge.start()

        // Hook touch listener on SurfaceView for swipe-to-look camera rotation and hotbar taps in-game,
        // and forward all MotionEvents to GameActivity's native layer so Winit/Horizon processes menu clicks.
        mSurfaceView?.setOnTouchListener { _, ev ->
            processMotionEvent(ev)
            val inMenu = controls?.isMenuOpen?.value ?: true
            if (!inMenu) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startTouchX = ev.x
                        startTouchY = ev.y
                        lastTouchX = ev.x
                        lastTouchY = ev.y
                        isDragMotion = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - lastTouchX
                        val dy = ev.y - lastTouchY
                        lastTouchX = ev.x
                        lastTouchY = ev.y
                        if (abs(ev.x - startTouchX) > 12f || abs(ev.y - startTouchY) > 12f) {
                            isDragMotion = true
                            controls?.turn(dx * 0.15f, dy * 0.15f)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragMotion) {
                            val surface = mSurfaceView
                            if (surface != null && surface.width > 0 && surface.height > 0) {
                                val hotbarLeft = surface.width * 0.25f
                                val hotbarRight = surface.width * 0.75f
                                val hotbarTop = surface.height * 0.82f
                                if (ev.y >= hotbarTop && ev.x in hotbarLeft..hotbarRight) {
                                    val relX = (ev.x - hotbarLeft) / (hotbarRight - hotbarLeft)
                                    val slot = (relX * 9).toInt().coerceIn(0, 8) + 1
                                    controls?.hotbar(slot)
                                }
                            }
                        }
                    }
                }
            }
            true
        }

        val layoutStore = ControlLayoutStore(this, profileId)
        addContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    HorizonTheme {
                        GameOverlay(
                            layoutStore = layoutStore,
                            bridge = bridge,
                            ignoreNotch = ignoreNotch,
                            onInjectNativeTap = ::injectNativeTap,
                            onInjectNativeTouch = ::injectNativeTouch,
                            onInjectNativeMove = ::injectNativeMove,
                            onInjectNativeKey = ::injectNativeKey,
                            onForceClose = {
                                finish()
                                if (isGameProcess()) Process.killProcess(Process.myPid())
                            },
                        )
                        if (confirmExit) {
                            AlertDialog(
                                onDismissRequest = { confirmExit = false },
                                title = { Text("Close the game?") },
                                text = {
                                    Text("The game controls are not connected yet. You can keep waiting, or close the game. Unsaved progress may be lost.")
                                },
                                confirmButton = {
                                    TextButton(onClick = { confirmExit = false; finish() }) { Text("Close game") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmExit = false }) { Text("Keep waiting") }
                                },
                            )
                        }
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The pad Start action follows the game's own pause/back/save flow.
                if (bridge.state.value == ControlConnection.CONNECTED) {
                    bridge.releaseAndTap(ControlAction.PAUSE)
                } else {
                    bridge.releaseAll()
                    confirmExit = true
                }
            }
        })
        lifecycleScope.launch {
            while (isActive) {
                val status = withContext(Dispatchers.IO) { RuntimeStatusStore.read(this@HorizonGameActivity) }
                if (status?.state == "completed" || status?.state == "failed") {
                    finish()
                    break
                }
                delay(500)
            }
        }
    }

    /**
     * Injects synthetic touch events at the specified coordinates (used by virtual mouse and control clicks).
     */
    fun injectNativeTap(x: Float, y: Float) {
        val insetX = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        } else 0
        val insetY = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
        } else 0
        val finalX = x + insetX
        val finalY = y + insetY

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, finalX, finalY, 0)
        processMotionEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, finalX, finalY, 0)
        processMotionEvent(up)
        up.recycle()
    }

    fun injectNativeTouch(x: Float, y: Float, down: Boolean, secondary: Boolean = false) {
        val insetX = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        } else 0
        val insetY = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
        } else 0
        val finalX = x + insetX
        val finalY = y + insetY

        val now = SystemClock.uptimeMillis()
        val action = if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
        if (secondary) {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = finalX
                this.y = finalY
                pressure = 1f
                size = 1f
            })
            val ev = MotionEvent.obtain(
                now, now, action, 1, props, coords, 0,
                if (down) MotionEvent.BUTTON_SECONDARY else 0,
                1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0
            )
            processMotionEvent(ev)
            ev.recycle()
        } else {
            val ev = MotionEvent.obtain(now, now, action, finalX, finalY, 0)
            processMotionEvent(ev)
            ev.recycle()
        }
    }

    fun injectNativeMove(x: Float, y: Float) {
        val insetX = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        } else 0
        val insetY = if (!intent.getBooleanExtra(HorizonLauncher.EXTRA_IGNORE_NOTCH, false) && Build.VERSION.SDK_INT >= 28) {
            window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
        } else 0
        val finalX = x + insetX
        val finalY = y + insetY

        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, finalX, finalY, 0)
        processMotionEvent(ev)
        ev.recycle()
    }

    fun injectNativeKey(keyCode: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val event = KeyEvent(now, now, action, keyCode, 0)
        if (down) {
            onKeyDown(keyCode, event)
        } else {
            onKeyUp(keyCode, event)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (!handled) {
            return processMotionEvent(ev)
        }
        return handled
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else controls?.releaseAll()
    }

    override fun onPause() {
        controls?.releaseAll()
        super.onPause()
    }

    override fun onDestroy() {
        controls?.close()
        controls = null
        // GameActivity's native destruction joins the android_main thread before returning.
        super.onDestroy()
        if (isGameProcess()) Process.killProcess(Process.myPid())
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isGameProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            val pid = Process.myPid()
            val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return name == "$packageName:game"
    }
}
