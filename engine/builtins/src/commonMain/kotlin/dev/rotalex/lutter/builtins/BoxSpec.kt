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
 * The `core.Box` spec: one stacking scope and nothing else.
 *
 * No `contentAlignment`: `layout.align` places each child against the box scope this spec
 * provides, so an alignment carried on the box itself would be a second path to one placement.
 */
public object BoxSpec {
    /** The children, providing the box scope the two-axis form of `layout.align` reads. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Box),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.Box"), version = 1) {
        metadata(displayName = "Box", category = Category.Layout)
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Box")) {
            slot("children", LambdaTarget.Trailing)
        }
    }
}
