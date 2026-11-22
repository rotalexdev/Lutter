package dev.rotalex.lutter.plugin.container

import dev.rotalex.lutter.model.plugin.PluginBuild
import dev.rotalex.lutter.model.plugin.PluginContainerLayout
import dev.rotalex.lutter.model.plugin.PluginEntryPoints
import dev.rotalex.lutter.model.plugin.PluginManifest
import dev.rotalex.lutter.model.plugin.PluginManifestProblem
import dev.rotalex.lutter.model.plugin.PluginPayload
import dev.rotalex.lutter.model.ids.PluginId
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The container as a file format (ADR-018): what it round-trips, what it refuses, and that
 * packaging twice produces the same bytes.
 *
 * The negative cases hand-build a ZIP instead of going through the writer, because the writer
 * refuses to produce exactly those files — which is the point of having both.
 */
class PluginContainerTest {

    @TempDir
    lateinit var directory: File

    private val desktop: PluginManifest = PluginManifest(
        id = PluginId("forge.badge"),
        version = "1.0.0",
        apiVersion = 1,
        build = PluginBuild.DesktopOnly,
        entryPoints = PluginEntryPoints(
            schema = "forge.badge.BadgeSchema",
            runtime = "forge.badge.BadgeRuntime#install",
        ),
    )

    private fun container(name: String = "badge.lutterplugin"): File = File(directory, name)

    @Test
    fun `a written container reads back its manifest and its payloads`() {
        val file = container()
        PluginContainerWriter.write(
            file,
            desktop,
            jvmPayloads = mapOf("badge.jar" to byteArrayOf(1, 2, 3)),
        )

        val opened = PluginContainerReader.read(file)

        assertEquals(desktop, opened.manifest)
        assertEquals(listOf("badge.jar"), opened.jvmPayloads)
        assertEquals(emptyList(), opened.androidPayloads)
        assertTrue(opened.has(PluginPayload.JvmJar))
        assertFalse(opened.has(PluginPayload.AndroidDex))
    }

    @Test
    fun `a full build carries both payloads and reads them sorted`() {
        val full = desktop.copy(build = PluginBuild.Full)
        val file = container("full.lutterplugin")
        PluginContainerWriter.write(
            file,
            full,
            jvmPayloads = mapOf("z.jar" to byteArrayOf(1), "a.jar" to byteArrayOf(2)),
            androidPayloads = mapOf("classes.dex" to byteArrayOf(3)),
        )

        val opened = PluginContainerReader.read(file)

        assertEquals(listOf("a.jar", "z.jar"), opened.jvmPayloads)
        assertEquals(listOf("classes.dex"), opened.androidPayloads)
    }

    /**
     * The packager's honesty rule, at the lowest level: a `full` manifest with no dex is refused by
     * the writer, so no file that describes itself wrongly can be produced by this module.
     */
    @Test
    fun `the writer refuses a build its arguments do not back`() {
        val full = desktop.copy(build = PluginBuild.Full)

        val refusal = assertFailsWith<PluginContainerException> {
            PluginContainerWriter.write(container(), full, jvmPayloads = mapOf("x.jar" to byteArrayOf(1)))
        }

        assertEquals(listOf("plugin.payload_missing"), refusal.codes)
    }

    @Test
    fun `the reader refuses a build its entries do not back`() {
        val file = container("lying.lutterplugin")
        zipOf(
            file,
            PluginContainerLayout.ManifestPath to manifestJson(desktop.copy(build = PluginBuild.Full)),
            PluginContainerLayout.JvmPayloadPrefix + "only.jar" to byteArrayOf(1),
        )

        val refusal = assertFailsWith<PluginContainerException> { PluginContainerReader.read(file) }

        assertEquals(listOf("plugin.payload_missing"), refusal.codes)
    }

    @Test
    fun `a file that is not a zip is unreadable`() {
        val file = container("notes.lutterplugin").apply { writeText("not a container") }

        val refusal = assertFailsWith<PluginContainerException> { PluginContainerReader.read(file) }

        assertEquals(listOf(PluginContainerProblemCodes.Unreadable), refusal.codes)
    }

    @Test
    fun `a missing path is unreadable rather than an empty container`() {
        val refusal = assertFailsWith<PluginContainerException> {
            PluginContainerReader.read(File(directory, "absent.lutterplugin"))
        }

        assertEquals(listOf(PluginContainerProblemCodes.Unreadable), refusal.codes)
    }

    @Test
    fun `a container without a manifest is refused by name`() {
        val file = container("bare.lutterplugin")
        zipOf(file, PluginContainerLayout.JvmPayloadPrefix + "x.jar" to byteArrayOf(1))

        val refusal = assertFailsWith<PluginContainerException> { PluginContainerReader.read(file) }

        assertEquals(listOf(PluginContainerProblemCodes.ManifestMissing), refusal.codes)
        assertTrue(refusal.message!!.contains("META-INF/lutter/plugin.json"), refusal.message!!)
    }

    @Test
    fun `a manifest that does not decode is refused by name`() {
        val file = container("broken.lutterplugin")
        zipOf(file, PluginContainerLayout.ManifestPath to "{ not json".encodeToByteArray())

        val refusal = assertFailsWith<PluginContainerException> { PluginContainerReader.read(file) }

        assertEquals(listOf(PluginContainerProblemCodes.ManifestInvalid), refusal.codes)
    }

    /** A newer packager's extra manifest field must not break an older engine. */
    @Test
    fun `an unknown manifest field is ignored`() {
        val file = container("future.lutterplugin")
        val json = manifestJson(desktop).decodeToString()
            .replaceFirst("{", """{"signedBy":"someone",""")
        zipOf(
            file,
            PluginContainerLayout.ManifestPath to json.encodeToByteArray(),
            PluginContainerLayout.JvmPayloadPrefix + "x.jar" to byteArrayOf(1),
        )

        assertEquals(desktop, PluginContainerReader.read(file).manifest)
    }

    @Test
    fun `an api version this engine does not implement is refused`() {
        val file = container("future-api.lutterplugin")
        zipOf(
            file,
            PluginContainerLayout.ManifestPath to manifestJson(desktop.copy(apiVersion = 99)),
            PluginContainerLayout.JvmPayloadPrefix + "x.jar" to byteArrayOf(1),
        )

        val refusal = assertFailsWith<PluginContainerException> { PluginContainerReader.read(file) }

        assertEquals(listOf(PluginManifestProblem.ApiVersionMismatch.code), refusal.codes)
    }

    /** Entries outside the format's paths are not payloads: a container may carry a README. */
    @Test
    fun `an unrelated entry is ignored rather than counted as a payload`() {
        val file = container("documented.lutterplugin")
        zipOf(
            file,
            PluginContainerLayout.ManifestPath to manifestJson(desktop),
            "README.md" to "hello".encodeToByteArray(),
            PluginContainerLayout.JvmPayloadPrefix + "x.jar" to byteArrayOf(1),
        )

        val opened = PluginContainerReader.read(file)

        assertEquals(listOf("x.jar"), opened.jvmPayloads)
    }

    /** §36.2 applied to a second artifact: same inputs, same bytes. */
    @Test
    fun `packaging the same input twice is byte-identical`() {
        val first = container("one.lutterplugin")
        val second = container("two.lutterplugin")
        val payloads = mapOf("b.jar" to byteArrayOf(9, 8), "a.jar" to byteArrayOf(7))

        PluginContainerWriter.write(first, desktop, jvmPayloads = payloads)
        PluginContainerWriter.write(second, desktop, jvmPayloads = payloads)

        assertTrue(first.readBytes().contentEquals(second.readBytes()))
        assertEquals(desktop, PluginContainerReader.read(second).manifest)
    }

    @Test
    fun `a payload name that is not a plain file name is refused`() {
        for (name in listOf("../escape.jar", "dir/x.jar", "")) {
            assertFailsWith<IllegalArgumentException>("expected '$name' to be refused") {
                PluginContainerWriter.write(
                    container("$name.lutterplugin".replace('/', '_')),
                    desktop,
                    jvmPayloads = mapOf(name to byteArrayOf(1)),
                )
            }
        }
    }

    private fun manifestJson(manifest: PluginManifest): ByteArray =
        kotlinx.serialization.json.Json
            .encodeToString(PluginManifest.serializer(), manifest)
            .encodeToByteArray()

    private fun zipOf(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
