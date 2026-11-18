package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §6.2:537's reporting half: the passes validate the dirty closure and carry the rest.
 *
 * Every fixture is the same shape `AnalyzerDirtySetTest` uses — one column with two text leaves
 * — so the dirty set for a one-property edit on `n_b` is exactly `{ n_b, n_root }` and `n_a`
 * sits outside it. What these tests pin is the carry contract the [Analyzer.analyze]
 * `previousFindings` parameter states: a finding attributed to a node outside the closure comes
 * from the previous run, a finding attributed to a node inside it is recomputed, and a finding
 * whose node no longer exists cannot survive.
 */
class AnalyzerCarriedFindingsTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    /**
     * One column with two text leaves. [aText] and [bText] are the leaves' `text` properties;
     * `null` leaves the property absent, which is a required-missing finding for a `Text`.
     */
    private fun document(aText: Value?, bText: Value?): UiDocument = homeDocument {
        node(ColumnType, id = NodeId("n_root")) {
            val a = node(TextType, id = NodeId("n_a")) {
                if (aText != null) prop("text", aText)
            }
            val b = node(TextType, id = NodeId("n_b")) {
                if (bText != null) prop("text", bText)
            }
            slot("children", listOf(a, b))
        }
    }

    private fun dirtyOf(document: UiDocument, touched: NodeId): DirtyNodes =
        DirtyNodes.of(setOf(touched), DocumentIndex(document))

    @Test
    fun `a finding outside the closure is carried while the edited node is recomputed`() {
        // Before: `n_a` is missing its required `text`; `n_b` is valid. The run reports one
        // error, so it resolves nothing — which is exactly why the previous resolution passed
        // below is null and only the findings are carried.
        val before = analyzer.analyze(document(aText = null, bText = Value.Str("ok")))
        val beforeFindings = before.diagnostics
        assertEquals(listOf(DiagnosticCodes.PropRequiredMissing), beforeFindings.map { it.code })
        assertNull(before.resolved)

        // The edit gives `n_b` a value of the wrong type. `n_a` is untouched, so its finding has
        // to come across from the previous run while `n_b`'s is found afresh.
        val patched = document(aText = null, bText = Value.Int32(3))
        val dirty = dirtyOf(patched, NodeId("n_b"))
        val incremental = analyzer.analyze(patched, dirty, null, beforeFindings)
        val full = analyzer.analyze(patched)

        assertEquals(full.diagnostics, incremental.diagnostics)

        val carried = incremental.diagnostics.single { it.location.nodeId == NodeId("n_a") }
        assertEquals(DiagnosticCodes.PropRequiredMissing, carried.code)
        // Identity, not merely equality: the carried finding is the previous run's object, which
        // is what proves the clean node was not validated again.
        assertSame(beforeFindings.single(), carried)

        val recomputed = incremental.diagnostics.single { it.location.nodeId == NodeId("n_b") }
        assertEquals(DiagnosticCodes.PropTypeMismatch, recomputed.code)
    }

    @Test
    fun `a carried finding naming a node the document no longer holds is dropped`() {
        val doc = document(aText = Value.Str("A"), bText = Value.Str("ok"))
        val dirty = dirtyOf(doc, NodeId("n_b"))
        val stale = Diagnostic(
            Severity.Error,
            DiagnosticCodes.ComponentUnknown,
            DiagnosticLocation(nodeId = NodeId("n_gone")),
            "Unknown component type 'core.Ghost' (node 'n_gone')",
            mapOf("type" to "core.Ghost", "node" to "n_gone"),
        )

        // No exception, no stale finding: the carry filter drops the node the table does not hold.
        val incremental = analyzer.analyze(doc, dirty, null, listOf(stale))
        val full = analyzer.analyze(doc)

        assertTrue(incremental.diagnostics.none { it.location.nodeId == NodeId("n_gone") })
        assertEquals(full.diagnostics, incremental.diagnostics)
        assertNotNull(incremental.resolved)
    }
}
