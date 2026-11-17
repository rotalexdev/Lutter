package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * §6.2:537's seam at the orchestrator: a dirty set narrows pass 7, and nothing else.
 *
 * The patch behind every test here is a single property edit — `SetProp`'s shape — so the dirty
 * set is exactly what the bridge answers: the touched leaf closed over its ancestors, the
 * untouched sibling left out. The analysis module cannot see the patch itself (§23.2 gives this
 * module `:engine:model` and `:engine:schema` and nothing else), so the tests hand the folded
 * ids over directly, which is the seam's own contract: the caller that *can* see the patch folds
 * `touchedNodes` into `DirtyNodes.of` and passes the result here.
 */
class AnalyzerDirtySetTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    /** One column holding a text and a nested column holding another text. */
    private fun document(inner: String): UiDocument = homeDocument {
        node(ColumnType, id = NodeId("n_root")) {
            val title = node(TextType, id = NodeId("n_title")) { prop("text", Value.Str("Hi")) }
            val body = node(ColumnType, id = NodeId("n_body")) {
                val leaf = node(TextType, id = NodeId("n_leaf")) { prop("text", Value.Str(inner)) }
                slot("children", listOf(leaf))
            }
            slot("children", listOf(title, body))
        }
    }

    /** The bridge's answer for the property edit the [document] pair above models. */
    private fun dirtyOf(patched: UiDocument): DirtyNodes =
        DirtyNodes.of(setOf(NodeId("n_leaf")), DocumentIndex(patched))

    @Test
    fun `an untouched sibling keeps its previous resolved node at reference identity`() {
        val before = assertNotNull(analyzer.analyze(document("Body")).resolved)
        val patched = document("Changed")
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)

        assertSame(before.nodes[NodeId("n_title")], after.nodes[NodeId("n_title")])
    }

    @Test
    fun `a dirty ancestor is lowered again, not carried across`() {
        val before = assertNotNull(analyzer.analyze(document("Body")).resolved)
        val patched = document("Changed")
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)

        // Both are ancestors of the touched leaf, and each embeds the leaf's resolved result in
        // a slot list, so both have to be rebuilt for the edit to be visible in the output.
        assertNotSame(before.nodes[NodeId("n_body")], after.nodes[NodeId("n_body")])
        assertNotSame(before.nodes[NodeId("n_root")], after.nodes[NodeId("n_root")])
    }

    @Test
    fun `incremental re-analysis equals a full analysis`() {
        val full = assertNotNull(analyzer.analyze(document("Changed")).resolved)
        val before = assertNotNull(analyzer.analyze(document("Body")).resolved)
        val patched = document("Changed")
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), before).resolved)

        assertEquals(full, after)
    }

    @Test
    fun `a dirty set without a previous run is a full analysis`() {
        val full = assertNotNull(analyzer.analyze(document("Changed")).resolved)
        val patched = document("Changed")
        val after = assertNotNull(analyzer.analyze(patched, dirtyOf(patched), null).resolved)

        assertEquals(full, after)
    }
}
