package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 1: every structural invariant fails visibly, with its catalogued code. */
class StructuralPassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

    @Test
    fun `missing page root reports missing root`() {
        val codes = codesOf { root(NodeId("n_ghost")) }

        assertTrue(codes.contains(DiagnosticCodes.StructMissingRoot.value), "got $codes")
    }

    @Test
    fun `unknown start page reports missing root`() {
        val valid = validDocument()
        val document = valid.copy(app = valid.app.copy(startPage = PageId("p_nope")))

        val codes = analyzer.analyze(document).diagnostics.map { it.code.value }

        assertTrue(codes.contains(DiagnosticCodes.StructMissingRoot.value), "got $codes")
    }

    @Test
    fun `slot naming an absent node reports missing node`() {
        val codes = codesOf {
            node(ColumnType) { slot("children", listOf(NodeId("n_ghost"))) }
        }

        assertTrue(codes.contains(DiagnosticCodes.StructMissingNode.value), "got $codes")
    }

    @Test
    fun `child in two slots reports multiple parents`() {
        val codes = codesOf {
            node(ColumnType) {
                val shared = node(TextType) { prop("text", Value.Str("s")) }
                val inner = node(ColumnType) { slot("children", listOf(shared)) }
                slot("children", listOf(shared, inner))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.StructMultipleParents.value), "got $codes")
    }

    @Test
    fun `child listed twice in one slot reports duplicate id`() {
        val codes = codesOf {
            node(ColumnType) {
                val title = node(TextType) { prop("text", Value.Str("s")) }
                slot("children", listOf(title, title))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.StructDuplicateId.value), "got $codes")
    }

    @Test
    fun `cyclic slots report a cycle`() {
        val codes = codesOf {
            node(ColumnType, id = NodeId("n_a")) { slot("children", listOf(NodeId("n_b"))) }
            node(ColumnType, id = NodeId("n_b")) { slot("children", listOf(NodeId("n_a"))) }
            root(NodeId("n_a"))
        }

        assertTrue(codes.contains(DiagnosticCodes.StructCycle.value), "got $codes")
    }

    @Test
    fun `unreachable node reports orphan`() {
        val result = analyzer.analyze(
            homeDocument {
                val kept = node(ColumnType) {
                    val title = node(TextType) { prop("text", Value.Str("kept")) }
                    slot("children", listOf(title))
                }
                node(TextType) { prop("text", Value.Str("loose")) }
                root(kept)
            },
        )
        val orphans = result.diagnostics.filter { it.code == DiagnosticCodes.StructOrphan }

        assertEquals(1, orphans.size, "expected the loose node only, got ${result.diagnostics}")
    }

    @Test
    fun `sound structure reports nothing`() {
        val result = analyzer.analyze(validDocument())

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
    }
}
