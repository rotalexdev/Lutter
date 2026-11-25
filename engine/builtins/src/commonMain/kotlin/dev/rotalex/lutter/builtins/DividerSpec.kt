package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `m3.Divider` spec: a thin horizontal separator line.
 *
 * No properties and no children for the wave-2 skeleton — Compose's divider takes a modifier
 * plus optional thickness/color, and the document expresses size through layout modifiers.
 * The emitted symbol is `HorizontalDivider` because Material 3 renamed the call; the component
 * type keeps the plan's `m3.Divider` name.
 */
public object DividerSpec {
    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Divider"), version = 1) {
        metadata(displayName = "Divider", category = Category.Layout)
        composeCall(KotlinSymbol("androidx.compose.material3", "HorizontalDivider"))
    }
}
