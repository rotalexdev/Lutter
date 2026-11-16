package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.Dialog` spec, the wave-3 declarative window: content shows while `visible` holds.
 *
 * The whole call renders behind the guard — Compose spells it as `if (visible) { Dialog(...) }`,
 * because Material's `Dialog` takes no visibility parameter: a window is either composed or not.
 * The generator wraps the guarded call in the `if`; a page root composes unconditionally, since
 * a document never makes its window the screen. No modifier: Compose's `Dialog` has none, and
 * the renderer places nothing beside it.
 */
public object DialogSpec {
    /** Whether the dialog is open. Required: a window without a state is an unconditional window. */
    public val visible: PropertySpec<Boolean> = prop("visible", TypeRef.Bool, required = true)

    /** The body, in no receiver lambda: `Dialog`'s content stacks like `Surface`'s does. */
    public val content: SlotSpec = SlotSpec(SlotName("content"), Cardinality.Many)

    /** The dismissal, outside taps and back included; optional, an unbound one runs nothing. */
    public val onDismiss: EventSpec = EventSpec(EventKey("onDismiss"), emptyList())

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Dialog"), version = 1) {
        metadata(displayName = "Dialog", category = Category.Layout)
        property(visible)
        event(onDismiss)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Dialog"), modifierParam = null) {
            event(onDismiss, param = "onDismissRequest")
            slot("content", LambdaTarget.Trailing)
            guard(visible.key)
        }
    }
}
