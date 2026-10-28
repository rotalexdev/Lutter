package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.type.TypeRef

/**
 * A type together with the answer to two questions about its values: does this value belong
 * to this type, and what is it when you want it as a model type rather than as a `Value`.
 *
 * PLAN §9.3's interface, with one member removed and the reason recorded in the feature
 * document: `toKotlin` is not here. Its signature returns `KtExpr` and takes a
 * `KotlinLiteralContext`, and §33.7 places both of those in `:engine:codegen` — they are
 * Lutter's own hand-written Kotlin IR (ADR-004, which lists the kotlin-compiler PSI as a
 * rejected alternative), so the reason they cannot be named here is not purity. It is
 * §23.3: `:engine:model` may depend on no other module, `ModuleGraphRules.ALLOWED` says
 * `:engine:model` to an empty set, and `verifyModuleGraph` fails the build on a `project(...)`
 * that says otherwise. Emitting Kotlin from the module that owns the vocabulary would invert
 * the dependency the whole engine is arranged around, so the emission stays where the IR is.
 *
 * ### Why this is in the model at all, given that PLAN puts it in `:engine:schema`
 *
 * It is not, according to §4.5.2, §33.2's file manifest and the module table, all of which say
 * `:engine:schema`. It is here because this work unit was asked for it here, and the conflict
 * is recorded rather than resolved unilaterally: a kind that decodes an enum entry or an
 * object's fields needs the schema, so **the kinds that need a schema cannot live in a module
 * that may not see one**. What is here is the part of the contract that is schema-free, which
 * is exactly the part that can be, and the rest is left to the module PLAN assigns it to.
 *
 * ### `accepts` and `decode` are two questions, not one
 *
 * `accepts` is total and cheap: it is a variant check and it answers for a value of the wrong
 * shape without throwing. `decode` is the one that can fail, because a value of the right
 * variant can still carry a payload the caller cannot use — and it fails at the call that made
 * the mistake, with the kind's own tag in the message. Collapsing them into one would make
 * "is this a colour?" and "give me the colour" the same operation, and an editor asking the
 * first question would have to catch an exception to get the answer.
 *
 * ### The relationship with [TypeRef]
 *
 * [type] is the type this kind answers for, and each implementation in `ValueKinds.kt` holds
 * one of the closed, serializable types from [TypeRef] — never a name of its own. That is what
 * makes a kind usable as a *property's* companion: a `PropertySpec` declares a `TypeRef`, and
 * the kind that goes with it is found by that declaration rather than by a second table that
 * could disagree with the first.
 *
 * @see dev.rotalex.lutter.model.value.ValueKinds for the kinds that exist, and for the
 *   eight types that do not have one yet and what each of them is waiting for.
 */
public interface ValueKind<T> {

    /** The type this kind answers for, as a [TypeRef]. */
    public val type: TypeRef

    /**
     * Whether [value] is a value of [type], without throwing and without decoding it.
     *
     * The check is on the variant, and that is the most the model can know. "A `Value.Enum`
     * whose entry exists in the enum type this kind names" needs the `EnumTypeSpec`, and a
     * check that needed a registry would not be a model operation.
     */
    public fun accepts(value: Value): Boolean

    /**
     * The value as a model type.
     *
     * @throws IllegalArgumentException if [value] is not of [type]. A kind that returned a
     *   default instead would be a way for a document error to become a wrong rendering with
     *   no diagnostic anywhere.
     */
    public fun decode(value: Value): T
}
