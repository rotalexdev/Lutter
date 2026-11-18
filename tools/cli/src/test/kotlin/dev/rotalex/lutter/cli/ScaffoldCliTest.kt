package dev.rotalex.lutter.cli

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `forge scaffold`, through [execute] like every other command test here.
 *
 * What is asserted is the tree on disk and the exit code, not the generator's internals: §32
 * Phase 14's claim is that a CLI produces a project, and a test that read
 * `ScaffoldGenerator` directly would still pass with the command unwired.
 *
 * The flags' defaults are asserted too, because a default is a decision this command makes on
 * the caller's behalf: `--package` invents `com.example.<slug>` only so that a first scaffold
 * does not need a company domain, and the one-line report names the package it chose so the
 * invention is visible rather than discoverable in a file.
 */
class ScaffoldCliTest {

    @TempDir
    lateinit var directory: File

    /** One invocation: the code a shell would see, and both streams. */
    private fun invoke(vararg args: String): Invocation {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = execute(args.toList(), out, err)
        return Invocation(exit, out, err)
    }

    private class Invocation(val exit: Int, val out: StringBuilder, val err: StringBuilder)

    private fun forge(vararg args: String): Int = execute(args.toList(), StringBuilder(), StringBuilder())

    private fun output(): File = File(directory, "out")

    private fun relativePaths(root: File): List<String> = root.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .sorted()
        .toList()

    // -------------------------------------------------------------------------------------
    // The project
    // -------------------------------------------------------------------------------------

    @Test
    fun `scaffold writes a project and reports one line`() {
        val invocation = invoke("scaffold", "Sample App", "--out", output().path)

        assertEquals(ExitCode.Ok, invocation.exit)
        assertEquals("", invocation.err.toString())

        val report = invocation.out.toString()
        assertEquals(1, report.lines().count { it.isNotBlank() }, "one line, got: $report")
        assertTrue(report.contains("forge scaffold: wrote"), report)

        val paths = relativePaths(output())
        listOf(
            "settings.gradle.kts",
            "gradle/libs.versions.toml",
            "shared/build.gradle.kts",
            "androidApp/build.gradle.kts",
            "desktopApp/build.gradle.kts",
        ).forEach { assertTrue(it in paths, "missing '$it' in $paths") }
    }

    /** `--package` reaches the files that carry a package, and the default does too. */
    @Test
    fun `the package is the flag's, or a visible placeholder`() {
        assertEquals(ExitCode.Ok, forge("scaffold", "Sample App", "--package", "org.acme.app", "--out", output().path))
        val manifest = File(output(), "androidApp/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("org.acme.app"), manifest)

        val defaulted = File(directory, "default-out")
        assertEquals(ExitCode.Ok, forge("scaffold", "Sample App", "--out", defaulted.path))
        val defaultedManifest = File(defaulted, "androidApp/src/main/AndroidManifest.xml").readText()
        assertTrue(defaultedManifest.contains("com.example.sampleapp"), defaultedManifest)
    }

    /** §32 P14's scope: only the two runners this engine can build. */
    @Test
    fun `platforms decide which app modules exist`() {
        assertEquals(
            ExitCode.Ok,
            forge("scaffold", "Desktop Only", "--platforms", "desktop", "--out", output().path),
        )

        val paths = relativePaths(output())
        assertFalse(paths.any { it.startsWith("androidApp/") }, "got $paths")
        assertTrue("desktopApp/build.gradle.kts" in paths, "got $paths")
    }

    // -------------------------------------------------------------------------------------
    // The document
    // -------------------------------------------------------------------------------------

    /** The two halves of generation meet: `--document` puts the UI in `shared/`. */
    @Test
    fun `a document's codegen lands in the shared module`() {
        val document = write(VALID, "valid.json")

        assertEquals(
            ExitCode.Ok,
            forge("scaffold", "Sample App", "--out", output().path, "--document", document.path),
        )

        val shared = relativePaths(File(output(), "shared/src/commonMain/kotlin"))
        assertTrue(shared.isNotEmpty(), "no generated Kotlin in the shared module")
    }

    /** Absent a document there is no UI, and a scaffold that emitted one anyway would be codegen. */
    @Test
    fun `no document means no generated ui`() {
        assertEquals(ExitCode.Ok, forge("scaffold", "Bare", "--out", output().path))

        assertFalse(File(output(), "shared/src/commonMain/kotlin").exists())
    }

    /**
     * A refused document writes nothing at all.
     *
     * Half a project is worse than none: it looks like a project, and the first `gradle build`
     * a user runs fails on code they did not write.
     */
    @Test
    fun `a rejected document leaves no tree behind`() {
        val document = write(UNKNOWN_COMPONENT, "unknown.json")

        assertEquals(
            ExitCode.Rejected,
            forge("scaffold", "Broken", "--out", output().path, "--document", document.path),
        )
        assertFalse(output().exists(), "a rejected document must not create the output directory")
    }

    // -------------------------------------------------------------------------------------
    // Refusals
    // -------------------------------------------------------------------------------------

    @Test
    fun `an unknown platform is a usage failure naming the supported ones`() {
        val invocation = invoke("scaffold", "Sample App", "--platforms", "ios", "--out", output().path)

        assertEquals(ExitCode.Unusable, invocation.exit)
        val message = invocation.err.toString()
        assertTrue(message.contains("ios"), message)
        assertTrue(message.contains("android"), message)
        assertFalse(output().exists())
    }

    @Test
    fun `a missing app name is a usage failure`() {
        val invocation = invoke("scaffold", "--out", output().path)

        assertEquals(ExitCode.Unusable, invocation.exit)
        assertTrue(invocation.err.toString().contains("scaffold <app-name>"), invocation.err.toString())
    }

    /** The app name reaches `rootProject.name`, which Gradle will not accept with a colon in it. */
    @Test
    fun `an app name that cannot name a project is refused`() {
        assertEquals(ExitCode.Unusable, forge("scaffold", "Sample: App", "--out", output().path))
        assertFalse(output().exists())
    }

    private fun write(text: String, name: String): File = File(directory, name).apply { writeText(text) }

    private companion object {

        /** The smallest clean document: two `m3.Text` children in a `core.Column`. */
        val VALID: String = """
            {
              "format": "forge.ui-document",
              "formatVersion": 1,
              "schemaVersion": 1,
              "payload": {
                "app": {"packageName": "com.example.cli", "startPage": "p_home"},
                "components": {},
                "meta": {"name": "ScaffoldValid"},
                "nodes": {
                  "n_a": {
                    "id": "n_a",
                    "type": "m3.Text",
                    "props": {"text": {"type": "const", "value": {"type": "str", "v": "Hello"}}}
                  },
                  "n_b": {
                    "id": "n_b",
                    "type": "m3.Text",
                    "props": {"text": {"type": "const", "value": {"type": "str", "v": "World"}}}
                  },
                  "n_root": {
                    "id": "n_root",
                    "type": "core.Column",
                    "props": {"spacing": {"type": "const", "value": {"type": "dp", "v": 8}}},
                    "slots": {"children": ["n_a", "n_b"]}
                  }
                },
                "pages": {
                  "p_home": {"id": "p_home", "name": "Home", "route": "home", "root": "n_root"}
                }
              }
            }
        """.trimIndent()

        /** [VALID] with a component nobody registered, which `SchemaPass` rejects. */
        val UNKNOWN_COMPONENT: String = VALID.replace("core.Column", "acme.Nope")
    }
}
