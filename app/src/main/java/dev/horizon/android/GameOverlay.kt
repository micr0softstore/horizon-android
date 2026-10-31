package dev.horizon.android

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun GameOverlay(
    layoutStore: ControlLayoutStore,
    bridge: ControlBridge?,
    modifier: Modifier = Modifier,
    ignoreNotch: Boolean = false,
    onInjectNativeTap: ((Float, Float) -> Unit)? = null,
    onInjectNativeTouch: ((Float, Float, Boolean, Boolean) -> Unit)? = null,
    onInjectNativeMove: ((Float, Float) -> Unit)? = null,
    onInjectNativeKey: ((Int, Boolean) -> Unit)? = null,
    onForceClose: (() -> Unit)? = null,
) {
    ControlsEditor(
        layoutStore = layoutStore,
        bridge = bridge,
        modifier = modifier,
        ignoreNotch = ignoreNotch,
        onInjectNativeTap = onInjectNativeTap,
        onInjectNativeTouch = onInjectNativeTouch,
        onInjectNativeMove = onInjectNativeMove,
        onInjectNativeKey = onInjectNativeKey,
        onForceClose = onForceClose,
        embedded = false,
        onDone = {},
    )
}

@Composable
fun ControlsEditor(
    layoutStore: ControlLayoutStore,
    modifier: Modifier = Modifier,
    bridge: ControlBridge? = null,
    embedded: Boolean = false,
    ignoreNotch: Boolean = false,
    onInjectNativeTap: ((Float, Float) -> Unit)? = null,
    onInjectNativeTouch: ((Float, Float, Boolean, Boolean) -> Unit)? = null,
    onInjectNativeMove: ((Float, Float) -> Unit)? = null,
    onInjectNativeKey: ((Int, Boolean) -> Unit)? = null,
    onForceClose: (() -> Unit)? = null,
    onDone: () -> Unit = {},
) {
    val connection = bridge?.state?.collectAsState()?.value ?: ControlConnection.CONNECTED
    var controls by remember { mutableStateOf(layoutStore.load()) }
    var saved by remember { mutableStateOf(controls) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(embedded || bridge == null) }
    var showInGameMenu by remember { mutableStateOf(false) }
    var showEditorMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showKeycodeDialog by remember { mutableStateOf(false) }
    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var isGuiHidden by remember { mutableStateOf(false) }
    var isVirtualMouseEnabled by remember { mutableStateOf(true) }
    val isMenuOpen by bridge?.isMenuOpen?.collectAsState() ?: remember { mutableStateOf(true) }
    val showVirtualMouse = !editing && isVirtualMouseEnabled && isMenuOpen
    var mousePosition by remember { mutableStateOf(Offset(400f, 300f)) }
    var swipeSensitivity by remember { mutableFloatStateOf(0.15f) }
    var invertYLook by remember { mutableStateOf(false) }

    // Toggle states for lockable/toggleable buttons
    val toggledButtons = remember { mutableStateMapOf<String, Boolean>() }

    // Keyboard support: ONLY mounted when isKeyboardOpen is true, preventing Compose from
    // capturing focus and popping up the IME keyboard on direct screen taps!
    var isKeyboardOpen by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardFocus = remember { FocusRequester() }
    var keyboardInput by remember { mutableStateOf(TextFieldValue("")) }

    val selected = controls.firstOrNull { it.id == selectedId }

    fun update(placement: ControlPlacement) {
        controls = controls.map { if (it.id == placement.id) placement.sanitized() else it }
    }

    fun finish(save: Boolean) {
        bridge?.releaseAll()
        if (save) {
            runCatching { layoutStore.save(controls) }.onSuccess {
                saved = controls
                saveNotice = "Layout saved successfully"
            }.onFailure {
                saveNotice = it.message ?: "Couldn't save layout."
                return
            }
        } else {
            controls = saved
        }
        selectedId = null
        showEditorMenu = false
        showInGameMenu = false
        if (!embedded) editing = false
        onDone()
    }

    if (isKeyboardOpen) {
        BasicTextField(
            value = keyboardInput,
            onValueChange = { newVal ->
                val text = newVal.text
                if (text.isNotEmpty()) {
                    bridge?.typeText(text, submit = false)
                }
                keyboardInput = TextFieldValue("")
            },
            modifier = Modifier
                .size(1.dp)
                .offset((-100).dp, (-100).dp)
                .focusRequester(keyboardFocus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                bridge?.typeText("", submit = true)
                keyboardController?.hide()
                isKeyboardOpen = false
            }),
        )
        LaunchedEffect(isKeyboardOpen) {
            keyboardFocus.requestFocus()
            keyboardController?.show()
        }
    }

    val contentModifier = if (ignoreNotch) modifier.fillMaxSize() else modifier.fillMaxSize().safeDrawingPadding()

    BoxWithConstraints(contentModifier) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        // 1. Virtual Mouse Touchpad Zone (ONLY active in menus when virtual mouse is ON and NOT editing)
        // When in gameplay or virtual mouse is OFF, NO full-screen gesture detector exists, allowing direct touch on the game.
        if (showVirtualMouse) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                mousePosition = Offset(
                                    (mousePosition.x + dragAmount.x).coerceIn(0f, screenWidth),
                                    (mousePosition.y + dragAmount.y).coerceIn(0f, screenHeight),
                                )
                                onInjectNativeMove?.invoke(mousePosition.x, mousePosition.y)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Tap triggers synthetic native click at cursor position AND socket mouse click
                                onInjectNativeTap?.invoke(mousePosition.x, mousePosition.y)
                                bridge?.mouse("left", "click")
                            },
                        )
                    },
            )
        }

        // 2. Control buttons canvas
        ControlCanvas(
            controls = controls,
            editing = editing,
            selectedId = selectedId,
            bridge = bridge,
            inputEnabled = connection == ControlConnection.CONNECTED && !editing,
            isGuiHidden = isGuiHidden,
            toggledButtons = toggledButtons,
            onSelect = { selectedId = it },
            onUpdate = ::update,
            onInjectNativeTap = onInjectNativeTap,
            onInjectNativeTouch = onInjectNativeTouch,
            onInjectNativeMove = onInjectNativeMove,
            onInjectNativeKey = onInjectNativeKey,
            mousePosition = mousePosition,
            isVirtualMouseEnabled = isVirtualMouseEnabled,
            showVirtualMouse = showVirtualMouse,
            onBuiltinAction = { action ->
                when (action) {
                    ControlAction.GUI -> isGuiHidden = !isGuiHidden
                    ControlAction.MOUSE -> isVirtualMouseEnabled = !isVirtualMouseEnabled
                    ControlAction.KEYBOARD -> {
                        isKeyboardOpen = !isKeyboardOpen
                        if (!isKeyboardOpen) keyboardController?.hide()
                    }
                    else -> {}
                }
            },
            modifier = Modifier.fillMaxSize(),
            preview = embedded,
        )

        // 3. Virtual Mouse cursor with proper aspect ratio (not vertically compressed)
        // Only visible in menus when virtual mouse is enabled
        if (showVirtualMouse) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .size(width = 18.dp, height = 26.dp)
                    .offset { IntOffset(mousePosition.x.roundToInt(), mousePosition.y.roundToInt()) },
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(0f, size.height * 0.90f)
                    lineTo(size.width * 0.35f, size.height * 0.65f)
                    lineTo(size.width * 0.65f, size.height)
                    lineTo(size.width * 0.85f, size.height * 0.90f)
                    lineTo(size.width * 0.55f, size.height * 0.55f)
                    lineTo(size.width * 0.95f, size.height * 0.55f)
                    close()
                }
                drawPath(path, Color.White)
                drawPath(path, Color.Black, style = Stroke(1.5f))
            }
        }

        // 4. Cogwheel / Settings Icon (top center-right, matching screenshots)
        if (!isGuiHidden || editing) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 76.dp),
            ) {
                IconButton(
                    onClick = {
                        if (editing) {
                            showEditorMenu = !showEditorMenu
                        } else {
                            showInGameMenu = !showInGameMenu
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Control Settings",
                        tint = if (editing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }

        // 5. In-Game Quick Menu Drawer (matching photo_2026-10-31_12-18-25.jpg)
        // Opened when gear icon is tapped during gameplay (not in editor)
        if (!editing && showInGameMenu) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(260.dp),
                color = Color(0xFF262626).copy(alpha = 0.97f),
                tonalElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    InGameDrawerItem("Force close") {
                        showInGameMenu = false
                        onForceClose?.invoke()
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    InGameDrawerItem("Log output") {
                        showInGameMenu = false
                        showLogDialog = true
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    InGameDrawerItem("Send custom keycode") {
                        showInGameMenu = false
                        showKeycodeDialog = true
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    InGameDrawerItem("Quick settings") {
                        showInGameMenu = false
                        showQuickSettingsDialog = true
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    InGameDrawerItem("Custom controls") {
                        showInGameMenu = false
                        bridge?.releaseAll()
                        saved = controls
                        editing = true
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    Spacer(Modifier.weight(1f))

                    TextButton(
                        onClick = { showInGameMenu = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Text("Close Menu", color = Color.Gray)
                    }
                }
            }
        }

        // 6. Editor Actions Menu Drawer (opened from gear icon when in editor mode)
        if (editing && showEditorMenu) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 8.dp)
                    .width(240.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF282828).copy(alpha = 0.97f),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "Custom Controls",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    DrawerItem("Add button") {
                        showEditorMenu = false
                        showAddDialog = true
                    }
                    DrawerItem("Load") {
                        controls = layoutStore.load()
                        selectedId = null
                        showEditorMenu = false
                    }
                    DrawerItem("Save") {
                        finish(true)
                    }
                    DrawerItem("Select default controlmap") {
                        controls = defaultControlLayout()
                        selectedId = null
                        showEditorMenu = false
                    }
                    DrawerItem("Export controlmap") {
                        layoutStore.save(controls)
                        saveNotice = "Controlmap exported."
                        showEditorMenu = false
                    }
                    if (!embedded) {
                        DrawerItem("Exit editor") {
                            finish(false)
                        }
                    }
                }
            }
        }

        // 7. Selected Button Top Bar Actions: [DELETE] and [CLONE] (matching Mojolauncher)
        if (editing && selected != null) {
            val alignStart = selected.x >= 0.5f
            Row(
                Modifier
                    .align(if (alignStart) Alignment.TopStart else Alignment.TopEnd)
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF333333).copy(alpha = 0.95f),
                ) {
                    TextButton(
                        onClick = {
                            controls = controls.filterNot { it.id == selected.id }
                            selectedId = null
                        },
                        enabled = controls.size > 1,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp), tint = Color(0xFFFF5252))
                        Spacer(Modifier.width(4.dp))
                        Text("DELETE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF333333).copy(alpha = 0.95f),
                ) {
                    TextButton(
                        onClick = {
                            val cloned = selected.copy(
                                id = UUID.randomUUID().toString(),
                                x = (selected.x + 0.04f).coerceIn(0.05f, 0.95f),
                                y = (selected.y + 0.04f).coerceIn(0.05f, 0.95f),
                            )
                            controls = controls + cloned
                            selectedId = cloned.id
                        },
                        enabled = controls.size < 40,
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(18.dp), tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(4.dp))
                        Text("CLONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // 8. Mojolauncher Button Properties Side Drawer (dialog_control_button_setting.xml)
            // Positioned opposite from the selected button so it never covers it
            val drawerAlign = if (selected.x < 0.5f) Alignment.TopEnd else Alignment.TopStart
            Surface(
                modifier = Modifier
                    .align(drawerAlign)
                    .padding(top = 48.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .width(280.dp)
                    .heightIn(max = screenHeight.dp - 60.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF222222).copy(alpha = 0.97f),
                tonalElevation = 8.dp,
            ) {
                MojolauncherButtonSettingsDrawer(
                    control = selected,
                    onUpdate = ::update,
                    onDone = { selectedId = null },
                )
            }
        }

        // Helper instruction banner when in editor
        if (editing && selected == null && !showEditorMenu) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF222222).copy(alpha = 0.90f),
            ) {
                Text(
                    "Touch \u2699 for menu \u00b7 Tap a button to edit properties",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showAddDialog) {
        AddButtonDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { action, name ->
                val newBtn = ControlPlacement(
                    action = action,
                    name = name,
                    x = 0.5f,
                    y = 0.5f,
                    widthDp = if (action in setOf(ControlAction.FORWARD, ControlAction.BACKWARD, ControlAction.LEFT, ControlAction.RIGHT, ControlAction.JUMP)) 50f else 76f,
                    heightDp = if (action in setOf(ControlAction.FORWARD, ControlAction.BACKWARD, ControlAction.LEFT, ControlAction.RIGHT, ControlAction.JUMP)) 50f else 30f,
                )
                controls = controls + newBtn
                selectedId = newBtn.id
                showAddDialog = false
            },
        )
    }

    if (showLogDialog) {
        val context = LocalContext.current
        var statusText by remember { mutableStateOf("Loading status…") }
        LaunchedEffect(Unit) {
            val status = withContext(Dispatchers.IO) { RuntimeStatusStore.read(context) }
            statusText = if (status != null) {
                "State: ${status.state}\nMessage: ${status.message ?: "None"}\nUpdated: ${status.updatedAtMillis}"
            } else {
                "No runtime status recorded yet. Engine running."
            }
        }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Log Output") },
            text = {
                Text(
                    statusText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text("Close") } },
        )
    }

    if (showKeycodeDialog && bridge != null) {
        CustomKeycodeDialog(
            bridge = bridge,
            onDismiss = { showKeycodeDialog = false },
        )
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            isVirtualMouse = isVirtualMouseEnabled,
            onToggleVirtualMouse = { isVirtualMouseEnabled = it },
            sensitivity = swipeSensitivity,
            onSensitivityChange = { swipeSensitivity = it },
            invertY = invertYLook,
            onToggleInvertY = { invertYLook = it },
            onDismiss = { showQuickSettingsDialog = false },
        )
    }

    if (saveNotice != null) {
        AlertDialog(
            onDismissRequest = { saveNotice = null },
            title = { Text("Custom Controls") },
            text = { Text(saveNotice.orEmpty()) },
            confirmButton = { TextButton(onClick = { saveNotice = null }) { Text("OK") } },
        )
    }

    if (showTextDialog && bridge != null) {
        GameTextDialog(bridge = bridge, onDismiss = { showTextDialog = false })
    }
}

@Composable
private fun InGameDrawerItem(title: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun DrawerItem(title: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * Button Properties Side Drawer faithfully reproducing PojavLauncher / Mojolauncher's dialog_control_button_setting.xml
 */
@Composable
private fun MojolauncherButtonSettingsDrawer(
    control: ControlPlacement,
    onUpdate: (ControlPlacement) -> Unit,
    onDone: () -> Unit,
) {
    var nameText by remember(control.id) { mutableStateOf(control.name.ifBlank { control.action.label }) }
    var widthText by remember(control.id) { mutableStateOf(control.widthDp.toInt().toString()) }
    var heightText by remember(control.id) { mutableStateOf(control.heightDp.toInt().toString()) }
    var showActionMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title Bar
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Button Properties", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onDone, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

        // 1. EDIT NAME SECTION
        Text("Name", color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(
            value = nameText,
            onValueChange = {
                nameText = it
                onUpdate(control.copy(name = it))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // 2. SIZE SECTION (Width x Height in DP)
        Text("Size (Width x Height dp)", color = Color.Gray, fontSize = 12.sp)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = {
                    widthText = it
                    val w = it.toFloatOrNull()
                    if (w != null && w in 20f..300f) {
                        onUpdate(control.copy(widthDp = w))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Text("x", color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = heightText,
                onValueChange = {
                    heightText = it
                    val h = it.toFloatOrNull()
                    if (h != null && h in 20f..300f) {
                        onUpdate(control.copy(heightDp = h))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        // 3. MAPPING SECTION
        Text("Mapping", color = Color.Gray, fontSize = 12.sp)
        Box {
            OutlinedTextField(
                value = control.action.title,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { showActionMenu = true }
                    },
            )
            DropdownMenu(
                expanded = showActionMenu,
                onDismissRequest = { showActionMenu = false },
            ) {
                ControlAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.title) },
                        onClick = {
                            onUpdate(control.copy(action = action))
                            showActionMenu = false
                        },
                    )
                }
            }
        }

        // 4. SWITCHES: Toggle, PassThru, Swipeable, ForwardLock
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Toggle button", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Switch(checked = control.isToggle, onCheckedChange = { onUpdate(control.copy(isToggle = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Mouse pass through", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Switch(checked = control.passThru, onCheckedChange = { onUpdate(control.copy(passThru = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Swipeable", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Switch(checked = control.swipeable, onCheckedChange = { onUpdate(control.copy(swipeable = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Forward lock", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Switch(checked = control.forwardLock, onCheckedChange = { onUpdate(control.copy(forwardLock = it)) })
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

        // 5. STROKE WIDTH
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Stroke width", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("${(control.strokeWidth * 20).toInt()}%", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = control.strokeWidth,
            onValueChange = { onUpdate(control.copy(strokeWidth = it)) },
            valueRange = 0f..5f,
        )

        // 6. CORNER RADIUS
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Corner radius", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("${control.cornerRadius.toInt()} dp", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = control.cornerRadius,
            onValueChange = { onUpdate(control.copy(cornerRadius = it)) },
            valueRange = 0f..25f,
        )

        // 7. BUTTON OPACITY
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Button opacity", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("${(control.opacity * 100).toInt()}%", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = control.opacity,
            onValueChange = { onUpdate(control.copy(opacity = it)) },
            valueRange = 0.1f..1.0f,
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun QuickSettingsDialog(
    isVirtualMouse: Boolean,
    onToggleVirtualMouse: (Boolean) -> Unit,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    invertY: Boolean,
    onToggleInvertY: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Virtual Mouse")
                    Switch(checked = isVirtualMouse, onCheckedChange = onToggleVirtualMouse)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Invert Look (Y-axis)")
                    Switch(checked = invertY, onCheckedChange = onToggleInvertY)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Look Sensitivity: ${(sensitivity * 100).toInt()}%")
                    Slider(
                        value = sensitivity,
                        onValueChange = onSensitivityChange,
                        valueRange = 0.05f..0.50f,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CustomKeycodeDialog(bridge: ControlBridge, onDismiss: () -> Unit) {
    var customText by remember { mutableStateOf("") }
    val commonKeys = listOf(
        "F1" to "key f1 tap",
        "F2" to "key f2 tap",
        "F3" to "key f3 tap",
        "F5" to "key f5 tap",
        "F11" to "key f11 tap",
        "ESC" to "key esc tap",
        "TAB" to "key tab tap",
        "ENTER" to "key enter tap",
        "SPACE" to "pad south tap",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Custom Keycode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tap a key to send directly to the game:", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    commonKeys.take(4).forEach { (label, cmd) ->
                        OutlinedButton(
                            onClick = {
                                bridge.tap(ControlAction.valueOf(if (label == "F3") "DEBUG" else if (label == "ESC") "ESCAPE" else "JUMP"))
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    commonKeys.drop(4).take(4).forEach { (label, cmd) ->
                        OutlinedButton(
                            onClick = {
                                bridge.tap(ControlAction.valueOf(if (label == "TAB") "TAB" else if (label == "ESC") "ESCAPE" else "JUMP"))
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Type text to send") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (customText.isNotBlank()) {
                    bridge.typeText(customText, submit = true)
                }
                onDismiss()
            }) {
                Text("Send Text")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddButtonDialog(
    onDismiss: () -> Unit,
    onAdd: (ControlAction, String) -> Unit,
) {
    var selectedAction by remember { mutableStateOf(ControlAction.JUMP) }
    var customName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Button Label (e.g. F3, RUN)") },
                    placeholder = { Text(selectedAction.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Select Action:", style = MaterialTheme.typography.labelMedium)
                Column(
                    Modifier
                        .height(200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ControlAction.entries.forEach { action ->
                        TextButton(
                            onClick = { selectedAction = action },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                action.title,
                                fontWeight = if (selectedAction == action) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onAdd(selectedAction, customName.ifBlank { selectedAction.label })
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ControlCanvas(
    controls: List<ControlPlacement>,
    editing: Boolean,
    selectedId: String?,
    bridge: ControlBridge?,
    inputEnabled: Boolean,
    isGuiHidden: Boolean,
    toggledButtons: MutableMap<String, Boolean>,
    onSelect: (String) -> Unit,
    onUpdate: (ControlPlacement) -> Unit,
    onInjectNativeTap: ((Float, Float) -> Unit)?,
    onInjectNativeTouch: ((Float, Float, Boolean, Boolean) -> Unit)?,
    onInjectNativeMove: ((Float, Float) -> Unit)?,
    onInjectNativeKey: ((Int, Boolean) -> Unit)?,
    mousePosition: Offset,
    isVirtualMouseEnabled: Boolean,
    showVirtualMouse: Boolean,
    onBuiltinAction: (ControlAction) -> Unit,
    modifier: Modifier,
    preview: Boolean,
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier.then(if (preview) Modifier.background(Color(0xFF141414)) else Modifier)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        if (preview || editing) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val gridLine = Color.White.copy(alpha = 0.05f)
                repeat(20) { i ->
                    val x = size.width * i / 19f
                    drawLine(gridLine, Offset(x, 0f), Offset(x, size.height))
                }
                repeat(12) { i ->
                    val y = size.height * i / 11f
                    drawLine(gridLine, Offset(0f, y), Offset(size.width, y))
                }
            }
        }

        controls.forEach { control ->
            // In play mode, if GUI is hidden, only show the GUI button
            if (!editing && isGuiHidden && control.action != ControlAction.GUI) {
                return@forEach
            }

            val buttonWidthPx = with(density) { control.widthDp.dp.toPx() }.coerceAtLeast(36f)
            val buttonHeightPx = with(density) { control.heightDp.dp.toPx() }.coerceAtLeast(28f)
            val left = (control.x * widthPx - buttonWidthPx / 2).coerceIn(0f, (widthPx - buttonWidthPx).coerceAtLeast(0f))
            val top = (control.y * heightPx - buttonHeightPx / 2).coerceIn(0f, (heightPx - buttonHeightPx).coerceAtLeast(0f))

            PojavControlNode(
                control = control,
                editing = editing,
                selected = selectedId == control.id,
                bridge = bridge,
                inputEnabled = inputEnabled,
                isToggled = toggledButtons[control.id] ?: false,
                onToggleChange = { toggledButtons[control.id] = it },
                onBuiltinAction = onBuiltinAction,
                onInjectNativeTap = onInjectNativeTap,
                onInjectNativeTouch = onInjectNativeTouch,
                onInjectNativeKey = onInjectNativeKey,
                mousePosition = mousePosition,
                isVirtualMouseEnabled = isVirtualMouseEnabled,
                showVirtualMouse = showVirtualMouse,
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(width = control.widthDp.dp, height = control.heightDp.dp),
                onSelect = { onSelect(control.id) },
                onMove = { delta ->
                    onUpdate(
                        control.copy(
                            x = ((left + buttonWidthPx / 2 + delta.x) / widthPx).coerceIn(0f, 1f),
                            y = ((top + buttonHeightPx / 2 + delta.y) / heightPx).coerceIn(0f, 1f),
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PojavControlNode(
    control: ControlPlacement,
    editing: Boolean,
    selected: Boolean,
    bridge: ControlBridge?,
    inputEnabled: Boolean,
    isToggled: Boolean,
    onToggleChange: (Boolean) -> Unit,
    onBuiltinAction: (ControlAction) -> Unit,
    onInjectNativeTap: ((Float, Float) -> Unit)?,
    onInjectNativeTouch: ((Float, Float, Boolean, Boolean) -> Unit)?,
    onInjectNativeKey: ((Int, Boolean) -> Unit)?,
    mousePosition: Offset,
    isVirtualMouseEnabled: Boolean,
    showVirtualMouse: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onMove: (Offset) -> Unit,
) {
    var pressed by remember(control.id) { mutableStateOf(false) }
    val latestMove by rememberUpdatedState(onMove)
    val latestSelect by rememberUpdatedState(onSelect)
    val action = control.action

    val gestureModifier = if (editing) {
        Modifier
            .pointerInput(control.id) {
                var distance = Offset.Zero
                var moveFromStart: (Offset) -> Unit = {}
                detectDragGestures(
                    onDragStart = {
                        distance = Offset.Zero
                        moveFromStart = latestMove
                        latestSelect()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        distance += amount
                        moveFromStart(distance)
                    },
                )
            }
            .pointerInput(control.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    latestSelect()
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.id == down.id && it.pressed })
                }
            }
    } else {
        Modifier.pointerInput(control.id, action, inputEnabled, isToggled, showVirtualMouse) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true

                // In menus with virtual mouse: PRI injects primary click/touch at mousePosition; SEC injects secondary touch
                if (action == ControlAction.ATTACK && showVirtualMouse) {
                    onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, true, false)
                }
                if (action == ControlAction.USE && showVirtualMouse) {
                    onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, true, true)
                }
                if (action == ControlAction.TAB) {
                    onInjectNativeKey?.invoke(KeyEvent.KEYCODE_TAB, true)
                }
                if (action == ControlAction.ESCAPE || action == ControlAction.ESC) {
                    onInjectNativeKey?.invoke(KeyEvent.KEYCODE_ESCAPE, true)
                }
                if (action == ControlAction.ENTER) {
                    onInjectNativeKey?.invoke(KeyEvent.KEYCODE_ENTER, true)
                }
                if (action == ControlAction.BACKSPACE) {
                    onInjectNativeKey?.invoke(KeyEvent.KEYCODE_DEL, true)
                }

                if (control.isToggle) {
                    val next = !isToggled
                    onToggleChange(next)
                    if (action == ControlAction.ATTACK && showVirtualMouse) {
                        onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, next, false)
                    }
                    if (action == ControlAction.USE && showVirtualMouse) {
                        onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, next, true)
                    }
                    if (action == ControlAction.TAB) {
                        onInjectNativeKey?.invoke(KeyEvent.KEYCODE_TAB, next)
                    }
                    if (action.command.startsWith("builtin:")) {
                        onBuiltinAction(action)
                    } else if (inputEnabled && bridge != null) {
                        bridge.button(control.id, action, next)
                    }
                } else {
                    if (action.command.startsWith("builtin:")) {
                        onBuiltinAction(action)
                    } else if (inputEnabled && bridge != null) {
                        bridge.button(control.id, action, true)
                    }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                    }
                } finally {
                    pressed = false
                    if (!control.isToggle) {
                        if (action == ControlAction.ATTACK && showVirtualMouse) {
                            onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, false, false)
                        }
                        if (action == ControlAction.USE && showVirtualMouse) {
                            onInjectNativeTouch?.invoke(mousePosition.x, mousePosition.y, false, true)
                        }
                        if (action == ControlAction.TAB) {
                            onInjectNativeKey?.invoke(KeyEvent.KEYCODE_TAB, false)
                        }
                        if (action == ControlAction.ESCAPE || action == ControlAction.ESC) {
                            onInjectNativeKey?.invoke(KeyEvent.KEYCODE_ESCAPE, false)
                        }
                        if (action == ControlAction.ENTER) {
                            onInjectNativeKey?.invoke(KeyEvent.KEYCODE_ENTER, false)
                        }
                        if (action == ControlAction.BACKSPACE) {
                            onInjectNativeKey?.invoke(KeyEvent.KEYCODE_DEL, false)
                        }
                        if (inputEnabled && bridge != null && !action.command.startsWith("builtin:")) {
                            bridge.button(control.id, action, false)
                        }
                    }
                }
            }
        }
    }

    // Appearance matching Mojolauncher
    val shape = if (control.cornerRadius > 0f) RoundedCornerShape(control.cornerRadius.dp) else RoundedCornerShape(2.dp)
    val activeState = pressed || isToggled

    val fillColor = when {
        activeState -> Color(0xFF555555).copy(alpha = 0.70f)
        else -> Color(0xFF1E1E1E).copy(alpha = (control.opacity * 0.45f).coerceIn(0.1f, 1f))
    }

    val borderColor = when {
        selected && editing -> Color(0xFF00E5FF) // Teal/Cyan selection highlight matching screenshots
        activeState -> Color.White.copy(alpha = 0.8f)
        control.strokeWidth > 0f -> Color(control.strokeColor).copy(alpha = control.opacity)
        else -> Color.White.copy(alpha = 0.20f)
    }

    val borderWidth = when {
        selected && editing -> 2.dp
        activeState -> 1.5.dp
        control.strokeWidth > 0f -> control.strokeWidth.dp
        else -> 1.dp
    }

    Box(
        modifier = modifier
            .then(gestureModifier)
            .semantics { contentDescription = "${control.label} button" }
            .clip(shape)
            .background(fillColor)
            .border(borderWidth, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = control.label,
            color = Color.White.copy(alpha = (control.opacity).coerceAtLeast(0.75f)),
            fontSize = if (control.heightDp <= 32f) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}

@Composable
fun GameTextDialog(bridge: ControlBridge, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game Keyboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Command or chat message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Send text directly to chat, commands, or text fields in game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                bridge.typeText(text, submit = true)
                onDismiss()
            }) {
                Text("Send (Enter)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun rememberControlLayoutStore(): ControlLayoutStore {
    val context = LocalContext.current
    return remember(context) { ControlLayoutStore(context) }
}
