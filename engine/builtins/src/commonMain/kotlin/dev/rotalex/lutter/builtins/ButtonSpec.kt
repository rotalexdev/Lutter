package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `m3.Button` spec: one label slot and one declared press, no properties of its own.
 *
 * `onClick` is bound because Compose declares `onClick` with no default, so a button whose
 * handler the document wrote has nowhere to put it and one whose document did not has a call
 * that does not compile.
 */
public object ButtonSpec {
    /** The label. Exactly one, per PLAN §30.4; the row scope is what `Button` puts there. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.ExactlyOne,
        provides = setOf(LayoutScopes.Row),
    )

    /** The press, which a document names in the node's events and the binding receives. */
    public val onClick: EventSpec = EventSpec(EventKey("onClick"))

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Button"), version = 1) {
        metadata(displayName = "Button", category = Category.Basic)
        event(onClick)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Button")) {
            event(onClick, "onClick")
            slot("content", LambdaTarget.Trailing)
        }
    }
}
