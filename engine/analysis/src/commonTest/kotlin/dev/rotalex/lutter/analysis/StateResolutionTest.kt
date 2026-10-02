package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §12.1's declarations reach the resolved tree, each attached to the list the document put it in,
 * and a derived body's names are resolved before anything renders.
 */
class StateResolutionTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val home: PageId = PageId("p_home")
    private val card: ComponentDeclId = ComponentDeclId("Card")

    private val count: StateId = StateId("s_count")
    private val doubled: StateId = StateId("s_doubled")

    private val heldCount: StateDecl = StateDecl(count, "count", TypeRef.Int32, initial = Value.Int32(3))
    private val heldTheme: StateDecl = StateDecl(StateId("s_theme"), "theme", TypeRef.Str, Value.Str("light"))
    private val doubledNumber: StateDecl = doubling(count)
    private val doubledText: StateDecl = doubling(count, type = TypeRef.Str)
    private val doublingGhost: StateDecl = doubling(StateId("s_ghost"))
    private val doublingPageState: StateDecl = doubling(count, id = StateId("s_local"))

    @Test
    fun `app state lands on the document`() {
        val resolved = resolvedOf(document(appState = listOf(heldTheme)))

        assertEquals(listOf(heldTheme.id), resolved.appState.map { it.decl.id })
    }

    @Test
    fun `page state lands on the page`() {
        val resolved = resolvedOf(document(pageState = listOf(heldCount)))

        assertEquals(listOf(heldCount.id), resolved.pages.getValue(home).state.map { it.decl.id })
    }

    @Test
    fun `component state lands under its declaration`() {
        val resolved = resolvedOf(document(componentState = listOf(heldTheme)))

        assertEquals(listOf(heldTheme.id), resolved.componentState.getValue(card).map { it.decl.id })
    }

    @Test
    fun `a held declaration carries no expression`() {
        val resolved = resolvedOf(document(pageState = listOf(heldCount)))

        assertNull(resolved.pages.getValue(home).state.single().derived)
    }

    @Test
    fun `a derived declaration carries the checked body and the names it resolved`() {
        val resolved = resolvedOf(document(pageState = listOf(heldCount, doubledNumber)))

        val state: ResolvedState = resolved.pages.getValue(home).state.single { it.decl.id == doubled }
        val body = assertNotNull(state.derived)
        assertEquals(ExprType.Of(TypeRef.Int32), body.type)
        assertEquals(setOf(RefTarget.State(count)), body.refs)
    }

    @Test
    fun `a derived body naming an undeclared state is refused, naming the page`() {
        val result = analyzer.analyze(document(pageState = listOf(doublingGhost)))

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprUnresolvedRef }
        assertNull(result.resolved)
        assertTrue(finding.message.contains("s_ghost"), finding.message)
        assertTrue(finding.message.contains("p_home"), finding.message)
    }

    @Test
    fun `a derived body whose declared type is wrong is refused`() {
        val result = analyzer.analyze(document(pageState = listOf(heldCount, doubledText)))

        assertNull(result.resolved)
        val codes = result.diagnostics.map { it.code }
        assertTrue(DiagnosticCodes.ExprTypeMismatch.value in codes, codes.toString())
    }

    @Test
    fun `a component body does not see the state of the page that renders it`() {
        val page = listOf(heldCount)

        val result = analyzer.analyze(document(pageState = page, componentState = listOf(doublingPageState)))

        assertNull(result.resolved)
        val finding = result.diagnostics.single { it.code == DiagnosticCodes.ExprUnresolvedRef }
        assertTrue(finding.message.contains("s_count"), finding.message)
        assertEquals(card, finding.location.componentDeclId, finding.message)
    }

    /** A derived [id] whose body is `reads + 1`, declared as [type]. */
    private fun doubling(reads: StateId, id: StateId = doubled, type: TypeRef = TypeRef.Int32): StateDecl = StateDecl(
        id = id,
        name = "doubled",
        type = type,
        derived = Expr.Binary(
            BinaryOp.Add,
            Expr.Ref(RefTarget.State(reads)),
            Expr.Const(Value.Int32(1)),
        ),
    )

    private fun document(
        appState: List<StateDecl> = emptyList(),
        pageState: List<StateDecl> = emptyList(),
        componentState: List<StateDecl> = emptyList(),
    ): UiDocument = buildDocument("demo") {
        page(name = "Home", route = "home", id = home, state = pageState) {
            node(TextType) { prop("text", Value.Str("Hi")) }
        }
        component(id = card, name = "Card", state = componentState) {
            node(TextType) { prop("text", Value.Str("Card")) }
        }
    }.copy(appState = appState)

    private fun resolvedOf(document: UiDocument): ResolvedDocument =
        assertNotNull(analyzer.analyze(document).resolved)
}
