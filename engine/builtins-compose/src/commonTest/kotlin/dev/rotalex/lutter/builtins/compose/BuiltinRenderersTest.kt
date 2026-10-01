package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.BoxSpec
import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.builtins.RowSpec
import dev.rotalex.lutter.builtins.SpacerSpec
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Wiring only: every renderer registered, misses still null. No composition harness yet. */
class BuiltinRenderersTest {

    @Test
    fun `the runtime pair wires every builtin renderer`() {
        val registry = RendererRegistryBuilder()
            .apply { registerBuiltinRenderers() }
            .build()

        assertNotNull(registry[ColumnSpec.spec.type])
        assertNotNull(registry[RowSpec.spec.type])
        assertNotNull(registry[BoxSpec.spec.type])
        assertNotNull(registry[SpacerSpec.spec.type])
        assertNotNull(registry[TextSpec.spec.type])
        // Pinned so a spec registered without its renderer fails here rather than at coverage.
        assertEquals(5, registry.types().size)
    }

    @Test
    fun `outside the pair nothing resolves`() {
        val registry = RendererRegistryBuilder()
            .apply { registerBuiltinRenderers() }
            .build()

        assertNull(registry[ComponentType("core.FlowRow")])
    }
}
