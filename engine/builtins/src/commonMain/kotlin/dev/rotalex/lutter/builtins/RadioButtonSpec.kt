package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventBinding
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.RadioButton` spec, the wave-2 selection shape: `selected` renders, the press is an event.
 *
 * The state follows [CheckboxSpec]'s split with one difference: Material's radio press hands its
 * handler no argument (`onClick: (() -> Unit)?`), because a radio's new state is not the press's
 * news — the group decides which button is selected. The event therefore carries no args, the
 * same shape [ButtonSpec] binds, and the renderer's dispatch has nothing to pass.
 */
public object RadioButtonSpec {
    /** Whether this button is the one selected. Required: a radio without a state renders nothing meaningful. */
    public val selected: PropertySpec<Boolean> = prop("selected", TypeRef.Bool, required = true)

    /** The press. No argument: the selected radio is the group's decision, not the press's. */
    public val onClick: EventSpec = EventSpec(EventKey("onClick"), emptyList())

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.RadioButton"), version = 1) {
        metadata(displayName = "RadioButton", category = Category.Input)
        property(selected)
        event(onClick)
        composeCall(KotlinSymbol("androidx.compose.material3", "RadioButton")) {
            param("selected", from = selected)
            event(onClick, param = "onClick")
        }
    }
}
