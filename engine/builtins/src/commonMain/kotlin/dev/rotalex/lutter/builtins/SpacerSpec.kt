package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `core.Spacer` spec: empty space, sized entirely through modifiers.
 *
 * No properties and no children, because Compose's `Spacer` takes nothing but a modifier and
 * `layout.width` / `layout.height` already say how much space a document is asking for.
 */
public object SpacerSpec {
    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.Spacer"), version = 1) {
        metadata(displayName = "Spacer", category = Category.Layout)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Spacer"))
    }
}
