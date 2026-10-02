package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventArgSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.TextField` spec, PLAN §32's read-only scope: `value` renders, the edit does not.
 *
 * The edit is an event rather than a property because §11.1 puts a handler in `Node.events` as
 * an `ActionSequence` and §9.1 keeps lambdas out of `TypeRef`; a property spelling would need a
 * value kind the closed union does not have.
 */
public object TextFieldSpec {
    /** The text. Required, because a field with nothing in it has nothing to render. */
    public val value: PropertySpec<String> = prop("value", TypeRef.Str, required = true)

    /** The edit, carrying the new text as its one handler argument. */
    public val onValueChange: EventSpec = EventSpec(
        EventKey("onValueChange"),
        listOf(EventArgSpec("value", TypeRef.Str)),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.TextField"), version = 1) {
        metadata(displayName = "TextField", category = Category.Input)
        property(value)
        event(onValueChange)
        composeCall(KotlinSymbol("androidx.compose.material3", "TextField")) {
            param("value", from = value)
        }
    }
}
