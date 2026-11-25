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
 * The `m3.Slider` spec, the wave-2 input shape: `value` renders, the drag is an event.
 *
 * The same split [TextFieldSpec] makes — a typechecked `Float` property and an event carrying
 * the new value — with Material's signature `Slider(value: Float, onValueChange: (Float) -> Unit)`.
 * Material's `valueRange` keeps its default: a range pair has no `ValueEmit` arm yet, and the
 * wave's rule is to bind nothing the printer cannot spell.
 */
public object SliderSpec {
    /** The current position. Required: a slider without a value renders nothing meaningful. */
    public val value: PropertySpec<Float> = prop("value", TypeRef.Float32, required = true)

    /** The drag, carrying the new position as its one handler argument. */
    public val onValueChange: EventSpec = EventSpec(
        EventKey("onValueChange"),
        listOf(EventArgSpec("value", TypeRef.Float32)),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Slider"), version = 1) {
        metadata(displayName = "Slider", category = Category.Input)
        property(value)
        event(onValueChange)
        composeCall(KotlinSymbol("androidx.compose.material3", "Slider")) {
            param("value", from = value)
            event(onValueChange, param = "onValueChange", lambdaParams = listOf("value"))
        }
    }
}
