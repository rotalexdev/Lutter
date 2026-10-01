package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Wiring only: both renderers registered, misses still null. No composition harness yet. */
class BuiltinRenderersTest {

    @Test
    fun `the runtime pair wires column and text`() {
        val registry = RendererRegistryBuilder()
            .apply { registerBuiltinRenderers() }
            .build()

        assertNotNull(registry[ColumnSpec.spec.type])
        assertNotNull(registry[TextSpec.spec.type])
    }

    @Test
    fun `outside the pair nothing resolves`() {
        val registry = RendererRegistryBuilder()
            .apply { registerBuiltinRenderers() }
            .build()

        assertNull(registry[ComponentType("core.Row")])
    }
}
