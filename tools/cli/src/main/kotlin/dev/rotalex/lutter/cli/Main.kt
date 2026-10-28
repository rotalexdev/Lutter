package dev.rotalex.lutter.cli

import kotlin.system.exitProcess

/**
 * Every command, in the order the usage prints them.
 *
 * This list is the single registration point: `dispatch` resolves against it and [usage] is
 * rendered from it, so a command that exists and a command that is advertised cannot come
 * apart. The usage text used to name four commands and implement one — `validate`, `diff` and
 * `migrate` were advertised and refused — and the refusal was `unknown command`, which is the
 * worst possible answer to a user who read the help.
 */
internal val COMMANDS: List<CommandSpec> = listOf(
    GenerateCommand.spec,
    CheckCommand.spec,
)

/**
 * The `forge` entry point.
 *
 * The body is one statement because everything else is in [execute]: [exitProcess] is called
 * exactly once, here, and nowhere else in the module. Both are deliberate. One place that exits
 * means no command body can decide its own fate by terminating the JVM, which is what lets
 * [execute] be called straight from a test and still be the code a user runs; and a `main` this
 * short is one that can be read to confirm nothing happens before [dispatch] decides anything.
 */
public fun main(args: Array<String>) {
    exitProcess(execute(args.toList(), System.out, System.err))
}

/**
 * The whole of `forge`, as a value rather than a process.
 *
 * ### Why the exit code is a return value
 *
 * `main` cannot be tested — calling it terminates the test JVM — and the exit code is the part
 * of this CLI a script depends on most. Returning it makes the one contract that matters
 * assertable, and it costs one function: every command body already returns an [ExitCode]
 * rather than exiting.
 *
 * **Named `execute` rather than `run`.** `run` is `kotlin.run`, and a top-level `run` in a
 * package every test file also lives in is one that a reader has to resolve against the
 * standard library before they can read the call. The one place that would not matter — `main`
 * — is the one place a shorter name would have been nice.
 *
 * ### The two catches, and why each is here
 *
 * [CliFailure] is every failure that was anticipated: the code it carries is the one [ExitCode]
 * states for it, and every site that throws one has already said which of the two it is.
 *
 * `Exception` is everything else, and it is [ExitCode.EngineFailure] rather than
 * [ExitCode.Rejected] because [ExitCode.Rejected] is a claim about the *document*: the engine
 * has to be the one making it. Printing the exception and carrying on to the caller's handler
 * is the right answer rather than letting a stack trace reach a build log, which says the same
 * thing in a form nobody reads. Nothing here catches [Throwable]: an `OutOfMemoryError` is a
 * fact about the process, not a verdict about a document.
 */
internal fun execute(args: List<String>, out: Appendable, err: Appendable): Int = try {
    dispatch(args, out, err)
} catch (failure: CliFailure) {
    err.appendLine("forge: ${failure.message}")
    failure.code
} catch (failure: Exception) {
    err.appendLine("forge: the engine failed: ${failure::class.simpleName}: ${failure.message}")
    ExitCode.EngineFailure
}

/**
 * The command lookup and the three ways not to need one.
 *
 * **No arguments is [ExitCode.Unusable], not [ExitCode.Ok].** It used to be zero, and that was
 * the defect: an invocation that named no command printed the help and reported success, so a
 * pipeline whose first step was `forge` passed forever without ever checking a document. The
 * distinction this draws is between *asking for the help* and *running nothing*: the first is a
 * request and is answered on stdout with [ExitCode.Ok], the second is a mistake and is
 * answered on stderr with [ExitCode.Unusable]. A build tool that runs `forge --help` to prove
 * the binary works keeps working; a build step that runs bare `forge` now fails, which is the
 * whole point.
 */
private fun dispatch(args: List<String>, out: Appendable, err: Appendable): Int {
    val command = args.firstOrNull()
    if (command == null) {
        err.appendLine(usage())
        return ExitCode.Unusable
    }
    if (command == HELP || command == HELP_SHORT) {
        out.appendLine(usage())
        return ExitCode.Ok
    }

    val spec = COMMANDS.firstOrNull { it.name == command }
        ?: throw CliFailure(
            ExitCode.Unusable,
            "unknown command '$command'; known: ${COMMANDS.joinToString(", ") { it.name }}. " +
                "Run 'forge --help'.",
        )

    val line = CommandLine.parse(spec, args.drop(1))
    if (line.helpRequested) {
        out.appendLine(usage())
        return ExitCode.Ok
    }
    return spec.run(line, out, err)
}

/**
 * The help text, rendered from [COMMANDS].
 *
 * Generated rather than written because a hand-written copy is a promise, and this one already
 * broke: it advertised three commands that did not exist. Every line naming a command comes
 * from that command's own [CommandSpec], so a signature and the signature in its error messages
 * are the same string.
 */
private fun usage(): String = buildString {
    appendLine("forge — Forge Engine CLI")
    appendLine()
    for (spec in COMMANDS) {
        appendLine("  forge ${spec.signature}")
        appendLine("      ${spec.summary}")
    }
    appendLine("  forge $HELP")
    appendLine("      this text")
    appendLine()
    appendLine("Exit codes:")
    appendLine("  ${ExitCode.Ok}  the command ran and the document is accepted")
    appendLine("  ${ExitCode.Rejected}  the command ran and the document has an Error diagnostic")
    appendLine("  ${ExitCode.Unusable}  the command did not run: usage, a missing or undecodable file")
    appendLine("  ${ExitCode.EngineFailure}  the engine failed on input this CLI had accepted")
}
