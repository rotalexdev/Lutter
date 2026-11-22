package dev.rotalex.lutter.fixture.plugin

import dev.rotalex.lutter.model.plugin.PluginApiVersion
import dev.rotalex.lutter.model.plugin.PluginBuild
import dev.rotalex.lutter.model.plugin.PluginPayload
import dev.rotalex.lutter.plugin.container.PluginContainerReader
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the packaging plugin wrote, read back with the real reader (phase 16 S9, and the guard for
 * the format's duplication).
 *
 * The packager lives in `plugins/lutter-plugin`, an included build that cannot depend on
 * `:tools:plugin-container`, so it writes the container format a second time. That duplication is
 * only safe if something compares the two, and this is that something: the file is produced by
 * this module's own `lutterPluginContainer` task, then read by `PluginContainerReader` exactly as a
 * host would read it. A format change on one side that the other does not understand fails here.
 *
 * The second half is the identity claim. The build script cannot reference `FixturePlugin`'s
 * constants — scripts compile before the module — so the id, version, API version and entry point
 * it writes are literals, and this test holds those literals against the descriptor and the
 * constant they are supposed to mirror.
 */
class PluginContainerRoundTripTest {

    private val containerFile: File =
        File(assertNotNull(System.getProperty("lutter.plugin.container"), "the build must pass the container path"))

    @Test
    fun `the packaged container is the one this module's build produced`() {
        assertTrue(containerFile.isFile, "the packaging task must have produced ${containerFile.path}")
    }

    /** A desktop host loads a jar, so the container must declare it and carry it. */
    @Test
    fun `the manifest declares the desktop-only build the packaging task wrote`() {
        val container = PluginContainerReader.read(containerFile)

        assertEquals(PluginBuild.DesktopOnly, container.manifest.build)
        assertTrue(container.has(PluginPayload.JvmJar), "a desktop-only container without its jar")
        assertTrue(!container.has(PluginPayload.AndroidDex), "this packager must never claim a dex")
    }

    /** Literals in the build script, values in the descriptor: the test is where they meet. */
    @Test
    fun `the manifest identity is the descriptor it was declared from`() {
        val manifest = PluginContainerReader.read(containerFile).manifest

        assertEquals(FixturePlugin.descriptor.id, manifest.id)
        assertEquals(FixturePlugin.descriptor.version, manifest.version)
        assertEquals(FixturePlugin.descriptor.apiVersion, manifest.apiVersion)
        assertEquals(PluginApiVersion.Supported, manifest.apiVersion)
        assertEquals(FixturePlugin.descriptor.dependsOn, manifest.dependsOn)
    }

    /** The container is only useful if the payload inside it is the code the entry point names. */
    @Test
    fun `the payload jar contains the class the entry point names`() {
        val names = PluginContainerReader.read(containerFile).jvmPayloads
        assertEquals(1, names.size, "a desktop-only container carries exactly one jar")

        val entry = "dev/rotalex/lutter/fixture/plugin/FixturePlugin.class"
        ZipFile(containerFile).use { zip ->
            val jarEntry = zip.getEntry("classes/jvm/${names.single()}")
            assertNotNull(jarEntry, "the manifest names ${names.single()} but the container does not carry it")
            val jarBytes = zip.getInputStream(jarEntry).readBytes()
            // The payload is a jar of its own, so it is read as one rather than by looking for
            // bytes: a container that stored the wrong file would pass a byte search and fail here.
            val payload = File.createTempFile("lutter-payload", ".jar").apply { writeBytes(jarBytes) }
            try {
                ZipFile(payload).use { inner ->
                    assertNotNull(inner.getEntry(entry), "the payload jar does not carry $entry")
                }
            } finally {
                payload.delete()
            }
        }
    }

    @Test
    fun `the manifest names the constant a host will load`() {
        val manifest = PluginContainerReader.read(containerFile).manifest

        assertEquals(FixturePlugin.SCHEMA_ENTRY_POINT, manifest.entryPoints.schema)
    }
}
