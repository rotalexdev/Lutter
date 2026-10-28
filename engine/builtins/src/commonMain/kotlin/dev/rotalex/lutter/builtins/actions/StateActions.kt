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

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(set)
}
