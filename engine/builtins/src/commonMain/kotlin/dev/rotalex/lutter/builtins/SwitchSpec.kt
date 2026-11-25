package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventArgSpec
import dev.rotalex.lutter.schema.component.EventBinding
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.Switch` spec, the wave-2 selection shape: `checked` renders, the change is an event.
 *
 * The same split [CheckboxSpec] makes — a typechecked property plus an event carrying the new
 * state — because Material's `Switch` has the identical signature: `checked: Boolean` and the
 * nullable `onCheckedChange: ((Boolean) -> Unit)?` whose absence renders the disabled track.
 */
public object SwitchSpec {
    /** Whether the switch is on. Required: a switch without a state renders nothing meaningful. */
    public val checked: PropertySpec<Boolean> = prop("checked", TypeRef.Bool, required = true)

    /** The change, carrying the new state as its one handler argument. */
    public val onCheckedChange: EventSpec = EventSpec(
        EventKey("onCheckedChange"),
        listOf(EventArgSpec("checked", TypeRef.Bool)),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Switch"), version = 1) {
        metadata(displayName = "Switch", category = Category.Input)
        property(checked)
        event(onCheckedChange)
        composeCall(KotlinSymbol("androidx.compose.material3", "Switch")) {
            param("checked", from = checked)
            event(onCheckedChange, param = "onCheckedChange", lambdaParams = listOf("checked"))
        }
    }
}
