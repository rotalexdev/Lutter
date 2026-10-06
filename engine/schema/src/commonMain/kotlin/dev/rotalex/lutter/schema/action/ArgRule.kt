package dev.rotalex.lutter.schema.action

import dev.rotalex.lutter.model.ids.PropertyKey

/**
 * One argument a step may carry, and what it has to be when it does.
 *
 * [ActionSpec.params] is where a [dev.rotalex.lutter.model.type.TypeRef] types an argument;
 * this is where one cannot. The two lists are separate because `state.set`'s `value` is typed
 * by the declaration its `target` names, and that declaration is in the document rather than in
 * the spec — a type here would have to be right for every state a document could declare.
 */
public class ArgRule(
    public val key: PropertyKey,
    public val shape: ArgShape,
    public val required: Boolean = false,
    public val doc: String = "",
)

/**
 * What an argument must satisfy when no type can say it: a closed set of shapes, not a predicate.
 *
 * Two, because §11.4's `state.set` has exactly two arguments no type reaches — one naming a
 * state, one carrying the value written to it. A predicate would make the check arbitrary per
 * plugin; a `TypeRef.Any` would make `expr.type_mismatch` permissive everywhere to serve one
 * argument; a `RefKind.State` would put a state on the wire, which it is not. Both rejections,
 * and the step a third shape would take, are recorded in PLAN §4.6.
 */
public sealed interface ArgShape {

    /**
     * A state, named rather than evaluated: `Computed(Expr.Ref(RefTarget.State(id)))`.
     *
     * Evaluated it would write back what the state already holds, because that read resolves
     * to the state's own value.
     */
    public data object StateRef : ArgShape

    /**
     * A value, typed against the declaration this step's [StateRef] names.
     *
     * With no [StateRef] beside it there is no type to check against, so a pass has nothing to
     * do — which is why [ArgRule] carries a shape rather than a bare key: the two rules of
     * `state.set` say so between them.
     */
    public data object TargetValue : ArgShape
}