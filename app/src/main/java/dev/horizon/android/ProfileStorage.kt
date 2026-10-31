package dev.horizon.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class HorizonProfile(
    val id: String,
    val directory: File,
    val gameDir: File,
    val resourcesDir: File,
    val homeDir: File,
    val controlEndpoint: File,
) {
    fun hasPreparedResources(): Boolean =
        File(resourcesDir, RESOURCE_MANIFEST).isFile

    fun ensureDirectories() {
        check(directory.mkdirs() || directory.isDirectory) {
            "Could not create Horizon profile directory."
        }
        check(gameDir.mkdirs() || gameDir.isDirectory) {
            "Could not create Horizon game directory."
        }
        check(homeDir.mkdirs() || homeDir.isDirectory) {
            "Could not create Horizon home directory."
        }
        val controlParent = controlEndpoint.parentFile
        if (controlParent != null && !controlParent.isDirectory) {
            check(controlParent.mkdirs() || controlParent.isDirectory) {
                "Could not create Horizon control directory."
            }
        }
    }

    companion object {
        const val RESOURCE_MANIFEST = "horizon-resources.json"
    }
}

object HorizonPaths {
    const val DEFAULT_PROFILE_ID = "default"
    private const val ROOT_NAME = "horizon"
    private val validProfile = Regex("[A-Za-z0-9_-]{1,40}")

    fun forProfile(context: Context, profileId: String = DEFAULT_PROFILE_ID): HorizonProfile {
        require(validProfile.matches(profileId)) { "Invalid Horizon profile id." }
        val root = File(context.filesDir, ROOT_NAME)
        val profile = File(File(root, "profiles"), profileId)
        return HorizonProfile(
            id = profileId,
            directory = profile,
            gameDir = File(profile, "game"),
            resourcesDir = File(profile, "resources"),
            homeDir = File(profile, "home"),
            controlEndpoint = File(profile, "control.json"),
        )
    }

    fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    fun activeLaunchFile(context: Context): File = File(root(context), "active-launch.json")

    fun runtimeStatusFile(context: Context): File = File(root(context), "runtime-status.json")
}

/**
 * Strict schema matching the Rust engine's ActiveLaunchConfig struct in libmain.so.
 * DO NOT add extra fields here because serde in Rust uses deny_unknown_fields.
 */
data class ActiveLaunchConfig(
    val gameDir: String,
    val resourcesDir: String,
    val homeDir: String,
    val controlEndpoint: String,
    val renderDistance: Int,
)

/**
 * A file hand-off is intentional: the GameActivity runs in a dedicated process,
 * so SharedPreferences would not be a reliable configuration channel.
 */
object ActiveLaunchStore {
    private const val MIN_RENDER_DISTANCE = 2
    private const val MAX_RENDER_DISTANCE = 128

    fun write(
        context: Context,
        profile: HorizonProfile,
        renderDistance: Int,
    ): ActiveLaunchConfig {
        profile.ensureDirectories()
        val config = ActiveLaunchConfig(
            gameDir = profile.gameDir.absolutePath,
            resourcesDir = profile.resourcesDir.absolutePath,
            homeDir = profile.homeDir.absolutePath,
            controlEndpoint = profile.controlEndpoint.absolutePath,
            renderDistance = renderDistance.coerceIn(MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE),
        )
        val json = JSONObject()
            .put("gameDir", config.gameDir)
            .put("resourcesDir", config.resourcesDir)
            .put("homeDir", config.homeDir)
            .put("controlEndpoint", config.controlEndpoint)
            .put("renderDistance", config.renderDistance)
        AtomicJson.write(HorizonPaths.activeLaunchFile(context), json)
        return config
    }

    fun read(context: Context): ActiveLaunchConfig? {
        val file = HorizonPaths.activeLaunchFile(context)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            ActiveLaunchConfig(
                gameDir = json.requiredAbsolutePath("gameDir"),
                resourcesDir = json.requiredAbsolutePath("resourcesDir"),
                homeDir = json.requiredAbsolutePath("homeDir"),
                controlEndpoint = json.requiredAbsolutePath("controlEndpoint"),
                renderDistance = json.getInt("renderDistance").coerceIn(MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE),
            )
        }.getOrNull()
    }

    private fun JSONObject.requiredAbsolutePath(name: String): String {
        val path = getString(name)
        require(File(path).isAbsolute) { "$name must be an absolute path." }
        return path
    }
}

data class RuntimeStatus(
    val state: String,
    val message: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

object RuntimeStatusStore {
    fun record(context: Context, status: RuntimeStatus) {
        val json = JSONObject()
            .put("state", status.state)
            .put("message", status.message)
            .put("updatedAtMillis", status.updatedAtMillis)
        AtomicJson.write(HorizonPaths.runtimeStatusFile(context), json)
    }

    fun read(context: Context): RuntimeStatus? {
        val file = HorizonPaths.runtimeStatusFile(context)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            RuntimeStatus(
                state = json.getString("state"),
                message = json.optString("message").takeIf { it.isNotEmpty() },
                updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        val file = HorizonPaths.runtimeStatusFile(context)
        if (file.isFile) file.delete()
    }
}

data class HorizonReadiness(
    val preparedResources: Boolean,
    val nativeLibraryPresent: Boolean,
    val bundleFile: File?,
) {
    val readyToLaunch: Boolean get() = preparedResources && nativeLibraryPresent
}

class ProfileRepository(private val context: Context) {
    fun currentProfile(): HorizonProfile {
        val defaultProfile = HorizonPaths.forProfile(context)
        defaultProfile.ensureDirectories()
        return defaultProfile
    }

    fun assessReadiness(profile: HorizonProfile): HorizonReadiness {
        val nativePresent = File(context.applicationInfo.nativeLibraryDir, "libmain.so").isFile
        val defaultBundle = File(HorizonPaths.root(context), HorizonProfile.RESOURCE_MANIFEST)
        val bundlePresent = defaultBundle.isFile && defaultBundle.length() > 0
        val prepared = profile.hasPreparedResources()
        return HorizonReadiness(
            preparedResources = prepared,
            nativeLibraryPresent = nativePresent,
            bundleFile = defaultBundle.takeIf { bundlePresent },
        )
    }
}

class LaunchSettingsStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("horizon.launch_settings", Context.MODE_PRIVATE)

    fun renderDistance(profileId: String, fallback: Int = 12): Int =
        preferences.getInt("$profileId.renderDistance", fallback).coerceIn(2, 64)

    fun setRenderDistance(profileId: String, value: Int) {
        preferences.edit().putInt("$profileId.renderDistance", value.coerceIn(2, 64)).apply()
    }

    fun ignoreNotch(profileId: String): Boolean =
        preferences.getBoolean("$profileId.ignoreNotch", false)

    fun setIgnoreNotch(profileId: String, value: Boolean) {
        preferences.edit().putBoolean("$profileId.ignoreNotch", value).apply()
    }
}

sealed class HorizonLaunchResult {
    data object Started : HorizonLaunchResult()
    data class Blocked(val message: String) : HorizonLaunchResult()
}

object HorizonLauncher {
    const val EXTRA_PROFILE_ID = "dev.horizon.android.extra.PROFILE_ID"
    const val EXTRA_IGNORE_NOTCH = "dev.horizon.android.extra.IGNORE_NOTCH"
    private const val GAME_ACTIVITY = "dev.horizon.android.HorizonGameActivity"

    fun launch(
        context: Context,
        profile: HorizonProfile,
        renderDistance: Int,
    ): HorizonLaunchResult {
        val repository = ProfileRepository(context)
        val readiness = repository.assessReadiness(profile)
        if (!readiness.preparedResources) {
            return HorizonLaunchResult.Blocked(
                "Import game files before launching Horizon.",
            )
        }
        if (!readiness.nativeLibraryPresent) {
            return HorizonLaunchResult.Blocked(
                "This build does not include a compatible game engine for this device.",
            )
        }

        return try {
            val settings = LaunchSettingsStore(context)
            val ignoreNotch = settings.ignoreNotch(profile.id)
            ActiveLaunchStore.write(context, profile, renderDistance)
            RuntimeStatusStore.clear(context)
            val intent = Intent().apply {
                component = ComponentName(context.packageName, GAME_ACTIVITY)
                putExtra(EXTRA_PROFILE_ID, profile.id)
                putExtra(EXTRA_IGNORE_NOTCH, ignoreNotch)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            HorizonLaunchResult.Started
        } catch (error: Exception) {
            RuntimeStatusStore.record(
                context,
                RuntimeStatus(state = "launcher-error", message = error.message ?: "Could not start the game."),
            )
            HorizonLaunchResult.Blocked(error.message ?: "Could not start the game.")
        }
    }
}

private object AtomicJson {
    fun write(target: File, json: JSONObject) {
        val parent = target.parentFile ?: error("JSON target needs a parent directory.")
        check(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.name}." }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toString().toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (target.exists() && !target.delete()) error("Could not replace ${target.name}.")
            check(temporary.renameTo(target)) { "Could not finalize ${target.name}." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
