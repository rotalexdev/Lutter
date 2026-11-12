package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape

/**
 * §11.4's `state.set`, the action §12.3 keeps explicit instead of a two-way binding (ADR-009).
 *
 * Its two keys are shapes rather than `params`, because no `TypeRef` names a state: the target is
 * a read of one and the value is typed by whatever that read names, which is a declaration in the
 * document and not in this spec. `RefKind.State` was rejected for the same reason — a state is an
 * expression, and §9.1's four reference kinds are document constants.
 */
public object StateActions {

    /**
     * `state.set`: §12.3's write to one writable target.
     *
     * Both rules are required: a step with no target has nothing to write and one with no value
     * writes nothing, which is the same refusal a handler makes at run time. The keys are
     * spelled as strings because `:engine:interpreter`'s handler reads the same two by hand and
     * has no way to see this module.
     */
    public val set: ActionSpec = ActionSpec(
        id = ActionId("state.set"),
        metadata = ActionMetadata("Set state"),
        // Explicit rather than defaulted: `params` carries no default anywhere on purpose, so an
        // action that takes its arguments as rules says so instead of inheriting a silent empty.
        params = emptyList(),
        argRules = listOf(
            ArgRule(PropertyKey("target"), ArgShape.StateRef, required = true, doc = "The state to write"),
            ArgRule(PropertyKey("value"), ArgShape.TargetValue, required = true, doc = "What to write into it"),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /**
     * `state.toggle`: flips one `Bool` state.
     *
     * The target alone, because a toggle has nothing else to carry: the new value is the old one
     * negated, and a `value` argument would let a document write what the action already means.
     * The Bool restriction is not in the shape — shapes say what an argument *is*, and the
     * interpreter's handler is where the type is known at run time.
     */
    public val toggle: ActionSpec = ActionSpec(
        id = ActionId("state.toggle"),
        metadata = ActionMetadata("Toggle state"),
        params = emptyList(),
        argRules = listOf(
            ArgRule(PropertyKey("target"), ArgShape.StateRef, required = true, doc = "The Bool state to flip"),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /**
     * `list.append`: adds one value at the end of a list state.
     *
     * The same two shapes `state.set` takes, but the handler refuses a target that is not a
     * list: `StateRef` names the state and says nothing about what it holds, so the list
     * requirement is a run-time answer like the toggle's Bool one.
     */
    public val append: ActionSpec = ActionSpec(
        id = ActionId("list.append"),
        metadata = ActionMetadata("Append to list"),
        params = emptyList(),
        argRules = listOf(
            ArgRule(PropertyKey("target"), ArgShape.StateRef, required = true, doc = "The list state to grow"),
            ArgRule(PropertyKey("value"), ArgShape.TargetValue, required = true, doc = "What to add at the end"),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /**
     * `list.remove`: takes every occurrence of one value out of a list state.
     *
     * Every occurrence, because the generated form is `state.items - value` and Kotlin's minus
     * for lists is defined that way — an interpreter that removed only the first occurrence
     * would disagree with the file it is claimed to run like. A document with duplicate rows
     * loses them together; wave-3's row identity is where a by-one removal can come back, and
     * until then this spelling is the honest one rather than a pretending one.
     */
    public val remove: ActionSpec = ActionSpec(
        id = ActionId("list.remove"),
        metadata = ActionMetadata("Remove from list"),
        params = emptyList(),
        argRules = listOf(
            ArgRule(PropertyKey("target"), ArgShape.StateRef, required = true, doc = "The list state to shrink"),
            ArgRule(PropertyKey("value"), ArgShape.TargetValue, required = true, doc = "What to take out"),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(set, toggle, append, remove)
}
