package dev.rotalex.lutter.runtime

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedModifier
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.action.ActionEnv
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope

/**
 * §5.7's instantiation, asserted at the seam that decides it.
 *
 * `DefaultRenderScope.expand` answers every question about the *document* — which declaration,
 * whether it exists, whether it is already open, what its params bind to — and it is not
 * `@Composable`, so all of it is reachable from `commonTest` with no Compose harness at all. The
 * drawing it feeds is asserted in `:integration:generated-compile`, where `runComposeUiTest`
 * already runs; nothing here claims to prove pixels.
 *
 * The nodes are built by hand for the reason `RenderScopeTest` builds its own: pass 7's output is
 * `ComponentOverlayTest`'s, and restating a schema this module may not depend on would test the
 * fixture rather than the runtime.
 */
class ComponentInstanceTest {

    private val decl: ComponentDeclId = ComponentDeclId("c_badge")
    private val badge: ComponentType = ComponentType("doc.c_badge")
    private val column: ComponentType = ComponentType("core.Column")
    private val text: PropertySpec<String> = prop("text", TypeRef.Str, required = true)
    private val padded: ModifierType = ModifierType("layout.padding")

    private val children: SlotName = SlotName("children")
    private val content: SlotName = SlotName("content")
    private val label: PropertyKey = PropertyKey("label")
    private val labelName: ParamName = ParamName("label")

    /** What `environment.diagnostics` collected, in the order the render reported it. */
    private val reported: MutableList<RuntimeDiagnostic> = mutableListOf()

    @Test
    fun `an instance expands to its declaration body with the instance's params bound`() {
        val expanded = assertNotNull(scope().expand(instance()))

        // The body is the declaration's, not the call site's: §5.5 gives an instance no body of
        // its own, which is why a `doc.*` type needs no renderer.
        assertEquals(column, expanded.root.type)
        // And the label the body reads is the one the instance carried. A scope that forgot to
        // bind would resolve `@label` against the page's params and throw over there instead.
        val heading = expanded.root.slots.getValue(children).single()
        assertEquals("Live", expanded.scope.props(heading)[text])
    }

    @Test
    fun `the outlet reaches the instance, and the instance holds the filled slot`() {
        val filled = leaf("n_body", "body")
        val expanded = assertNotNull(scope().expand(instance(slots = mapOf(content to listOf(filled)))))

        // Null in a page's own tree, which is what keeps an outlet written outside a component
        // body from drawing whatever the page happens to hold.
        assertNull(scope().instance)
        assertSame(filled, expanded.scope.instance?.slots?.get(content)?.single())
    }

    @Test
    fun `a component that instantiates itself reports and expands to nothing`() {
        // Pass 1's cycle check walks nodes and `n_recursion` is not a descendant of `n_outer`: a
        // declaration's body is a separate tree that the tree instantiating it merely names, so
        // the document is structurally clean and the expansion would never end.
        val recursing = instance(id = NodeId("n_recursion"))
        val scope = scope(declarations = mapOf(decl to columnOf("n_decl_root", listOf(recursing))))
        val outer = assertNotNull(scope.expand(instance(id = NodeId("n_outer"))))

        assertNull(outer.scope.expand(recursing))
        assertEquals(1, reported.size)
        assertEquals(NodeId("n_recursion"), reported.single().nodeId)
        assertTrue(
            reported.single().message.contains("instantiates itself"),
            reported.single().message,
        )
    }

    @Test
    fun `an instance whose declaration the document does not carry reports`() {
        val ghost = instance(id = NodeId("n_ghost"), type = ComponentType("doc.c_ghost"))

        assertNull(scope().expand(ghost))
        assertTrue(reported.single().message.contains("c_ghost"), reported.single().message)
    }

    @Test
    fun `a type that names no document component reports rather than drawing nothing`() {
        // What `RenderNode` hands over on a registry miss, so this is the answer for every miss
        // that is not an instance — the case a blank column used to hide.
        assertNull(scope().expand(leaf("n_leaf", "hello")))
        assertEquals(
            "No renderer for 'm3.Text' and it names no document component",
            reported.single().message,
        )
    }

    @Test
    fun `the instance's modifier lands on the declaration root and nowhere else`() {
        val applier = Padding()
        val node = instance(modifiers = listOf(ResolvedModifier(padded, emptyMap())))
        val expanded = assertNotNull(scope(applier = applier).expand(node))

        // §5.5 makes composition the whole of a document component, so an instance carries a
        // modifier where a wrapping call would take one. It belongs on the body's root, because
        // that is the node a caller would have wrapped had the component not existed.
        assertNotSame(Modifier, expanded.scope.modifierFor(expanded.root))
        val heading = expanded.root.slots.getValue(children).single()
        assertSame(Modifier, expanded.scope.modifierFor(heading))
        assertEquals(1, applier.applied)
    }

    @Test
    fun `a body reading a param the instance did not bind refuses rather than reading the page's`() {
        // §5.5 makes a declaration's params its whole input surface. Without `InstanceScope` in
        // front, this read lands on `ScreenEvalScope`, which holds the page's params and would
        // answer with whatever the page happened to name the same thing.
        val scope = InstanceScope(
            MapEvalScope(MapStateStore(), mapOf(labelName to Value.Str("page"))),
            mapOf(ParamName("title") to Value.Str("instance")),
        )

        assertEquals(Value.Str("instance"), scope.read(RefTarget.Param(ParamName("title"))))
        val failure = assertFailsWith<IllegalStateException> { scope.read(RefTarget.Param(labelName)) }
        assertTrue(failure.message?.contains("whole input surface") == true, failure.message)
    }

    // -------------------------------------------------------------------------------------
    // Nodes
    // -------------------------------------------------------------------------------------

    /** `m3.Text` reading its text from the declaration's `label` param, as pass 7 lowers it. */
    private fun heading(id: String): ResolvedNode {
        val reference = Expr.Ref(RefTarget.Param(labelName))
        val typed = TypedExpr(reference, ExprType.Of(TypeRef.Str), setOf(reference.target))
        return ResolvedNode(
            id = NodeId(id),
            type = ComponentType("m3.Text"),
            props = mapOf(
                text.key to ResolvedProp(
                    text.key,
                    PropertyValue.Computed(reference),
                    PropOrigin.Specified,
                    null,
                    typed,
                ),
            ),
            modifiers = emptyList(),
            slots = emptyMap(),
            scopes = emptySet(),
        )
    }

    /** A `m3.Text` carrying a literal, which is what a slot's content is made of. */
    private fun leaf(id: String, body: String): ResolvedNode = ResolvedNode(
        id = NodeId(id),
        type = ComponentType("m3.Text"),
        props = mapOf(text.key to constant(text.key, Value.Str(body))),
        modifiers = emptyList(),
        slots = emptyMap(),
        scopes = emptySet(),
    )

    /** A `core.Column` wrapping [kids] — the declaration body both tests share. */
    private fun columnOf(id: String, kids: List<ResolvedNode>): ResolvedNode = ResolvedNode(
        id = NodeId(id),
        type = column,
        props = emptyMap(),
        modifiers = emptyList(),
        slots = mapOf(children to kids),
        scopes = emptySet(),
    )

    /** `doc.c_badge` as a document writes it: its props are the declaration's params. */
    private fun instance(
        id: NodeId = NodeId("n_instance"),
        type: ComponentType = badge,
        props: Map<PropertyKey, ResolvedProp> = mapOf(label to constant(label, Value.Str("Live"))),
        slots: Map<SlotName, List<ResolvedNode>> = emptyMap(),
        modifiers: List<ResolvedModifier> = emptyList(),
    ): ResolvedNode = ResolvedNode(
        id = id,
        type = type,
        props = props,
        modifiers = modifiers,
        slots = slots,
        scopes = emptySet(),
    )

    private fun constant(key: PropertyKey, value: Value): ResolvedProp =
        ResolvedProp(key, PropertyValue.Const(value), PropOrigin.Specified, null)

    // -------------------------------------------------------------------------------------
    // Scope
    // -------------------------------------------------------------------------------------

    /**
     * A screen's scope: the same one `UiScreen` builds, with [declarations] handed over.
     *
     * Positional because `DefaultRenderScope` puts `declarations` after `actionEnv`, so a trailing
     * lambda is no longer available and named arguments are not either — the last eight are the
     * constructor's, and this test is where they are read.
     */
    private fun scope(
        declarations: Map<ComponentDeclId, ResolvedNode> =
            mapOf(decl to columnOf("n_decl_root", listOf(heading("n_heading")))),
        applier: ModifierApplier? = null,
    ): DefaultRenderScope {
        val modifiers = ModifierApplierRegistryBuilder().apply {
            if (applier != null) register(padded, applier)
        }.build()
        val runtime = UiRuntime(
            RendererRegistryBuilder().build(),
            modifiers,
            Implementations.None,
            Schema.build<ComponentSpec, ModifierSpec, String, String, String> {},
        )
        val environment = RuntimeEnvironment(StubNavigator, diagnostics = { finding -> reported += finding })
        val actions: (Map<String, Value>) -> ActionEnv = { args ->
            val store = MapStateStore()
            ScreenActionEnv(MapEvalScope(store), store, environment, args)
        }
        return DefaultRenderScope(
            environment,
            DefaultThemeHandle(ResolvedTheme(null)),
            runtime,
            null,
            ExpressionSource(Evaluator(), MapEvalScope(MapStateStore())),
            NoScope,
            actions,
            declarations,
        )
    }

    private class Padding : ModifierApplier {
        var applied: Int = 0
        override fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier {
            applied += 1
            return modifier.then(Modifier.padding(1.dp))
        }
    }

    private object StubNavigator : Navigator {
        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }

    /** Nothing to join: no test here dispatches a handler. */
    private class NoScope : CoroutineScope {
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
    }
}