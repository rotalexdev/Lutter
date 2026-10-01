package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Construction refuses a schema with an unwired component, naming it. */
class UiRuntimeTest {

    private val column: ComponentType = ComponentType("core.Column")
    private val text: ComponentType = ComponentType("m3.Text")

    @Test
    fun `full coverage constructs`() {
        val runtime = builds(column, text)

        assertTrue(column in runtime.renderers)
        assertTrue(text in runtime.renderers)
    }

    @Test
    fun `a spec without a renderer fails naming the type`() {
        val failure = assertFailsWith<IllegalStateException> {
            builds(text)
        }

        assertTrue(failure.message?.contains("core.Column") == true)
    }

    private fun builds(vararg wired: ComponentType): UiRuntime {
        val renderers = RendererRegistryBuilder()
            .apply { for (type in wired) register(type, StubRenderer) }
            .build()
        val schema: Schema<ComponentSpec, ModifierSpec, String, String, String> =
            Schema.build {
                component(stub(column))
                component(stub(text))
            }
        return UiRuntime(renderers, ModifierApplierRegistryBuilder().build(), Implementations.None, schema)
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
