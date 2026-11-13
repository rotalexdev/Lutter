package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.BoxSpec
import dev.rotalex.lutter.builtins.BoxWithConstraintsSpec
import dev.rotalex.lutter.builtins.ButtonSpec
import dev.rotalex.lutter.builtins.CardSpec
import dev.rotalex.lutter.builtins.CheckboxSpec
import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.builtins.DividerSpec
import dev.rotalex.lutter.builtins.FlowColumnSpec
import dev.rotalex.lutter.builtins.FlowRowSpec
import dev.rotalex.lutter.builtins.IconSpec
import dev.rotalex.lutter.builtins.ImageSpec
import dev.rotalex.lutter.builtins.LazyColumnSpec
import dev.rotalex.lutter.builtins.LazyRowSpec
import dev.rotalex.lutter.builtins.ProgressIndicatorSpec
import dev.rotalex.lutter.builtins.RadioButtonSpec
import dev.rotalex.lutter.builtins.RowSpec
import dev.rotalex.lutter.builtins.SliderSpec
import dev.rotalex.lutter.builtins.SlotOutletSpec
import dev.rotalex.lutter.builtins.SpacerSpec
import dev.rotalex.lutter.builtins.SurfaceSpec
import dev.rotalex.lutter.builtins.SwitchSpec
import dev.rotalex.lutter.builtins.TextFieldSpec
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        assertNotNull(registry[DividerSpec.spec.type])
        assertNotNull(registry[ProgressIndicatorSpec.spec.type])
        assertNotNull(registry[IconSpec.spec.type])
        assertNotNull(registry[TextSpec.spec.type])
        assertNotNull(registry[ButtonSpec.spec.type])
        assertNotNull(registry[TextFieldSpec.spec.type])
        assertNotNull(registry[CheckboxSpec.spec.type])
        assertNotNull(registry[SwitchSpec.spec.type])
        assertNotNull(registry[RadioButtonSpec.spec.type])
        assertNotNull(registry[SliderSpec.spec.type])
        assertNotNull(registry[ImageSpec.spec.type])
        assertNotNull(registry[FlowRowSpec.spec.type])
        assertNotNull(registry[FlowColumnSpec.spec.type])
        // The iteration mechanism's own unit wired them: a lazy layout renders like any other
        // builtin, and its slot's children iterate because the spec says so.
        assertNotNull(registry[LazyColumnSpec.spec.type])
        assertNotNull(registry[LazyRowSpec.spec.type])
        assertNotNull(registry[CardSpec.spec.type])
        // An intrinsic is a schema component like any other: `UiRuntime`'s coverage check walks
        // the component registry, so an outlet without a renderer stops every runtime built over
        // the pack rather than only failing where a document happens to use one.
        assertNotNull(registry[SlotOutletSpec.spec.type])
        assertNotNull(registry[SurfaceSpec.spec.type])
        assertNotNull(registry[BoxWithConstraintsSpec.spec.type])
        // Pinned so a spec registered without its renderer fails here rather than at coverage.
        assertEquals(23, registry.types().size)
    }
}
