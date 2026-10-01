package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.ids.ComponentType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Registry misses return null; duplicates fail. No lookup ever throws. */
class RendererRegistryTest {

    @Test
    fun `an unknown type resolves to nothing, not a throw`() {
        val registry = RendererRegistryBuilder().build()

        assertNull(registry[ComponentType("core.Ghost")])
    }

    @Test
    fun `a registered renderer reads back out`() {
        val registry = RendererRegistryBuilder()
            .apply { register(ComponentType("m3.Text"), StubRenderer) }
            .build()

        assertSame(StubRenderer, registry[ComponentType("m3.Text")])
        assertTrue(ComponentType("m3.Text") in registry)
    }

    @Test
    fun `a second registration for one type fails naming it`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RendererRegistryBuilder().apply {
                register(ComponentType("m3.Text"), StubRenderer)
                register(ComponentType("m3.Text"), StubRenderer)
            }
        }

        assertTrue(failure.message?.contains("m3.Text") == true)
    }

    private object StubRenderer : ComponentRenderer {
        @Composable
        override fun Render(node: ResolvedNode, scope: RenderScope): Unit = Unit
    }
}
