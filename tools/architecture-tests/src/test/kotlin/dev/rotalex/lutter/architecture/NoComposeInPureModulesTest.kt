package dev.rotalex.lutter.architecture

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §23.3 / §23.4: the core engine modules have no Compose dependency.
 *
 * ### This rule used to lie in two directions at once
 *
 * It carried a hand-written list of eight module names. A new engine module was not in the list,
 * so it was never checked — the same argument [RepositoryRoot.commonMainDirectories] already
 * makes for `src/commonMain` source sets, and the one this rule should have been making too. And
 * three modules that legitimately depend on Compose were missing from it as well, so their absence
 * read exactly like an oversight: a reader could not tell a deliberate exemption from a gap.
 *
 * So the list is gone and a module is classified by what its own build script declares. There are
 * exactly three ways this repository says "Compose here": a Compose artifact, the Compose
 * convention plugin, or a project dependency on the module that carries both.
 *
 * ### Why the pinned set is the load-bearing half
 *
 * "A module that declares no Compose must not import it" is very nearly a compile error — an
 * undeclared import does not build. So the interesting direction is the opposite one: a module
 * gaining a Compose declaration at all. Under classification alone that is invisible, because the
 * module becomes exempt and the rule stops looking at it — the same silent drift, moved.
 *
 * So the set is derived *and* pinned. A new Compose-declaring engine module fails this test and
 * the failure says what it is: an architectural decision, not a filter to widen. That is the
 * precedent Phase 10 set with a codegen corpus, where a fifth exclusion is a finding about codegen
 * rather than a filter to relax.
 *
 * ### The guard
 *
 * A classification this derived could stop discriminating — every module reading pure, or every
 * one reading exempt — and a rule with nothing left to check passes. So the test also asserts
 * that it found both kinds and names them.
 */
class NoComposeInPureModulesTest {

    @Test
    fun `an engine module that declares no compose dependency does not import compose`() {
        val pure = mutableListOf<String>()
        val violations = mutableListOf<String>()

        for ((module, sourceRoot) in RepositoryRoot.engineModules()) {
            if (declaresCompose(sourceRoot)) continue
            pure += module
            for (file in RepositoryRoot.kotlinFilesIn(sourceRoot)) {
                for (imported in file.imports.filter { it.startsWith(COMPOSE_IMPORT) }) {
                    violations += "$module/${file.name} imports $imported with no Compose declared"
                }
            }
        }

        assertTrue(pure.isNotEmpty()) { "every engine module read as exempt, so this rule checks nothing" }
        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    @Test
    fun `only the runtime layer declares compose`() {
        val declared = RepositoryRoot.engineModules()
            .filter { (_, sourceRoot) -> declaresCompose(sourceRoot) }
            .keys

        assertEquals(
            COMPOSE_DECLARING_MODULES,
            declared.toSortedSet(),
        ) {
            "the Compose-declaring engine modules changed. A core module joining this set is an " +
                "architectural decision — §23.3 and its layer split — and not a list to extend.\n" +
                "declared: $declared"
        }
    }

    private fun declaresCompose(sourceRoot: Path): Boolean = COMPOSE_DECLARATION
        .containsMatchIn(RepositoryRoot.buildFileText(sourceRoot, BUILD_FILE))

    private companion object {

        const val BUILD_FILE = "build.gradle.kts"

        const val COMPOSE_IMPORT = "androidx.compose"

        /**
         * The three modules §23.3 lets reach Compose: the renderer, the Compose bindings, and
         * the harness that drives both.
         */
        val COMPOSE_DECLARING_MODULES = setOf("builtins-compose", "runtime", "test-support")

        /**
         * The three declarations, and nothing looser.
         *
         * Anchored on the declaration so a comment cannot classify a module as exempt —
         * `:engine:runtime`'s own build file has a comment saying a property is Compose UI's —
         * and so a hyphenated project name is not one: `:samples:compose-preview` reads as pure,
         * because `-` is neither a word character nor a dot.
         */
        val COMPOSE_DECLARATION = Regex(
            """id\(\s*"forge\.kmp\.compose"|[\w.]*compose[\w.]*\s*\)|project\(":engine:builtins-compose"\)""",
        )
    }
}
