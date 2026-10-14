package dev.rotalex.lutter.cli

/**
 * The two spellings of "explain yourself", accepted by every command.
 *
 * Declared once at file level rather than per command because they are not a policy of any one
 * command: `forge --help` and `forge check --help` have to answer identically, and two copies
 * of the spelling is how they stop agreeing.
 */
internal const val HELP: String = "--help"

internal const val HELP_SHORT: String = "-h"

private const val FLAG_TERMINATOR: String = "--"

/** Whether a flag consumes the token after it. */
internal enum class Arity {

    /** `--help`: the flag is the whole of it. */
    None,

    /** `--out <dir>`: the next token is the flag's value. */
    Value,
}

/**
 * One command: what it is called, what it accepts, and what it does when called.
 *
 * A record rather than a sealed hierarchy because a command is a *row*, not a variant: there
 * is no behaviour to override and nothing to exhaustively match on, and the registry in
 * `Main.kt` is a list because it is also the source of the usage text. A hierarchy would make
 * adding a command a new class plus a new `when`, which is two places to forget instead of
 * one line plus one body.
 *
 * @property name what a caller types after `forge`.
 * @property signature the invocation, as the usage prints it. Also the error text for a missing
 *   or surplus positional, so a command cannot describe one shape in help and another in a
 *   refusal.
 * @property summary the one line under the signature.
 * @property positionals the accepted bare arguments, in order, under the names callers and
 *   error messages use.
 * @property flags every flag the command accepts, including `--help`. An [Arity.None] flag is
 *   recorded rather than swallowed, which is what makes an unknown one a refusal instead of a
 *   silent no-op.
 * @property run the body. It returns an [ExitCode] rather than exiting, so `main` in `Main.kt`
 *   is the only place that calls `exitProcess` and a test can call the same code a user does.
 */
internal class CommandSpec(
    val name: String,
    val signature: String,
    val summary: String,
    val positionals: List<String>,
    val flags: Map<String, Arity>,
    val run: (CommandLine, Appendable, Appendable) -> Int,
)

/**
 * The arguments of one invocation, already checked against its [CommandSpec].
 *
 * ### What the old parser did, and what each of these rules replaces
 *
 * The one rule it had was "the first argument that does not start with `--` is the document",
 * and everything else fell out of that being the whole of the parser:
 *
 *  * `--package --out dir` read `--out` as the package name. **A flag's value may not look
 *    like a flag**, so that line is refused instead of writing a Kotlin package called
 *    `--out` into a directory called `dir`.
 *  * `--colour always` was ignored, and the argument it swallowed was never looked at. **Every
 *    flag must be in the command's table**, and the refusal names what the table holds.
 *  * `generate a.json b.json` read `a.json` and dropped `b.json`. **The number of bare
 *    arguments is checked against the spec**, and a surplus names the argument that was
 *    unexpected rather than printing a count.
 *  * A file called `-out.json` was unreachable. **`--` ends the flags**, so the rest is
 *    positional.
 *  * `--out` twice took the last value silently. **A flag may appear once.**
 *
 * @property positionals the bare arguments, keyed by the name the spec gave them.
 */
internal class CommandLine(
    val positionals: Map<String, String>,
    private val options: Map<String, String>,
) {

    /**
     * The bare argument called [name].
     *
     * Named rather than indexed so that reordering a command's positional list is a compile
     * error at the read site instead of a document written to the wrong file.
     */
    fun positional(name: String): String = checkNotNull(positionals[name]) {
        "'$name' was parsed out of an invocation that does not carry it"
    }

    /** The value of [flag], or `null` when it was not given. A bare `-` is a legal value. */
    fun option(flag: String): String? = options[flag]

    /** Whether [flag] was given. Only meaningful for an [Arity.None] flag. */
    fun has(flag: String): Boolean = options.containsKey(flag)

    /** Whether help was asked for, in either spelling. */
    val helpRequested: Boolean get() = has(HELP) || has(HELP_SHORT)

    companion object {

        /**
         * A token that begins option parsing.
         *
         * `-` alone does not: it is a legal file name and a legal directory name, and a parser
         * that refused it would make `forge check ./-` necessary to read a document called
         * `-`. Everything else that starts with `-` is a flag, including one this command does
         * not accept, so that `forge check -x` is a refusal naming `-x` rather than a path
         * lookup that happens to fail.
         */
        private fun looksLikeFlag(token: String): Boolean = token.startsWith("-") && token != "-"

        /**
         * Checks [args] against [spec].
         *
         * **The positional count is skipped when help was asked for.** `forge check --help`
         * names no document, and refusing that would make `--help` unusable on exactly the
         * command whose arguments someone is unsure about — so help short-circuits the parse
         * and the count is only enforced on an invocation that means to do work.
         *
         * @throws CliFailure with [ExitCode.Unusable] for every refusal. None of them is a fact
         *   about the document, which is why none of them is [ExitCode.Rejected].
         */
        fun parse(spec: CommandSpec, args: List<String>): CommandLine {
            val bare: MutableList<String> = mutableListOf()
            val options: MutableMap<String, String> = mutableMapOf()

            var index = 0
            var literal = false
            while (index < args.size) {
                val token = args[index]
                index++
                when {
                    literal -> bare += token
                    token == FLAG_TERMINATOR -> literal = true
                    !looksLikeFlag(token) -> bare += token
                    spec.flags[token] == null -> throw CliFailure(
                        ExitCode.Unusable,
                        "unknown flag '$token' for 'forge ${spec.name}'; accepted: " +
                            spec.flags.keys.sorted().joinToString(", "),
                    )

                    options.containsKey(token) -> throw CliFailure(
                        ExitCode.Unusable,
                        "flag '$token' given more than once for 'forge ${spec.name}'",
                    )

                    spec.flags[token] == Arity.None -> options[token] = ""

                    else -> {
                        val value = args.getOrNull(index)
                            ?: throw CliFailure(
                                ExitCode.Unusable,
                                "'forge ${spec.name}' flag '$token' needs a value",
                            )
                        if (looksLikeFlag(value)) {
                            throw CliFailure(
                                ExitCode.Unusable,
                                "'forge ${spec.name}' flag '$token' needs a value, but '$value' is " +
                                    "a flag. Put '$FLAG_TERMINATOR' before a value that starts with '-'.",
                            )
                        }
                        options[token] = value
                        index++
                    }
                }
            }

            if (options.containsKey(HELP) || options.containsKey(HELP_SHORT)) {
                return CommandLine(emptyMap(), options)
            }

            if (bare.size > spec.positionals.size) {
                throw CliFailure(
                    ExitCode.Unusable,
                    "'forge ${spec.name}' takes exactly ${spec.positionals.size} argument(s) " +
                        "(${spec.signature}); '${bare[spec.positionals.size]}' is unexpected",
                )
            }
            if (bare.size < spec.positionals.size) {
                throw CliFailure(
                    ExitCode.Unusable,
                    "'forge ${spec.name}' needs ${spec.signature}",
                )
            }

            return CommandLine(spec.positionals.zip(bare).toMap(), options)
        }
    }
}
