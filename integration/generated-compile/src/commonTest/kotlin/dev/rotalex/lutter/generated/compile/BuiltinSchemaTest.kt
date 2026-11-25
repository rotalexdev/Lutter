package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLAN §36's fuzz batch generates two hundred documents against the real builtin schema, and
 * this is the half of that claim a pull request can afford: a fraction of the seed range,
 * analysed, with no fork and no codegen.
 *
 * The generator is otherwise only ever run against `generatorSchema()`, which differs from the
 * builtin schema by three components no engine module registers. So nothing asserted that it can
 * read a schema without them — and a generator that cannot is indistinguishable, from the
 * nightly's log alone, from a batch that refuses every document and reports green.
 *
 * The codegen half is the nightly's own, because `CodegenCoverage` has already run over this
 * schema in `BuiltinCoverageTest`. A refusal there is a refusal of something downstream, so
 * failing here instead would blame the wrong layer.
 */
class BuiltinSchemaTest {

    @Test
    fun `every seed the batch walks analyses against the builtin schema`() {
        val schema = ConformanceHarness.schema()
        val failures = mutableListOf<String>()
        for (seed in 1L..SEEDS) {
            val document = DocumentGenerator(schema, seed).generate().document
            val errors = BuiltinEngine.analyze(schema, document).diagnostics
                .filter { it.severity == Severity.Error }
            if (errors.isNotEmpty()) {
                failures += "seed $seed: " + errors.joinToString("; ") { "${it.code.value}: ${it.message}" }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $SEEDS seed(s) did not analyse against the builtin schema.\n" +
                failures.joinToString("\n\n"),
        )
    }

    @Test
    fun `every navigation item the batch draws nests under a navigation bar`() {
        val schema = ConformanceHarness.schema()
        val strays = mutableListOf<String>()
        for (seed in 1L..SEEDS) {
            val document = DocumentGenerator(schema, seed).generate().document
            strays += strays(document, seed)
        }
        assertTrue(
            strays.isEmpty(),
            "${strays.size} stray navigation item(s) the codegen cannot compile.\n" +
                strays.joinToString("\n"),
        )
    }

    /**
     * The nodes of type [NAVIGATION_ITEM] whose parent is not [NAVIGATION_BAR].
     *
     * `m3.NavigationItem`'s binding is Compose's `RowScope.NavigationBarItem` extension, so the
     * generated call needs the row scope its bar opens — a stray item compiles into nothing
     * (PR #144's CI settled the shape). The generator's draw rule keeps the pair together; this
     * walk is the assertion that the rule holds across the seed range, read off the finished
     * trees rather than off the draw's internals.
     */
    private fun strays(document: UiDocument, seed: Long): List<String> {
        val parents = mutableMapOf<NodeId, NodeId>()
        fun walk(id: NodeId, parent: NodeId?) {
            if (parent != null) parents[id] = parent
            for (children in document.nodes.require(id).slots.values) {
                for (child in children) walk(child, id)
            }
        }
        for (page in document.pages.values) walk(page.root, null)
        return parents.mapNotNull { (id, parent) ->
            val type = document.nodes.require(id).type
            if (type == NAVIGATION_ITEM &&
                document.nodes.require(parent).type != NAVIGATION_BAR
            ) {
                "seed $seed: ${id.value} is a $type under ${document.nodes.require(parent).type}"
            } else {
                null
            }
        }
    }

    private companion object {

        /** A fraction of the nightly's range: twenty seeds here, two hundred there. */
        const val SEEDS: Long = 20

        val NAVIGATION_BAR: ComponentType = ComponentType("m3.NavigationBar")
        val NAVIGATION_ITEM: ComponentType = ComponentType("m3.NavigationItem")
    }
}