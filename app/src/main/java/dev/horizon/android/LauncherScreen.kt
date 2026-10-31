package dev.horizon.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LauncherDestination(val label: String) {
    HOME("Home"),
    CONTROLS("Controls"),
    SETTINGS("Settings"),
}

private data class LauncherNotice(val text: String, val isError: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    profile: HorizonProfile,
    onLaunch: (renderDistance: Int) -> HorizonLaunchResult,
    onImportBundle: (suspend (Uri) -> PreparedBundleImportResult)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { ProfileRepository(context) }
    val settings = remember(context) { LaunchSettingsStore(context) }
    val layoutStore = remember(context, profile.id) { ControlLayoutStore(context, profile.id) }
    val scope = rememberCoroutineScope()

    var destination by rememberSaveable { mutableStateOf(LauncherDestination.HOME) }
    var readiness by remember(profile.id) { mutableStateOf(repository.assessReadiness(profile)) }
    var distance by rememberSaveable(profile.id) {
        mutableIntStateOf(settings.renderDistance(profile.id))
    }
    var ignoreNotch by rememberSaveable(profile.id) {
        mutableStateOf(settings.ignoreNotch(profile.id))
    }
    var notice by remember { mutableStateOf<LauncherNotice?>(null) }
    var importing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    DisposableEffect(profile.id, refreshKey) {
        readiness = repository.assessReadiness(profile)
        onDispose {}
    }

    val bundlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        notice = LauncherNotice("Importing game files…", isError = false)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    onImportBundle?.invoke(uri) ?: PreparedBundleImporter.import(context, uri, profile)
                }
                importing = false
                notice = LauncherNotice(
                    "Imported ${result.fileCount} files (${result.uncompressedBytes / (1024 * 1024)} MB). Ready to launch.",
                    isError = false,
                )
            } catch (e: Exception) {
                importing = false
                notice = LauncherNotice(e.message ?: "Could not import the prepared bundle.", isError = true)
            }
            readiness = repository.assessReadiness(profile)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(
                                color = if (readiness.readyToLaunch) Color(0xFF43A047) else Color(0xFFE57373),
                            )
                        }
                        Text(
                            text = "Horizon Pocket",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = destination == LauncherDestination.HOME,
                    onClick = { destination = LauncherDestination.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(LauncherDestination.HOME.label) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
                NavigationBarItem(
                    selected = destination == LauncherDestination.CONTROLS,
                    onClick = { destination = LauncherDestination.CONTROLS },
                    icon = { Icon(Icons.Filled.SportsEsports, contentDescription = null) },
                    label = { Text(LauncherDestination.CONTROLS.label) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
                NavigationBarItem(
                    selected = destination == LauncherDestination.SETTINGS,
                    onClick = { destination = LauncherDestination.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(LauncherDestination.SETTINGS.label) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        },
    ) { innerPadding ->
        when (destination) {
            LauncherDestination.HOME -> HomeDestination(
                modifier = Modifier.padding(innerPadding),
                readiness = readiness,
                importing = importing,
                renderDistance = distance,
                notice = notice,
                onClearNotice = { notice = null },
                onPickBundle = {
                    bundlePicker.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                    )
                },
                onLaunch = {
                    when (val result = onLaunch(distance)) {
                        HorizonLaunchResult.Started -> {
                            notice = LauncherNotice("Starting Horizon…", isError = false)
                        }

                        is HorizonLaunchResult.Blocked -> notice = LauncherNotice(result.message, isError = true)
                    }
                    refreshKey += 1
                },
            )

            LauncherDestination.CONTROLS -> ControlsDestination(
                modifier = Modifier.padding(innerPadding),
                layoutStore = layoutStore,
                profileId = profile.id,
                onDone = { destination = LauncherDestination.HOME },
            )

            LauncherDestination.SETTINGS -> SettingsDestination(
                modifier = Modifier.padding(innerPadding),
                renderDistance = distance,
                onDistanceChange = { distance = it },
                onDistanceCommitted = { settings.setRenderDistance(profile.id, distance) },
                ignoreNotch = ignoreNotch,
                onIgnoreNotchChange = {
                    ignoreNotch = it
                    settings.setIgnoreNotch(profile.id, it)
                },
            )
        }
    }
}

@Composable
private fun HomeDestination(
    modifier: Modifier,
    readiness: HorizonReadiness,
    importing: Boolean,
    renderDistance: Int,
    notice: LauncherNotice?,
    onClearNotice: () -> Unit,
    onPickBundle: () -> Unit,
    onLaunch: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        notice?.let {
            NoticeCard(it, onDismiss = onClearNotice)
        }

        HeroLaunchCard(
            readiness = readiness,
            importing = importing,
            renderDistance = renderDistance,
            onLaunch = onLaunch,
            onPickBundle = onPickBundle,
        )

        ReadinessCard(
            readiness = readiness,
            onPickBundle = onPickBundle,
        )

        OwnershipCard()
    }
}

@Composable
private fun HeroLaunchCard(
    readiness: HorizonReadiness,
    importing: Boolean,
    renderDistance: Int,
    onLaunch: () -> Unit,
    onPickBundle: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Minecraft Java 26.3 on Android",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "The Horizon engine runs natively on ARM64 using Vulkan. Your game files stay on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("ARM64") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Vulkan") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                AssistChip(
                    onClick = {},
                    label = { Text("$renderDistance chunks") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }

            if (readiness.readyToLaunch) {
                Button(
                    onClick = onLaunch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Launch Horizon", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                FilledTonalButton(
                    onClick = onPickBundle,
                    enabled = !importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        if (importing) "Importing…" else "Import game files to play",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    readiness: HorizonReadiness,
    onPickBundle: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ready check", style = MaterialTheme.typography.titleMedium)
            ReadinessRow(
                ok = readiness.preparedResources,
                title = "Game resources",
                description = if (readiness.preparedResources) {
                    "Game assets are unpacked and ready to load."
                } else {
                    "Import an extracted game archive (.zip) containing client assets."
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadinessRow(
                ok = readiness.nativeLibraryPresent,
                title = "Native engine (ARM64)",
                description = if (readiness.nativeLibraryPresent) {
                    "libmain.so is bundled with this build."
                } else {
                    "This APK is missing libmain.so for this device architecture."
                },
            )
            if (!readiness.preparedResources) {
                OutlinedButton(
                    onClick = onPickBundle,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Select game archive (.zip)")
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(ok: Boolean, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Canvas(modifier = Modifier.padding(top = 6.dp).size(8.dp)) {
            drawCircle(color = if (ok) Color(0xFF43A047) else Color(0xFFE57373))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoticeCard(notice: LauncherNotice, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = if (notice.isError) colors.errorContainer else colors.secondaryContainer
    val content = if (notice.isError) colors.onErrorContainer else colors.onSecondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    if (notice.isError) "Check needed" else "Updated",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onDismiss, contentPadding = ButtonDefaults.ContentPadding) {
                    Text("Dismiss")
                }
            }
            Text(notice.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OwnershipCard() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("About Horizon Pocket", style = MaterialTheme.typography.titleMedium)
            Text(
                "This independent launcher keeps your imported game files and worlds private to this device. It does not include game files or account sign-in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlsDestination(
    modifier: Modifier,
    layoutStore: ControlLayoutStore,
    profileId: String,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var resetNotice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Arrange your touch controls", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Customize on-screen controls, adjust button dimensions, and map actions matching Mojolauncher / PojavLauncher.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Custom Controls Editor", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Opens full-screen landscape layout editor on a clean black canvas to customize, move, or add buttons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = {
                        val intent = Intent(context, ControlEditorActivity::class.java).apply {
                            putExtra(HorizonLauncher.EXTRA_PROFILE_ID, profileId)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Control Editor", style = MaterialTheme.typography.titleSmall)
                }

                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Controls to Default Layout")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In-Game Controls Overview", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• D-Pad: Forward (▲), Back (▼), Left (◀), Right (▶), Sneak (◇)\n" +
                    "• Actions: Attack (PRI), Use (SEC), Jump (⬛), Inventory (INV)\n" +
                    "• Top Bar: DEBUG, CHAT, KEYBOARD, TAB, Virtual MOUSE\n" +
                    "• Gear Icon: Opens Quick Menu in-game (Force close, Log output, Custom keycode, Quick settings, Custom controls)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Controls?") },
            text = { Text("This will restore the standard Mojolauncher button layout (D-pad, jump, and top-bar controls).") },
            confirmButton = {
                TextButton(onClick = {
                    layoutStore.save(defaultControlLayout())
                    showResetDialog = false
                    resetNotice = "Controls reset to default Mojolauncher map."
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (resetNotice != null) {
        AlertDialog(
            onDismissRequest = { resetNotice = null },
            title = { Text("Controls Updated") },
            text = { Text(resetNotice.orEmpty()) },
            confirmButton = { TextButton(onClick = { resetNotice = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun SettingsDestination(
    modifier: Modifier,
    renderDistance: Int,
    onDistanceChange: (Int) -> Unit,
    onDistanceCommitted: () -> Unit,
    ignoreNotch: Boolean = false,
    onIgnoreNotchChange: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Graphics", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Adjust the chunk render distance. Higher values require more memory and GPU power.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Render distance",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "$renderDistance chunks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = renderDistance.toFloat(),
                    onValueChange = { onDistanceChange(it.toInt()) },
                    onValueChangeFinished = onDistanceCommitted,
                    valueRange = 2f..32f,
                    steps = 14,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ignore notch", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Render the whole game across the entire display cutout/notch area edge-to-edge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ignoreNotch,
                        onCheckedChange = onIgnoreNotchChange,
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Engine Info", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Horizon is a clean-room Rust client for Minecraft Java Edition. This mobile wrapper connects via loopback socket for controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Target version: 26.3 • Protocol: 774",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
