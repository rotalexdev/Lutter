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
 * `onClick` is declared but not bound: PLAN §30 carries the handler in the document's
 * `events`, and `CodegenCoverage` refuses a binding carrying events until Phase 7 emits them.
 */
public object ButtonSpec {
    /** The label. Exactly one, per PLAN §30.4; the row scope is what `Button` puts there. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.ExactlyOne,
        provides = setOf(LayoutScopes.Row),
    )

    /** The press. Declared so the name is a spec fact; no binding or renderer reads it. */
    public val onClick: EventSpec = EventSpec(EventKey("onClick"))

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Button"), version = 1) {
        metadata(displayName = "Button", category = Category.Basic)
        event(onClick)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Button")) {
            slot("content", LambdaTarget.Trailing)
        }
    }
}
