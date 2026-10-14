package dev.rotalex.lutter.cli

import java.io.File

/**
 * `forge check <document.json>`.
 *
 * ### The name, and why not `validate`
 *
 * `check` is the name PLAN's own Phase 10 work unit gives this command, and it is the one §36.2
 * already uses for the engine-wide verification step. Choosing it over `validate` is not only
 * that prior agreement, though — the two words ask for different things.
 *
 * `validate` names a *stage*, and this CLI has a stage by that name already: `forge generate`
 * runs the same analysis and then goes on to emit code. Two commands whose names both say
 * "validate", one of which validates and one of which validates and then does something else,
 * is the ambiguity a user has to resolve by reading the source. `check` asks a *question* —
 * is this document good? — and so does `diff`, and a pair of questions reads as a pair. It
 * also does not over-promise: nothing here validates a schema, a plugin pack or a resource
 * bundle, and the only thing this command ever looks at is one document.
 *
 * ### The three channels, and why they are three
 *
 * A diagnostic is not an error message — it is the answer to the question the command asks, and
 * a command whose answer is a list should not hide the list in the error stream:
 *
 *  * **[exit code]** the verdict. This is the contract a script branches on, and it is
 *    [ExitCode.Rejected] when — and only when — the engine produced a `Severity.Error`.
 *  * **stderr** the findings, one per line, in the engine's own order. Diagnostics on stderr
 *    keeps them out of a pipeline: `forge check f.json | wc -l` counts output, not findings.
 *  * **stdout** exactly one line of counts. Not the verdict — the verdict is the exit code, and
 *    printing it twice is two places for the two to disagree. The counts are data, and they
 *    carry all three `Severity` cases so that a severity nobody reports today is still counted
 *    the day it appears rather than silently dropping out of the summary.
 *
 * ### What it does *not* do
 *
 * It does not generate, and it cannot: generation is reached only from [GenerateCommand]. That
 * is deliberate and is what makes §36.3's "decode → validate → generate" three separable
 * steps rather than one function with a flag — a `check` that generated would have to answer
 * "is this document good?" with a second question about whether codegen found a binding.
 */
internal object CheckCommand {

    /** What the usage prints and what `forge check` dispatches to. */
    val spec: CommandSpec = CommandSpec(
        name = "check",
        signature = "check <document.json>",
        summary = "decode and report the document's diagnostics",
        positionals = listOf("document.json"),
        flags = mapOf(
            "--help" to Arity.None,
            "-h" to Arity.None,
        ),
        run = { line, out, err -> run(line, out, err) },
    )

    internal fun run(line: CommandLine, out: Appendable, err: Appendable): Int {
        val input: String = line.positional("document.json")
        val document = Engine.readDocument(File(input), spec.name)

        val analysis = Engine.analyze(Engine.builtinSchema(), document)
        Engine.report(analysis.diagnostics, err)

        // All three severities, not only the one that decides the verdict: the counts describe
        // what the engine said, and a summary that dropped the third case would be a hole the
        // day something starts emitting it.
        val counts = Engine.counts(analysis.diagnostics)
        out.appendLine(
            "forge ${spec.name}: ${counts.errors} errors, ${counts.warnings} warnings, " +
                "${counts.info} info in '$input'",
        )

        // `AnalysisResult.hasErrors` is the engine's own statement of the rule this command
        // exits on, so a warning never changes the verdict and the CLI never restates the test.
        return if (analysis.hasErrors) ExitCode.Rejected else ExitCode.Ok
    }
}
