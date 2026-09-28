package dev.rotalex.lutter.cli

/**
 * Entry point of the `forge` command.
 *
 * Phase 0 wires the CLI into the build so the module graph and the JVM toolchain are proven
 * end to end. The four subcommands are not implemented and this deliberately does not
 * pretend otherwise: `validate`, `generate` and `diff` arrive in Phase 4 with the analyzer
 * and the generator, and `migrate` in Phase 10 with the migration format.
 *
 * It exits normally and returns 0, because a build tool that fails the build for being
 * unfinished would make the unfinished state harder to see, not easier.
 */
public fun main() {
    println(
        """
        forge — Forge Engine CLI (Phase 0 stub)

        No subcommand is implemented yet. The command surface is fixed, so scripts written
        against it now will keep working:

          forge validate <document.json>   diagnose a document     (Phase 4)
          forge generate <document.json>   emit Kotlin/Compose     (Phase 4)
          forge diff <a.json> <b.json>      structural difference   (Phase 4)
          forge migrate <document.json>    upgrade to the current  (Phase 10)

        Exit code is 0: the stub reports its state, it does not signal a failure.
        """.trimIndent(),
    )
}
