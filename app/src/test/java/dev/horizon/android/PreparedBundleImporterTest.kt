package dev.horizon.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedBundleImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports a prepared root that has the manifest Horizon checks for`() {
        val profile = profile("ready")

        val result = PreparedBundleImporter.importStream(
            ByteArrayInputStream(zipOf("horizon-resources.json" to "{}", "facts.json" to "{}")),
            profile,
        )

        assertEquals(2, result.fileCount)
        assertTrue(profile.hasPreparedResources())
        assertTrue(File(profile.resourcesDir, "facts.json").isFile)
    }

    @Test
    fun `rejects a zip slip entry before it reaches profile storage`() {
        val profile = profile("slip")
        val escaped = File(profile.directory.parentFile, "escaped.txt")

        expectImportFailure {
            PreparedBundleImporter.importStream(
                ByteArrayInputStream(zipOf("../escaped.txt" to "no", "horizon-resources.json" to "{}")),
                profile,
            )
        }

        assertFalse(escaped.exists())
        assertFalse(profile.hasPreparedResources())
    }

    @Test
    fun `does not replace a working bundle when manifest is absent`() {
        val profile = profile("rollback")
        profile.ensureDirectories()
        assertTrue(profile.resourcesDir.mkdirs())
        File(profile.resourcesDir, HorizonProfile.RESOURCE_MANIFEST).writeText("old-bundle")

        expectImportFailure {
            PreparedBundleImporter.importStream(ByteArrayInputStream(zipOf("facts.json" to "{}")), profile)
        }

        assertEquals("old-bundle", File(profile.resourcesDir, HorizonProfile.RESOURCE_MANIFEST).readText())
    }

    @Test
    fun `rejects two archive entries that normalize to one destination`() {
        val profile = profile("duplicate")
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("folder/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("folder"))
                zip.write("not-a-directory".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(HorizonProfile.RESOURCE_MANIFEST))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

        expectImportFailure {
            PreparedBundleImporter.importStream(ByteArrayInputStream(bytes), profile)
        }

        assertFalse(profile.hasPreparedResources())
    }

    @Test
    fun `cancellation leaves the current bundle in place`() {
        val profile = profile("cancelled")
        profile.ensureDirectories()
        assertTrue(profile.resourcesDir.mkdirs())
        File(profile.resourcesDir, HorizonProfile.RESOURCE_MANIFEST).writeText("still-here")

        try {
            PreparedBundleImporter.importStream(
                ByteArrayInputStream(zipOf(HorizonProfile.RESOURCE_MANIFEST to "new")),
                profile,
            ) { throw CancellationException("screen closed") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the production suspend entry point supplies this checkpoint.
        }

        assertEquals("still-here", File(profile.resourcesDir, HorizonProfile.RESOURCE_MANIFEST).readText())
    }

    private fun profile(id: String): HorizonProfile {
        val directory = File(temporaryFolder.root, "profiles/$id")
        return HorizonProfile(
            id = id,
            directory = directory,
            gameDir = File(directory, "game"),
            resourcesDir = File(directory, "resources"),
            homeDir = File(directory, "home"),
            controlEndpoint = File(directory, "control.json"),
        )
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun expectImportFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PreparedBundleImportException) {
            return
        }
        throw AssertionError("Expected a PreparedBundleImportException")
    }
}
