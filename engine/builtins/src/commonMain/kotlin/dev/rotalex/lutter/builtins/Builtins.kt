package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.actions.FlowActions
import dev.rotalex.lutter.builtins.actions.HostActions
import dev.rotalex.lutter.builtins.actions.NavActions
import dev.rotalex.lutter.builtins.actions.StateActions
import dev.rotalex.lutter.builtins.actions.UiActions
import dev.rotalex.lutter.builtins.enums.HorizontalArrangementSpec
import dev.rotalex.lutter.builtins.enums.VerticalArrangementSpec
import dev.rotalex.lutter.builtins.functions.CoreFunctions
import dev.rotalex.lutter.builtins.functions.ListFunctions
import dev.rotalex.lutter.builtins.functions.NumberFunctions
import dev.rotalex.lutter.builtins.functions.StringFunctions
import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.interpreter.eval.FunctionImpls
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.action
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.function
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

/** Registers the enum types the modifier and layout properties name. */
public fun <C : Any, M : Any, A : Any, F : Any> SchemaBuilder<C, M, A, F, TypeSpec>.registerBuiltinEnums(): Unit {
    type(AlignmentSpec.spec)
    type(ShapeSpec.spec)
    type(VerticalArrangementSpec.spec)
    type(HorizontalArrangementSpec.spec)
}

/**
 * Registers §10.2's fifteen seed functions, which are the whole of the expression vocabulary
 * for the first production-capable version.
 *
 * §10.3's other half is [builtinFunctionImpls]: a spec is only half a function, and an
 * assembly that registers these without wiring those gets every call refused at run time.
 */
public fun <C : Any, M : Any, A : Any, T : Any>
    SchemaBuilder<C, M, A, FunctionSpec, T>.registerBuiltinFunctions(): Unit {
    for (spec in ListFunctions.all) function(spec)
    for (spec in StringFunctions.all) function(spec)
    for (spec in NumberFunctions.all) function(spec)
    for (spec in CoreFunctions.all) function(spec)
}

/**
 * Registers §11.4's six MVP action specs, all intrinsic because §11.4 calls state, navigation,
 * control flow and host calls engine-owned. Descriptions only: `IntrinsicHandlers` performs the
 * navigation pair and nothing else, so a registered spec is not handler coverage.
 */
public fun <C : Any, M : Any, F : Any, T : Any>
    SchemaBuilder<C, M, ActionSpec, F, T>.registerBuiltinActions(): Unit {
    for (spec in NavActions.all) action(spec)
    for (spec in StateActions.all) action(spec)
    for (spec in FlowActions.all) action(spec)
    for (spec in HostActions.all) action(spec)
    for (spec in UiActions.all) action(spec)
}

/**
 * Every builtin [FunctionImpl], keyed by the id its `FunctionSpec` is filed under.
 *
 * Its own constructor rather than a `SchemaBuilder` extension because §10.3's two halves live
 * in two registries that two assemblies build separately; §10.3's coverage test is what holds
 * them together, because there is nowhere at construction time that sees both.
 */
public fun builtinFunctionImpls(): FunctionImpls = FunctionImpls.of(
    ListFunctions.impls + StringFunctions.impls + NumberFunctions.impls + CoreFunctions.impls,
)

/**
 * The argument a §10.3 `ParamSig` declared, or the refusal that says the checker and the
 * implementation disagree.
 *
 * §10.6 makes a *value* the document does not have a `Value.Null`; this is the other half, the
 * document that could not have got here at all. It throws for the same reason `Evaluator` does:
 * pass 5 already refused a call whose arguments do not fill the signature, so reaching this
 * means the two halves have stopped agreeing and hiding it would let a document render on one
 * backend and fail on the other.
 */
internal inline fun <reified T : Value> declaredArgument(
    args: List<Value>,
    index: Int,
    id: FunctionId,
): T = args.getOrNull(index) as? T ?: throw IllegalStateException(
    "'$id' argument ${index + 1} is not the type its ParamSig declares",
)
