package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.DocumentScope
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3 sees a `doc.*` instance as the spec its own declaration synthesizes (§8.4).
 *
 * The overlay is [Analyzer]'s to build, so these are the only tests that can say a document
 * component is analyzed rather than refused. Two of the four promises are refusals on purpose:
 * a mis-declared instance is checked against the *declaration's* params and slots rather than
 * waved through, and one document's declarations never reach another's analysis.
 */
class ComponentOverlayTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val badge: ComponentDeclId = ComponentDeclId("c_badge")
    private val badgeType: ComponentType = ComponentType("doc.c_badge")
    private val pageId: PageId = PageId("p_home")
    private val labelRead: Expr = Expr.Ref(RefTarget.Param(ParamName("label")))

    /**
     * The declaration every test here instantiates: one required `label`, one `content` slot,
     * and a body whose `Text` reads the parameter — which is what pass 5 has to resolve against
     * the declaration's own scope, and what makes the second test here worth writing.
     */
    private fun declareBadge(scope: DocumentScope) {
        scope.component(
            id = badge,
            name = "Badge",
            params = listOf(ParamDecl(ParamName("label"), TypeRef.Str)),
            slots = listOf(SlotDecl(SlotName("content"))),
        ) {
            node(ColumnType, id = NodeId("n_badge")) {
                val heading = node(TextType, id = NodeId("n_badge_label")) { computed("text", labelRead) }
                slot("children", listOf(heading))
            }
        }
    }

    /** A document that declares whatever [declared] declares and hosts [body] as its page. */
    private fun documentUsing(
        declared: DocumentScope.() -> Unit = {},
        body: PageScope.() -> Unit,
    ): UiDocument = buildDocument("demo") {
        declared()
        page(name = "Home", route = "home", id = pageId, block = body)
    }

    @Test
    fun `an instance resolves against the spec its declaration synthesizes`() {
        val result = analyzer.analyze(
            documentUsing(declared = { declareBadge(this) }) {
                node(badgeType, id = NodeId("n_home")) {
                    prop("label", Value.Str("Live"))
                    slot("content", listOf(node(TextType, id = NodeId("n_body")) { prop("text", Value.Str("body")) }))
                }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val resolved = assertNotNull(result.resolved)
        val instance = assertNotNull(resolved.pages[pageId]).root
        assertEquals(badgeType, instance.type)
        val label = assertNotNull(instance.props[PropertyKey("label")])
        assertEquals(PropOrigin.Specified, label.origin)
        assertEquals(Value.Str("Live"), (label.value as PropertyValue.Const).value)
        val content = assertNotNull(instance.slots[SlotName("content")]).single()
        assertEquals(TextType, content.type)
        val body = assertNotNull(content.props[PropertyKey("text")])
        assertEquals(Value.Str("body"), (body.value as PropertyValue.Const).value)
    }

    @Test
    fun `the declaration body resolves with the parameter it declares`() {
        val result = analyzer.analyze(
            documentUsing(declared = { declareBadge(this) }) {
                node(badgeType, id = NodeId("n_home")) { prop("label", Value.Str("Live")) }
            },
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val body = assertNotNull(assertNotNull(result.resolved).components[badge])
        assertEquals(ColumnType, body.type)
        val heading = assertNotNull(body.slots[SlotName("children")]).single()
        // §10.4 resolved `@label` against the declaration's own params, which is what makes the
        // param a real input rather than a name the checker tolerates.
        val typed = assertNotNull(assertNotNull(heading.props[PropertyKey("text")]).typed)
        assertEquals(ExprType.Of(TypeRef.Str), typed.type)
        assertEquals(setOf<RefTarget>(RefTarget.Param(ParamName("label"))), typed.refs)
    }

    @Test
    fun `a mis-declared instance is reported precisely, and never as an unknown type`() {
        // Three independent faults on one instance: the required param is absent, a prop the
        // declaration does not declare is present, and a slot it does not expose is filled.
        val result = analyzer.analyze(
            documentUsing(declared = { declareBadge(this) }) {
                node(badgeType, id = NodeId("n_home")) {
                    prop("tone", Value.Str("loud"))
                    slot("footer", listOf(node(TextType, id = NodeId("n_body")) { prop("text", Value.Str("body")) }))
                }
            },
        )
        val codes = result.diagnostics.map { it.code.value }

        assertTrue(result.hasErrors)
        assertNull(result.resolved)
        assertEquals(
            setOf(
                DiagnosticCodes.PropRequiredMissing.value,
                DiagnosticCodes.PropUnknown.value,
                DiagnosticCodes.ComponentSlotUnknown.value,
            ),
            codes.toSet(),
        )
        assertTrue(codes.none { it == DiagnosticCodes.ComponentUnknown.value }, "got $codes")
    }

    @Test
    fun `one document's components never reach another's analysis`() {
        val declaring = documentUsing(declared = { declareBadge(this) }) {
            node(badgeType, id = NodeId("n_home")) { prop("label", Value.Str("Live")) }
        }
        val borrowing = documentUsing {
            node(badgeType, id = NodeId("n_home")) { prop("label", Value.Str("Live")) }
        }

        assertTrue(analyzer.analyze(declaring).diagnostics.isEmpty())
        val result = analyzer.analyze(borrowing)

        // Same analyzer, same static schema, one call apart: the overlay is per-`analyze`, so
        // the second document is back to `component.unknown` rather than inheriting a `doc.*`
        // that would compile in this build and fail in another.
        assertEquals(listOf(DiagnosticCodes.ComponentUnknown.value), result.diagnostics.map { it.code.value })
        assertNull(result.resolved)
    }
}
