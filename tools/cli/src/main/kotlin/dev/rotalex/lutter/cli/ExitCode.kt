package dev.rotalex.lutter.cli

/**
 * The four answers `forge` can give, and what each one is a claim about.
 *
 * ### The convention
 *
 *  * [Ok] — the command ran, and the answer to the question it asks is yes.
 *  * [Rejected] — the command ran, and the answer is no: the document carries at least one
 *    `Severity.Error`. **This is the only code a caller should branch on to decide whether a
 *    document is good.**
 *  * [Unusable] — the command did not run. A usage error, an unknown flag, a file that is not
 *    there, a payload that does not decode.
 *  * [EngineFailure] — the engine failed on input this CLI had already accepted.
 *
 * ### Why [Rejected] and [Unusable] cannot be the same number
 *
 * They were, and that was the defect. `forge` exited 1 both for a document with an error and
 * for a path that did not exist, so nothing downstream could tell "the artefact I built is
 * broken" from "you typed the command wrong". A CI step cannot retry one and fail the pipeline
 * on the other, and a wrapper cannot decide which of the two to show a person.
 *
 * The split is the whole of the reason [Rejected] is keyed on `Severity.Error` and not on
 * "the command printed something". A `Severity.Warning` is a finding the engine chose to
 * report without refusing the document — `AnalysisResult.hasErrors` is the engine's own
 * statement of that rule, and the CLI reads it rather than restating it. Note that no pass in
 * `:engine:analysis` emits a `Warning` today, so [Rejected] is presently the only non-zero
 * answer a valid invocation can give; the rule is stated for the day one appears rather than
 * for the day it does.
 *
 * ### Why [EngineFailure] exists at all
 *
 * Without it, an exception escaping the engine exits 1 and a CI job records a broken document
 * when the truth is a bug. [Rejected] is a claim about the *input*, and the CLI should only
 * make that claim when the engine actually made it. Every expected failure is translated to a
 * [CliFailure] at the site that knows what it was, which is what leaves this code seeing only
 * the ones nobody anticipated.
 *
 * ### The shape of the set
 *
 * `grep`, `diff` and `git` all keep a small range and spend 1 and 2 on "trouble"; `sysexits.h`
 * spends 64 and up. This takes the small range, because the number that travels through a
 * Makefile is read by a person and four values are four things to remember. There is no 3 in
 * the tradition this follows, and [EngineFailure] takes it anyway rather than overloading 2:
 * "you invoked me wrong" and "the engine is broken" are the two answers a caller most needs to
 * tell apart, and neither is the other.
 */
internal object ExitCode {

    /** The command ran and the document is accepted. */
    const val Ok: Int = 0

    /** The command ran and the document carries at least one `Severity.Error`. */
    const val Rejected: Int = 1

    /** The command did not run: usage, a missing file, or a payload that does not decode. */
    const val Unusable: Int = 2

    /** The engine failed on input the CLI had accepted. */
    const val EngineFailure: Int = 3
}

/**
 * A failure that already knows which [ExitCode] it is.
 *
 * ### Why the code travels with the message
 *
 * Every failure site in this CLI knows two things: what went wrong, and whether the fault is
 * the user's or the engine's. Carrying the code on the exception makes those two facts one
 * statement, so [ExitCode] cannot be forgotten at a call site and a site cannot quietly
 * disagree with the convention [ExitCode]'s KDoc states. The alternative — throwing and
 * translating at each `catch` — is one more place per failure to get the mapping wrong, and
 * there are a dozen of them.
 *
 * It extends [RuntimeException] because every throw site is an ordinary control-flow path for
 * a command line: a missing file is not exceptional, it is a question with an answer.
 */
internal class CliFailure(
    val code: Int,
    override val message: String,
) : RuntimeException(message)
