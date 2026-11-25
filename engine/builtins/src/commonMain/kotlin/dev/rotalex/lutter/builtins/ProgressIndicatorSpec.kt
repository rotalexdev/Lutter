package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `m3.ProgressIndicator` spec: an indeterminate linear progress bar.
 *
 * No properties in the wave-2 skeleton — the call resolves to Material 3's indeterminate
 * overload, and a document that wants a determinate value will get a bound `progress`
 * property when wave 2 wires state to it. Size and placement stay on the modifiers.
 */
public object ProgressIndicatorSpec {
    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.ProgressIndicator"), version = 1) {
        metadata(displayName = "Progress Indicator", category = Category.Basic)
        composeCall(KotlinSymbol("androidx.compose.material3", "LinearProgressIndicator"))
    }
}
