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
 * The `core.BoxWithConstraints` spec: a box whose scope carries the incoming constraints.
 *
 * The stacking shape is [BoxSpec]'s — the same one slot, the same no-property rule — because
 * `layout.align` answers placement against this scope exactly as it does against a box's. What
 * the scope adds is the constraint half: the `compose.BoxWithConstraintsScope` handle names the
 * scope whose members (`minWidth`, `maxWidth`, and their friends) the wave-3 adaptive
 * expressions read, so the document can branch on the space a container was given rather than
 * on the space it took.
 */
public object BoxWithConstraintsSpec {
    /** The children, providing the constrained box scope the adaptive half reads. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.BoxWithConstraints),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.BoxWithConstraints"), version = 1) {
        metadata(displayName = "BoxWithConstraints", category = Category.Layout)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "BoxWithConstraints")) {
            slot("content", LambdaTarget.Trailing)
        }
    }
}
