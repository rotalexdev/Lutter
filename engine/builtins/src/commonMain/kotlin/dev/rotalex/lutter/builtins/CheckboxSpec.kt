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
 * The `m3.Checkbox` spec, the wave-2 selection shape: `checked` renders, the change is an event.
 *
 * The state follows [TextFieldSpec]'s split: a property the analyzer typechecks (a document
 * binds it to a `Bool` state or writes a constant) and an event the handler dispatches with the
 * new value as its one argument. Material's `onCheckedChange: ((Boolean) -> Unit)?` is nullable,
 * but the generator never drops the parameter: an event with no handler emits an empty lambda
 * (§11's every-event-emitted rule), so the checkbox stays enabled and merely ignores taps
 * instead of rendering Compose's disabled variant — binding the event is the user's steer.
 */
public object CheckboxSpec {
    /** Whether the box is checked. Required: a selection control without a state renders nothing meaningful. */
    public val checked: PropertySpec<Boolean> = prop("checked", TypeRef.Bool, required = true)

    /** The change, carrying the new state as its one handler argument. */
    public val onCheckedChange: EventSpec = EventSpec(
        EventKey("onCheckedChange"),
        listOf(EventArgSpec("checked", TypeRef.Bool)),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Checkbox"), version = 1) {
        metadata(displayName = "Checkbox", category = Category.Input)
        property(checked)
        event(onCheckedChange)
        composeCall(KotlinSymbol("androidx.compose.material3", "Checkbox")) {
            param("checked", from = checked)
            event(onCheckedChange, param = "onCheckedChange", lambdaParams = listOf("checked"))
        }
    }
}
