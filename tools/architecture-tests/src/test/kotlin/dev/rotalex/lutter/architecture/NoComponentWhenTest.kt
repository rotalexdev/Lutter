package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §7.4, enforced under the guard name §35 and §36.3 give it: no branching expression in
 * the engine dispatches on a component type or on a node's type.
 *
 * PLAN §7.4 states the rule and its reason in one sentence, and the reason is the part worth
 * keeping: *"No `when` over component types exists anywhere in the engine. `ThirdPartyComponentTest`
 * proves that a component defined in a test-only module works through validation, runtime,
 * codegen and serialization without editing any engine module."* A dispatch is an enumeration
 * the engine author writes by hand, and a component that is not in it works everywhere except there.
 */
class NoComponentWhenTest {

    private val dispatchingOverThreeThings = """
        package sample

        public fun columnCount(componentType: ComponentType): Int =
            when (componentType) {
                "core.Column" -> 0
                else -> 1
            }

        public fun width(node: Node): Int =
            when (node.type) {
                "core.Row" -> 1
                else -> 0
            }

        public fun height(node: Node): Int =
            when (
                node.type.value
            ) {
                "core.Box" -> 1
                else -> 0
            }
        """

    /** A dispatch on the schema, and one on a token kind: both are the registry's job, not a branch's. */
    private val dispatchingOnTheRegistry = """
        package sample

        public fun slots(spec: ComponentSpec, node: ResolvedNode): Int =
            when (spec.category) {
                Category.LAYOUT -> 0
                else -> node.slots.size
            }

        public fun label(kind: TokenKind): String =
            when (kind) {
                TokenKind.Color -> "colour"
                else -> "other"
            }
        """

    @Test
    fun `no engine branch dispatches on a component type or a node type`() {
        val violations = RepositoryRoot
            .engineSourceDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        SourceRules
                            .componentWhenOccurrences(file.text)
                            .map { occurrence -> "${file.name} in $directory: $occurrence" }
                    }
            }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    @Test
    fun `a dispatch on a component type is rejected in every spelling`() {
        val violations = SourceRules.componentWhenOccurrences(dispatchingOverThreeThings)

        assertEquals(3, violations.size, "expected all three spellings to be rejected, got $violations")
    }

    @Test
    fun `a dispatch on the registry rather than on a type is accepted`() {
        assertTrue(SourceRules.componentWhenOccurrences(dispatchingOnTheRegistry).isEmpty())
    }
}
