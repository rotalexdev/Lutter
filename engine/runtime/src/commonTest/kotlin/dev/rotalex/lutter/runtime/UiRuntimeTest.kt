package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.interpreter.eval.FunctionImpls
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Construction refuses a schema with an unwired component, naming it. */
class UiRuntimeTest {

    private val column: ComponentType = ComponentType("core.Column")
    private val text: ComponentType = ComponentType("m3.Text")
    private val UpperId: FunctionId = FunctionId("str.upper")

    @Test
    fun `full coverage constructs`() {
        val runtime = builds(listOf(column, text))

        assertTrue(column in runtime.renderers)
        assertTrue(text in runtime.renderers)
    }

    @Test
    fun `a spec without a renderer fails naming the type`() {
        val failure = assertFailsWith<IllegalStateException> {
            builds(listOf(text))
        }

        assertTrue(failure.message?.contains("core.Column") == true)
    }

    @Test
    fun `the evaluator dispatches to the functions Implementations carries`() {
        val implementations = Implementations(
            FunctionImpls.of(listOf(UpperId to FunctionImpl { args -> Value.Str("!" + (args[0] as Value.Str).v) })),
        )
        val runtime = builds(listOf(column, text), implementations)

        val evaluated = runtime.evaluator.eval(upper("Hi"), MapEvalScope(MapStateStore()))

        assertEquals(Value.Str("!Hi"), evaluated)
    }

    @Test
    fun `an evaluator with no implementations refuses a call`() {
        val runtime = builds(listOf(column, text))

        val failure = assertFailsWith<IllegalStateException> {
            runtime.evaluator.eval(upper("Hi"), MapEvalScope(MapStateStore()))
        }

        assertTrue(failure.message?.contains("str.upper") == true, failure.message)
    }

    /** `str.upper("Hi")` as pass 5 lowers it. */
    private fun upper(argument: String): TypedExpr {
        val body = Expr.Call(UpperId, listOf(Expr.Const(Value.Str(argument))))
        return TypedExpr(body, ExprType.Of(TypeRef.Str), emptySet())
    }

    private fun builds(
        wired: List<ComponentType>,
        implementations: Implementations = Implementations.None,
    ): UiRuntime {
        val renderers = RendererRegistryBuilder()
            .apply { for (type in wired) register(type, StubRenderer) }
            .build()
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build {
                component(stub(column))
                component(stub(text))
            }
        return UiRuntime(renderers, ModifierApplierRegistryBuilder().build(), implementations, schema)
    }

    private fun stub(type: ComponentType): ComponentSpec =
        componentSpec(type, version = 1) {
            metadata("Stub", Category.Basic)
            intrinsic()
        }

    private object StubRenderer : ComponentRenderer {
        @Composable
        override fun Render(node: ResolvedNode, scope: RenderScope): Unit = Unit
    }
}
