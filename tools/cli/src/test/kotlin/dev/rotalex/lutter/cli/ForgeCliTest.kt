package dev.rotalex.lutter.cli

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The exit codes, which are the part of this CLI a script depends on.
 *
 * ### What runs here and why it is not the process
 *
 * `main` cannot be called from a test — it calls `exitProcess`, which ends the JVM the test is
 * running in. So [execute] holds everything and `main` is the one line that exits, and every
 * test here goes through [execute]: what is asserted is the code a user's shell receives, not
 * a parallel reimplementation of the dispatch. The streams are injected rather than captured
 * from the real ones for the same reason — a test that wrote to the build log would report a
 * finding as a failure.
 *
 * ### Why the documents are inline
 *
 * `:integration:generated-compile` holds ten conformance fixtures and `:engine:test-support`
 * holds a seeded generator, and neither is reachable from here: `ModuleGraphRules` has no
 * `ALLOWED_IN_TESTS` line for this module, so a test-scoped project edge to either would fail
 * `verifyModuleGraph`. Borrowing them through the filesystem instead would tie this module's
 * tests to another module's layout, and the two documents below — one valid, one naming a
 * component nobody registered — are the whole of what these tests need to say.
 *
 * ### What these tests cannot say
 *
 * That a warning leaves the exit code at [ExitCode.Ok]. No pass in `:engine:analysis` emits a
 * `Severity.Warning` today, so no document exists that could demonstrate it, and a test built
 * around a hand-made `AnalysisResult` would be asserting on a fixture rather than on the
 * engine. The rule is stated where it is decided — `AnalysisResult.hasErrors` — and read from
 * there rather than restated here.
 */
class ForgeCliTest {

    @TempDir
    lateinit var directory: File

    /** The exit code `forge <args>` would return. */
    private fun forge(vararg args: String): Int = execute(args.toList(), StringBuilder(), StringBuilder())

    /** Both streams as well, for a test that has to read what was said. */
    private fun invoke(vararg args: String): Invocation =
        Invocation().also { execute(args.toList(), it.out, it.err) }

    private class Invocation {
        val out: StringBuilder = StringBuilder()
        val err: StringBuilder = StringBuilder()
    }

    // -------------------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------------------

    /**
     * The defect: a bare `forge` printed the help and exited **0**.
     *
     * A pipeline whose first step was `forge` therefore passed forever, without ever looking at
     * a document — a CI job could be green with the CLI entirely broken. The help goes to
     * stderr too, because a mistake is not a request.
     */
    @Test
    fun `no arguments is a usage failure, not a success`() {
        val invocation = invoke()

        assertEquals(ExitCode.Unusable, forge())
        assertEquals("", invocation.out.toString(), "usage must not go to stdout")
        assertTrue(invocation.err.toString().contains("forge --help"), invocation.err.toString())
    }

    /** Asking for the help is a request, and a request succeeds. */
    @Test
    fun `forge --help succeeds and answers on stdout`() {
        val invocation = invoke("--help")

        assertEquals(ExitCode.Ok, forge("--help"))
        assertTrue(invocation.out.toString().contains("forge generate"), invocation.out.toString())
        assertEquals("", invocation.err.toString(), "--help must not write to stderr")
    }

    /** Per-command help short-circuits the positional count: `forge check --help` names no document. */
    @Test
    fun `forge check --help succeeds without naming a document`() {
        assertEquals(ExitCode.Ok, forge("check", "--help"))
        assertEquals(ExitCode.Ok, forge("check", "-h"))
    }

    @Test
    fun `an unknown command names the commands that exist`() {
        val invocation = invoke("validdate", "a.json")
        val err = invocation.err.toString()

        assertEquals(ExitCode.Unusable, forge("validdate", "a.json"))
        assertTrue(err.contains("unknown command 'validdate'"), err)
        assertTrue(err.contains("generate, check"), "the refusal must name what exists: $err")
    }

    /**
     * A command the usage names is a command that exists, and a command that exists is named.
     *
     * The old usage advertised `validate`, `diff` and `migrate`, and all three answered
     * `unknown command` — the worst possible answer to a user who had just read the help. The
     * set comparison is what makes the claim total in both directions; a substring check would
     * pass with a missing row and a line-count check would be asserting on layout.
     */
    @Test
    fun `the usage names exactly the commands that are implemented`() {
        val help = invoke("--help").out.toString()

        // `forge` followed by a word. The header's `forge — Forge Engine CLI` and the
        // `forge --help` row both fail to match: an em dash is not a word and `--help` is a
        // flag, which is the distinction the check is about.
        val named = Regex("""\bforge\s+([A-Za-z][A-Za-z0-9-]*)""")
            .findAll(help)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(COMMANDS.map { it.name }.toSet(), named, "the usage and the registry disagree")
        for (spec in COMMANDS) {
            assertTrue(help.contains("forge ${spec.signature}"), "the usage omits '${spec.name}'")
        }
    }

    // -------------------------------------------------------------------------------------
    // Arguments
    // -------------------------------------------------------------------------------------

    @Test
    fun `a missing document names the signature rather than a count`() {
        val invocation = invoke("check")

        assertEquals(ExitCode.Unusable, forge("check"))
        assertTrue(
            invocation.err.toString().contains("check <document.json>"),
            invocation.err.toString(),
        )
    }

    /**
     * The old parser read `--out` as the *value* of `--package`, then wrote a Kotlin package
     * called `--out` into a directory called `dir` — silently, and with exit 0.
     */
    @Test
    fun `a flag value that looks like a flag is refused`() {
        val invocation = invoke("generate", "a.json", "--package", "--out", "dir")

        assertEquals(ExitCode.Unusable, forge("generate", "a.json", "--package", "--out", "dir"))
        assertTrue(invocation.err.toString().contains("--package"), invocation.err.toString())
    }

    @Test
    fun `a flag with no value at all is refused`() {
        assertEquals(ExitCode.Unusable, forge("generate", "a.json", "--out"))
    }

    /** The old parser ignored `--colour always` and dropped `always` on the floor. */
    @Test
    fun `an unknown flag is refused and the refusal lists what is accepted`() {
        val invocation = invoke("generate", "a.json", "--colour", "always")
        val err = invocation.err.toString()

        assertEquals(ExitCode.Unusable, forge("generate", "a.json", "--colour", "always"))
        assertTrue(err.contains("unknown flag '--colour'"), err)
        assertTrue(err.contains("--out"), "the refusal must list the accepted flags: $err")
    }

    /** The old parser read `a.json` and dropped `b.json` without a word. */
    @Test
    fun `a surplus argument names itself`() {
        val invocation = invoke("check", "a.json", "b.json")

        assertEquals(ExitCode.Unusable, forge("check", "a.json", "b.json"))
        assertTrue(
            invocation.err.toString().contains("'b.json' is unexpected"),
            invocation.err.toString(),
        )
    }

    @Test
    fun `a repeated flag is refused rather than taking the last one`() {
        assertEquals(ExitCode.Unusable, forge("generate", "a.json", "--out", "one", "--out", "two"))
    }

    /**
     * `--` is the escape a file name beginning with `-` needs, and it is the only one: without
     * it the token is read as a flag and refused.
     */
    @Test
    fun `a double dash ends the flags`() {
        val line = CommandLine.parse(CheckCommand.spec, listOf("--", "-dash.json"))

        assertEquals("-dash.json", line.positional("document.json"))
        assertEquals(ExitCode.Unusable, forge("check", "-dash.json"), "without `--` this is a flag")
    }

    /** A bare `-` is a file name, not a flag. */
    @Test
    fun `a lone dash is a positional`() {
        assertEquals("-", CommandLine.parse(CheckCommand.spec, listOf("-")).positional("document.json"))
    }

    // -------------------------------------------------------------------------------------
    // check
    // -------------------------------------------------------------------------------------

    @Test
    fun `a clean document is accepted and reports its counts on stdout`() {
        val document = write(VALID, "valid.json")
        val invocation = invoke("check", document.path)

        assertEquals(ExitCode.Ok, forge("check", document.path))
        assertEquals("", invocation.err.toString(), "a clean document has no findings")
        assertEquals(
            "forge check: 0 errors, 0 warnings, 0 info in '${document.path}'",
            invocation.out.toString().trim(),
        )
    }

    /**
     * The row the whole exit-code convention exists for: a `Severity.Error` has to be
     * *observable by a script*, and a script's only instrument is the exit code.
     */
    @Test
    fun `an error diagnostic is a rejected document, not a usage failure`() {
        val document = write(UNKNOWN_COMPONENT, "unknown.json")
        val invocation = invoke("check", document.path)

        assertEquals(ExitCode.Rejected, forge("check", document.path))
        assertTrue(invocation.err.toString().contains("component.unknown"), invocation.err.toString())
        assertTrue(
            Regex("""forge check: [1-9]\d* errors""").containsMatchIn(invocation.out.toString()),
            "the count and the exit code disagree: ${invocation.out}",
        )
    }

    /** The same verdict from `generate`, which reaches the same analysis one stage earlier. */
    @Test
    fun `generate rejects the document it cannot generate from`() {
        val document = write(UNKNOWN_COMPONENT, "unknown.json")

        assertEquals(ExitCode.Rejected, forge("generate", document.path))
    }

    // -------------------------------------------------------------------------------------
    // The two failures a script has to tell apart
    // -------------------------------------------------------------------------------------

    /** A file that is not there is not a document that is wrong. */
    @Test
    fun `a missing file is unusable, not rejected`() {
        val missing = File(directory, "absent.json")
        val invocation = invoke("check", missing.path)

        assertEquals(ExitCode.Unusable, forge("check", missing.path))
        assertTrue(invocation.err.toString().contains("no such file"), invocation.err.toString())
    }

    @Test
    fun `malformed json is unusable, not rejected`() {
        val document = write("{ not json", "broken.json")
        val invocation = invoke("check", document.path)

        assertEquals(ExitCode.Unusable, forge("check", document.path))
        assertTrue(invocation.err.toString().contains("cannot decode"), invocation.err.toString())
    }

    /** §19.4's unknown-field rule is strict by default, and a CLI is the wrong place to soften it. */
    @Test
    fun `an unknown envelope field is refused rather than ignored`() {
        val document = write(UNKNOWN_FIELD, "extra.json")

        assertEquals(ExitCode.Unusable, forge("check", document.path))
    }

    private fun write(text: String, name: String): File =
        File(directory, name).apply { writeText(text) }

    private companion object {

        /**
         * A document that uses only the built-in components: a `core.Column` holding two
         * `m3.Text` rows. `integration/generated-compile/fixtures/column_text.json` is the same
         * shape and is not used here — see the class KDoc.
         */
        val VALID: String = """
            {
              "format": "forge.ui-document",
              "formatVersion": 1,
              "schemaVersion": 1,
              "payload": {
                "app": {"packageName": "com.example.cli", "startPage": "p_home"},
                "components": {},
                "meta": {"name": "CliValid"},
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

        /**
         * [VALID] with one component type nothing registered.
         *
         * `SchemaPass` raises `component.unknown` at `Severity.Error`, so this is the smallest
         * document that makes [ExitCode.Rejected] observable — and it is an *analysis* finding
         * rather than a structural one, which is the point: the verdict comes from the engine's
         * diagnostic, not from the CLI's own reading of the file.
         */
        val UNKNOWN_COMPONENT: String = VALID.replace("core.Column", "acme.Nope")

        /** One envelope header key too many, which §19.4 makes an error rather than a silence. */
        val UNKNOWN_FIELD: String =
            VALID.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"future\": true,")
    }
}
