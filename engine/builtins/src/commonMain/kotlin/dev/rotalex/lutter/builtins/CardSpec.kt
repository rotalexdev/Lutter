package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `m3.Card` spec: a themed surface around a body, with no property of its own.
 *
 * Shape, colour and elevation stay with the theme and the modifier set; naming any of them here
 * would be a second path to one decision.
 */
public object CardSpec {
    /** The body. `Card` puts a `ColumnScope` in that lambda and nothing registered gates on it. */
    public val content: SlotSpec = SlotSpec(SlotName("content"), Cardinality.Many)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Card"), version = 1) {
        metadata(displayName = "Card", category = Category.Basic)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Card")) {
            slot("content", LambdaTarget.Trailing)
        }
    }
}
