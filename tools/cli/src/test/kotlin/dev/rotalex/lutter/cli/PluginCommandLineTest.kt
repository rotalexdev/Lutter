package dev.rotalex.lutter.cli

import dev.rotalex.lutter.cli.plugin.CliFixturePlugin
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.plugin.PluginBuild
import dev.rotalex.lutter.model.plugin.PluginEntryPoints
import dev.rotalex.lutter.model.plugin.PluginManifest
import dev.rotalex.lutter.plugin.container.PluginContainerWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `--plugin`, end to end: a real container on disk, read by the host, installed into the schema
 * the document is then analysed against (phase 16, P7).
 *
 * ### What is real and what is not
 *
 * The container, the jar inside it, the manifest and the extraction are all real: [container]
 * writes with the same `PluginContainerWriter` the packaging plugin's own round-trip test uses, and
 * the CLI reads it back with `PluginContainerReader` plus a `URLClassLoader`. What is *not* real is
 * where the class bytes live — [CliFixturePlugin] is compiled into this module's test classpath, so
 * the parent-first classloader would find it even if the jar were empty. That is stated rather than
 * hidden: the load path asserted here is the manifest-to-`SchemaContribution` one, and P6's
 * round-trip test is what pins the jar's contents.
 *
 * ### Why the assertions are on the exit code and on stdout
 *
 * The same reason [ForgeCliTest] gives: a script has two instruments, and one of them is the
 * summary line. A plugin that loaded silently would make `--plugin` unobservable, so the positive
 * case asserts the identity line, in the shape a user reads it.
 */
class PluginCommandLineTest {

    @TempDir
    lateinit var directory: File

    // -------------------------------------------------------------------------------------
    // The load
    // -------------------------------------------------------------------------------------

    /**
     * The whole point: a document naming a plugin is valid exactly when that plugin is installed.
     *
     * Without the flag the same document is `plugin.missing`, which is the negative half of the
     * same test and the reason the two are next to each other.
     */
    @Test
    fun `a loaded plugin registers its component and reports its identity`() {
        val document = document("1.0.0")
        val plugin = container()

        val invocation = invoke("check", document.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Ok, invocation.code)
        assertEquals("", invocation.err.toString(), "the document names an installed plugin")
        assertTrue(
            invocation.out.contains("forge check: loaded plugin forge.clitest 1.0.0 (api 1, desktop-only)"),
            "the loaded identity is on stdout: ${invocation.out}",
        )
    }

    /** The negative half: the component is unknown *and* the requirement is unmet. */
    @Test
    fun `a document naming an uninstalled plugin is rejected`() {
        val document = document("1.0.0")

        val invocation = invoke("check", document.path)

        assertEquals(ExitCode.Rejected, invocation.code)
        assertTrue(invocation.err.contains("plugin.missing"), invocation.err.toString())
    }

    /** ADR-018 compares versions by exact string equality, and a mismatch is an error, not a warning. */
    @Test
    fun `a version the container does not carry is a mismatch`() {
        val document = document("0.9.0")
        val plugin = container()

        val invocation = invoke("check", document.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Rejected, invocation.code)
        assertTrue(invocation.err.contains("plugin.version_mismatch"), invocation.err.toString())
    }

    /** Generation reads the same schema, so the flag has to exist there too (§36.3's two stages). */
    @Test
    fun `generate takes the plugin flag and installs it`() {
        val document = document("1.0.0")
        val plugin = container()
        val out = File(directory, "generated")

        val invocation = invoke("generate", document.path, "--plugin", plugin.path, "--out", out.path)

        assertEquals(ExitCode.Ok, invocation.code)
        assertTrue(invocation.out.contains("loaded plugin forge.clitest 1.0.0"), invocation.out.toString())
    }

    // -------------------------------------------------------------------------------------
    // The refusals, in the order the host asks
    // -------------------------------------------------------------------------------------

    /**
     * A dex-only container is *valid* and still not loadable here. The message names the build the
     * manifest declares, because "no jar" alone reads like a corrupt file.
     */
    @Test
    fun `an android-only container is refused by a jar host, naming the build`() {
        val document = document("1.0.0")
        val plugin = container(build = PluginBuild.AndroidOnly)

        val invocation = invoke("check", document.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains("android-only"), invocation.err.toString())
    }

    /** Neither payload kind named: nothing to load, and the refusal says so rather than crashing. */
    @Test
    fun `a manifest with no schema entry point is refused`() {
        val document = document("1.0.0")
        val plugin = container(entryPoints = PluginEntryPoints(runtime = "com.forge.Runtime#install"))

        val invocation = invoke("check", document.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains("names no schema entry point"), invocation.err.toString())
    }

    /** A name the jar does not contain is the caller's mistake, and the message names both. */
    @Test
    fun `an entry point the jar does not contain is refused`() {
        val document = document("1.0.0")
        val plugin = container(entryPoints = PluginEntryPoints(schema = "com.forge.Absent"))

        val invocation = invoke("check", document.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains("com.forge.Absent"), invocation.err.toString())
    }

    /** Two payloads claiming one identity: the builder refuses it, and the CLI reports the reason. */
    @Test
    fun `the same plugin twice is refused by the schema builder`() {
        val document = document("1.0.0")
        val plugin = container()

        val invocation = invoke("check", document.path, "--plugin", plugin.path, "--plugin", plugin.path)

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains("installed twice"), invocation.err.toString())
    }

    /** The parser's existing rule, applied to the new arity: a flag's value cannot be missing. */
    @Test
    fun `the plugin flag needs a value`() {
        val document = document("1.0.0")

        val invocation = invoke("check", document.path, "--plugin")

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains("needs a value"), invocation.err.toString())
    }

    /** A file that is not a container at all: the reader's own refusal, forwarded with the path. */
    @Test
    fun `a file that is not a container is refused with its path`() {
        val document = document("1.0.0")
        val notAContainer = File(directory, "not-a-plugin.lutterplugin").apply { writeText("not a zip") }

        val invocation = invoke("check", document.path, "--plugin", notAContainer.path)

        assertEquals(ExitCode.Unusable, invocation.code)
        assertTrue(invocation.err.contains(notAContainer.path), invocation.err.toString())
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private class Invocation {
        val out: StringBuilder = StringBuilder()
        val err: StringBuilder = StringBuilder()
        var code: Int = ExitCode.Ok
    }

    /** Runs one invocation the way a shell does, capturing both streams and the exit code. */
    private fun invoke(vararg args: String): Invocation =
        Invocation().also { it.code = execute(args.toList(), it.out, it.err) }

    /** [version] is what the *document* was authored against; the container carries `1.0.0`. */
    private fun document(version: String): File = File(directory, "plugin-document.json").apply {
        writeText(
            """
            {
              "format": "forge.ui-document",
              "formatVersion": 1,
              "schemaVersion": 1,
              "payload": {
                "app": {"packageName": "com.example.cli", "startPage": "p_home"},
                "components": {},
                "meta": {
                  "name": "CliPluginDocument",
                  "plugins": [{"id": "forge.clitest", "version": "$version"}]
                },
                "nodes": {
                  "n_root": {
                    "id": "n_root",
                    "type": "cli.notice.Notice",
                    "props": {"text": {"type": "const", "value": {"type": "str", "v": "Loaded"}}}
                  }
                },
                "pages": {
                  "p_home": {
                    "id": "p_home",
                    "name": "Home",
                    "route": "home",
                    "root": "n_root",
                    "state": [
                      {"id": "s_count", "name": "count", "type": {"type": "i32"}, "initial": {"type": "i32", "v": 0}}
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
        )
    }

    /**
     * A container holding [build]'s payloads, the fixture's identity, and [entryPoints].
     *
     * The dex is four arbitrary bytes: nothing in this repository produces a real one (P6 says so),
     * and the reader's rule is about presence, not about the format of a payload it never opens.
     */
    private fun container(
        build: PluginBuild = PluginBuild.DesktopOnly,
        entryPoints: PluginEntryPoints = PluginEntryPoints(schema = CliFixturePlugin.SCHEMA_ENTRY_POINT),
    ): File {
        val manifest = PluginManifest(
            id = PluginId(CliFixturePlugin.PLUGIN_ID),
            version = CliFixturePlugin.PLUGIN_VERSION,
            apiVersion = 1,
            build = build,
            entryPoints = entryPoints,
        )
        val target = File(directory, "clitest-${build.name.lowercase()}-${entryPoints.hashCode()}.lutterplugin")
        PluginContainerWriter.write(
            target = target,
            manifest = manifest,
            jvmPayloads = if (build == PluginBuild.AndroidOnly) emptyMap() else mapOf("cliplugin.jar" to fixtureJar()),
            androidPayloads = if (build == PluginBuild.DesktopOnly) emptyMap() else mapOf("cliplugin.dex" to byteArrayOf(0x64, 0x65, 0x78, 0x0a)),
        )
        return target
    }

    /**
     * The fixture's class file, as a one-entry jar.
     *
     * Read from the compiled test output rather than written as bytes here: a test that carried a
     * hand-made class file would break the day the class changes, and in a way that would look like
     * a loader bug.
     */
    private fun fixtureJar(): ByteArray {
        val entry = "dev/rotalex/lutter/cli/plugin/CliFixturePlugin.class"
        val bytes = checkNotNull(CliFixturePlugin::class.java.classLoader.getResourceAsStream(entry)) {
            "$entry is not in the test classpath; the fixture did not compile"
        }.use { it.readBytes() }
        val buffer = ByteArrayOutputStream()
        JarOutputStream(buffer).use { jar ->
            jar.putNextEntry(JarEntry(entry))
            jar.write(bytes)
            jar.closeEntry()
        }
        return buffer.toByteArray()
    }
}
