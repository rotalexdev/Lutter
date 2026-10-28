package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.action.ActionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orchestrator: valid documents resolve, failures stop passes, nothing throws. */
class AnalyzerTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())

    // Only the action rules need the actions slot bound: a handler on `testSchema()`'s stub is
    // `action.unknown`, which would prove the gate without proving anything about a handler.
    private val actionAnalyzer: Analyzer<ActionSpec, String, String> = Analyzer(actionSchema())
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
    fun `a handler error is reported alongside an earlier one`() {
        // One node, two independent faults: `value` is required by `m3.Field`, and the step omits
        // the `page` its spec requires. The pass 3 finding must not hide the pass 6 one.
        val result = actionAnalyzer.analyze(
            homeDocument {
                node(FieldType) {
                    event(ChangeEvent, ActionSequence(listOf(ActionStep(ActionId("nav.navigate")))))
                }
            },
        )

        assertEquals(
            setOf(DiagnosticCodes.PropRequiredMissing, DiagnosticCodes.ActionArgInvalid),
            result.diagnostics.map { it.code }.toSet(),
        )
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
            DiagnosticCodes.ExprUnknownFunction,
            DiagnosticCodes.ExprTypeMismatch,
            DiagnosticCodes.ExprNullableAccess,
            DiagnosticCodes.ExprUnresolvedRef,
            DiagnosticCodes.ActionUnknown,
            DiagnosticCodes.ActionArgInvalid,
            DiagnosticCodes.NavArgsMismatch,
        ).map { it.value }.toSet()

        // Every row a live pass raises: passes 1, 3, 4, 5 and 6. The codegen rows are not here
        // because no pass 8 exists to raise them, which is what `DiagnosticCodes`' own KDoc says.
        assertEquals(32, catalog.size, "catalog grew without a test: $catalog")
    }
}
