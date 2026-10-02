package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.modifier.modifier
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.schema.types.type

/**
 * The schema half of the plugin pair: the skeleton components and the §31.2 modifier set.
 *
 * Two builders rather than one, because the modifier registry is typed: a schema that binds
 * its components to a stub cannot also bind modifiers to [ModifierSpec]. The runtime halves
 * are `registerBuiltinRenderers` and `registerBuiltinModifierAppliers` in
 * `:engine:builtins-compose`.
 */
public fun <M : Any, A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, M, A, F, T>.registerBuiltinSpecs(): Unit {
    component(ColumnSpec.spec)
    component(RowSpec.spec)
    component(BoxSpec.spec)
    component(SpacerSpec.spec)
    component(TextSpec.spec)
    component(ButtonSpec.spec)
    component(TextFieldSpec.spec)
    component(CardSpec.spec)
}

/** Registers every builtin modifier. The applier half is `registerBuiltinModifierAppliers`. */
public fun <A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, ModifierSpec, A, F, T>.registerBuiltinModifiers(): Unit {
    for (spec in LayoutModifiers.all) modifier(spec)
    for (spec in DecorationModifiers.all) modifier(spec)
    for (spec in InteractionModifiers.all) modifier(spec)
}

/** Registers the enum types the modifier properties name. */
public fun <C : Any, M : Any, A : Any, F : Any> SchemaBuilder<C, M, A, F, TypeSpec>.registerBuiltinEnums(): Unit {
    type(AlignmentSpec.spec)
    type(ShapeSpec.spec)
}
