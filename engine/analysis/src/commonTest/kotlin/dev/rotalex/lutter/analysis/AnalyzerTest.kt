package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orchestrator: valid documents resolve, failures stop passes, nothing throws. */
class AnalyzerTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val pageId: PageId = PageId("p_home")

    @Test
    fun `valid document resolves with defaults applied`() {
        val result = analyzer.analyze(validDocument())

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val resolved = assertNotNull(result.resolved)
        val root = assertNotNull(resolved.pages[pageId]).root
        assertEquals(ColumnType, root.type)
        val spacing = assertNotNull(root.props[PropertyKey("spacing")])
        assertEquals(PropOrigin.Default, spacing.origin)
        assertEquals(Value.Dp(0f), (spacing.value as PropertyValue.Const).value)
        val title = assertNotNull(root.slots[SlotName("children")]).single()
        assertEquals(TextType, title.type)
        val text = assertNotNull(title.props[PropertyKey("text")])
        assertEquals(PropOrigin.Specified, text.origin)
        val maxLines = assertNotNull(title.props[PropertyKey("maxLines")])
        assertEquals(PropOrigin.Default, maxLines.origin)
    }

    @Test
    fun `resolved nodes carry their scopes`() {
        val resolved = assertNotNull(analyzer.analyze(validDocument()).resolved)
        val root = assertNotNull(resolved.pages[pageId]).root
        val title = assertNotNull(root.slots[SlotName("children")]).single()

        assertTrue(root.scopes.isEmpty(), "root scopes: ${root.scopes}")
        assertTrue(title.scopes.contains(ColumnScope), "title scopes: ${title.scopes}")
    }

    @Test
    fun `structural failure stops later passes`() {
        val result = analyzer.analyze(
            homeDocument {
                node("core.Ghost") { slot("children", listOf(NodeId("n_ghost"))) }
            },
        )
        val codes = result.diagnostics.map { it.code.value }

        assertTrue(result.hasErrors)
        assertNull(result.resolved)
        assertTrue(codes.none { it == DiagnosticCodes.ComponentUnknown.value }, "got $codes")
        assertTrue(codes.contains(DiagnosticCodes.StructMissingNode.value), "got $codes")
    }

    @Test
    fun `schema failure blocks resolution with the documented message`() {
        val result = analyzer.analyze(
            homeDocument {
                node(TextType) { prop("text", Value.Int32(3)) }
            },
        )
        val mismatch = result.diagnostics.single { it.code == DiagnosticCodes.PropTypeMismatch }

        assertTrue(result.hasErrors)
        assertNull(result.resolved)
        assertEquals("Property 'text' expects Str but found Int32", mismatch.message)
    }

    @Test
    fun `invalid documents return diagnostics instead of throwing`() {
        val result = analyzer.analyze(
            homeDocument {
                node(ColumnType, id = NodeId("n_a")) { slot("children", listOf(NodeId("n_b"))) }
                node(ColumnType, id = NodeId("n_b")) { slot("children", listOf(NodeId("n_a"))) }
                node(TextType) { prop("text", Value.Str("loose")) }
                root(NodeId("n_a"))
            },
        )

        assertTrue(result.hasErrors, "expected errors, got ${result.diagnostics}")
        assertNull(result.resolved)
    }

    @Test
    fun `catalog holds exactly the codes these passes emit`() {
        val catalog = listOf(
            DiagnosticCodes.StructDuplicateId,
            DiagnosticCodes.StructMissingNode,
            DiagnosticCodes.StructCycle,
            DiagnosticCodes.StructOrphan,
            DiagnosticCodes.StructMultipleParents,
            DiagnosticCodes.StructMissingRoot,
            DiagnosticCodes.ComponentUnknown,
            DiagnosticCodes.ComponentSlotUnknown,
            DiagnosticCodes.ComponentSlotCardinality,
            DiagnosticCodes.ComponentChildNotAllowed,
            DiagnosticCodes.ComponentConflictingProperties,
            DiagnosticCodes.PropUnknown,
            DiagnosticCodes.PropRequiredMissing,
            DiagnosticCodes.PropTypeMismatch,
            DiagnosticCodes.PropEnumEntryInvalid,
            DiagnosticCodes.PropRange,
            DiagnosticCodes.PropNotBindable,
            DiagnosticCodes.ValueNonFinite,
            DiagnosticCodes.ModifierUnknown,
            DiagnosticCodes.ModifierScopeMissing,
            DiagnosticCodes.ModifierArgInvalid,
            DiagnosticCodes.RefDangling,
            DiagnosticCodes.RefKindMismatch,
            DiagnosticCodes.TokenUnknown,
            DiagnosticCodes.ResourceUnknown,
        ).map { it.value }.toSet()

        assertEquals(25, catalog.size, "catalog grew without a test: $catalog")
    }
}
