package dev.horizon.android

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The outcome deliberately does not claim that the engine has validated every asset. */
data class PreparedBundleImportResult(
    val fileCount: Int,
    val uncompressedBytes: Long,
)

class PreparedBundleImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Imports a player-supplied, pre-prepared Horizon resource tree from the Storage
 * Access Framework. Extraction happens in an app-private staging directory; an
 * existing, working resource tree is swapped only after the manifest check passes.
 */
object PreparedBundleImporter {
    private const val BUFFER_BYTES = 32 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_UNCOMPRESSED_BYTES = 6L * 1024L * 1024L * 1024L
    private const val MAX_SINGLE_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L
    private const val REQUIRED_FREE_BYTES = 64L * 1024L * 1024L

    /**
     * Import on Dispatchers.IO and stop cooperatively if the calling screen goes
     * away. Cancellation never replaces the current resource tree.
     */
    suspend fun import(context: Context, source: Uri, profile: HorizonProfile): PreparedBundleImportResult =
        withContext(Dispatchers.IO) {
            val importContext = currentCoroutineContext()
            val input = context.contentResolver.openInputStream(source)
                ?: throw PreparedBundleImportException("Android could not open that document.")
            input.use { stream ->
                importStream(stream, profile) { importContext.ensureActive() }
            }
        }

    /**
     * Kept internal so local tests can exercise ZIP safety without an Android
     * document provider. The checkpoint must throw when an import is cancelled.
     */
    internal fun importStream(
        input: InputStream,
        profile: HorizonProfile,
        checkpoint: () -> Unit = {},
    ): PreparedBundleImportResult {
        profile.ensureDirectories()
        val parent = profile.directory.canonicalFile
        val staging = File(parent, ".resources-import-${UUID.randomUUID()}")
        require(staging.parentFile?.canonicalFile == parent) { "Unsafe import staging path." }
        check(staging.mkdir()) { "Could not create import staging directory." }

        try {
            checkpoint()
            val extracted = extract(ZipInputStream(BufferedInputStream(input)), staging, checkpoint)
            val manifest = File(staging, HorizonProfile.RESOURCE_MANIFEST)
            if (!manifest.isFile) {
                throw PreparedBundleImportException(
                    "That archive is not a prepared Horizon resource bundle: " +
                        "${HorizonProfile.RESOURCE_MANIFEST} must be at the archive root.",
                )
            }
            replaceResources(profile, staging, checkpoint)
            return extracted
        } catch (error: PreparedBundleImportException) {
            throw error
        } catch (error: IOException) {
            throw PreparedBundleImportException("Could not import the prepared bundle: ${error.message}", error)
        } finally {
            // Once moved, staging no longer exists. On every failure it is safely a
            // randomly-named child of this profile's private directory.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun extract(
        input: ZipInputStream,
        staging: File,
        checkpoint: () -> Unit,
    ): PreparedBundleImportResult {
        input.use { zip ->
            val stagingPath = staging.canonicalFile
            var entries = 0
            var files = 0
            var totalBytes = 0L
            val seenOutputPaths = mutableSetOf<String>()
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                checkpoint()
                val current = checkNotNull(entry)
                if (++entries > MAX_ENTRIES) {
                    throw PreparedBundleImportException("The archive has too many files.")
                }
                val output = checkedOutputFile(stagingPath, current.name)
                if (!seenOutputPaths.add(output.path)) {
                    throw PreparedBundleImportException("The archive contains a duplicate path.")
                }
                if (current.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) {
                        "Could not create an archive directory."
                    }
                    zip.closeEntry()
                    continue
                }
                files += 1
                val parent = output.parentFile ?: throw PreparedBundleImportException("Invalid archive entry.")
                if (!parent.mkdirs() && !parent.isDirectory) {
                    throw PreparedBundleImportException("Could not create an archive directory.")
                }
                var thisEntryBytes = 0L
                FileOutputStream(output).use { destination ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        checkpoint()
                        thisEntryBytes += read
                        totalBytes += read
                        if (thisEntryBytes > MAX_SINGLE_ENTRY_BYTES || totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw PreparedBundleImportException("The archive expands beyond the allowed import size.")
                        }
                        if (staging.usableSpace < REQUIRED_FREE_BYTES + read) {
                            throw PreparedBundleImportException("Not enough free storage to finish importing this bundle.")
                        }
                        destination.write(buffer, 0, read)
                    }
                    destination.fd.sync()
                }
                zip.closeEntry()
            }
            if (files == 0) throw PreparedBundleImportException("The archive is empty.")
            return PreparedBundleImportResult(files, totalBytes)
        }
    }

    private fun checkedOutputFile(staging: File, entryName: String): File {
        if (
            entryName.isBlank() ||
                entryName.startsWith('/') ||
                entryName.startsWith('\\') ||
                entryName.contains('\\')
        ) {
            throw PreparedBundleImportException("The archive contains an unsafe path.")
        }
        val normalized = entryName.removeSuffix("/")
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw PreparedBundleImportException("The archive contains an unsafe path.")
        }
        val output = File(staging, normalized).canonicalFile
        val prefix = staging.path + File.separator
        if (!output.path.startsWith(prefix)) {
            throw PreparedBundleImportException("The archive contains a path outside the import folder.")
        }
        return output
    }

    private fun replaceResources(profile: HorizonProfile, staging: File, checkpoint: () -> Unit) {
        val destination = profile.resourcesDir
        val parent = profile.directory.canonicalFile
        require(destination.parentFile?.canonicalFile == parent) { "Unsafe resource destination." }
        val backup = File(parent, ".resources-backup-${UUID.randomUUID()}")
        var backedUp = false
        try {
            // Do not checkpoint between these moves: cancellation at that point
            // must not strand the previous resources under a backup name.
            checkpoint()
            if (destination.exists()) {
                move(destination, backup)
                backedUp = true
            }
            move(staging, destination)
            if (backedUp && backup.exists()) backup.deleteRecursively()
        } catch (error: Exception) {
            if (!destination.exists() && backedUp && backup.exists()) {
                runCatching { move(backup, destination) }
            }
            throw error
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
