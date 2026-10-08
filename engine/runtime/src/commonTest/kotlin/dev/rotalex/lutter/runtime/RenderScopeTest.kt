package dev.rotalex.lutter.runtime

import androidx.compose.ui.Modifier
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Reads decode through the kind table; absent is null; constants resolve before evaluation. */
class RenderScopeTest {

    private val count: StateId = StateId("s_count")

    private val text: PropertySpec<String> = prop("text", TypeRef.Str, required = true)
    private val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))
    private val color: PropertySpec<ColorArgb?> = prop("color", TypeRef.Nullable(TypeRef.Color))
    private val style: PropertySpec<TokenName?> =
        prop("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography)))

    @Test
    fun `typed reads decode their values`() {
        val reader = reader(
            constOf(text.key, Value.Str("Hi")),
            constOf(spacing.key, Value.Dp(8f)),
            constOf(color.key, Value.Color(ColorArgb.parse("#FF6200EE"))),
            constOf(style.key, Value.Token(TokenKind.Typography, "headlineMedium")),
        )

        assertEquals("Hi", reader[text])
        assertEquals(8f, reader[spacing])
        assertEquals(ColorArgb.parse("#FF6200EE"), reader[color])
        assertEquals(TokenName("headlineMedium"), reader[style])
    }

    @Test
    fun `an absent optional reads as null`() {
        val reader = reader(constOf(text.key, Value.Str("Hi")))

        assertNull(reader[color])
        assertNull(reader[spacing])
    }

    @Test
    fun `a computed constant resolves without the evaluator`() {
        val reader = reader(
            ResolvedProp(
                text.key,
                PropertyValue.Computed(Expr.Const(Value.Str("Hi"))),
                PropOrigin.Specified,
                null,
            ),
        )

        assertEquals("Hi", reader[text])
    }

    @Test
    fun `a computed property runs the evaluator against the scope`() {
        val store: MapStateStore = MapStateStore(mapOf(count to Value.Str("Hi")))

        val reader = reader(textFromState(), scope = ExpressionSource(Evaluator(), MapEvalScope(store)))

        assertEquals("Hi", reader[text])
    }

    @Test
    fun `a write through the store is what the next read answers`() {
        val store: MapStateStore = MapStateStore(mapOf(count to Value.Str("Hi")))
        val reader = reader(textFromState(), scope = ExpressionSource(Evaluator(), MapEvalScope(store)))
        store.set(count, Value.Str("Bye"))

        assertEquals("Bye", reader[text])
    }

    @Test
    fun `a computed property with no checked expression refuses naming pass 5`() {
        val reader = reader(textFromState().copy(typed = null))

        val failure = assertFailsWith<IllegalStateException> { reader[text] }
        assertTrue(failure.message?.contains("pass 5") == true, failure.message)
    }

    @Test
    fun `a reader with no evaluation source names the modifier path`() {
        val reader = MapPropertyReader(mapOf(text.key to textFromState()), null)

        val failure = assertFailsWith<IllegalStateException> { reader[text] }
        assertTrue(failure.message?.contains("modifier argument") == true, failure.message)
    }

    @Test
    fun `no modifiers folds to the bare modifier`() {
        val scope = scope()
        val node = node()

        assertSame(Modifier, scope.modifierFor(node))
    }

    private fun reader(vararg props: ResolvedProp, scope: ExpressionSource = expressions()): PropertyReader =
        MapPropertyReader(props.associateBy { it.key }, scope)

    /** `text = <ref to s_count>` as pass 7 lowers it: a computed value and the expression behind it. */
    private fun textFromState(): ResolvedProp {
        val reference = Expr.Ref(RefTarget.State(count))
        val typed = TypedExpr(reference, ExprType.Of(TypeRef.Str), setOf(reference.target))
        return ResolvedProp(text.key, PropertyValue.Computed(reference), PropOrigin.Specified, null, typed)
    }

    private fun constOf(key: PropertyKey, value: Value): ResolvedProp =
        ResolvedProp(key, PropertyValue.Const(value), PropOrigin.Specified, null)

    private fun node(): ResolvedNode = ResolvedNode(
        id = NodeId("n_1"),
        type = ComponentType("m3.Text"),
        props = mapOf(text.key to constOf(text.key, Value.Str("Hi"))),
        modifiers = emptyList(),
        slots = emptyMap(),
        scopes = emptySet(),
    )

    private fun scope(): RenderScope {
        val empty = UiRuntime(
            RendererRegistryBuilder().build(),
            ModifierApplierRegistryBuilder().build(),
            Implementations.None,
            Schema.build<ComponentSpec, ModifierSpec, String, String, String> {},
        )
        return DefaultRenderScope(
            RuntimeEnvironment(StubNavigator),
            DefaultThemeHandle(ResolvedTheme(null)),
            empty,
            null,
            expressions(),
        ) { MapEvalScope(MapStateStore()) }
    }

    private fun expressions(): ExpressionSource =
        ExpressionSource(Evaluator(), MapEvalScope(MapStateStore()))

    private object StubNavigator : Navigator {
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}
