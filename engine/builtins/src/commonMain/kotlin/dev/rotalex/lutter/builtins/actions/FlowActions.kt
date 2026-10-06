package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.BranchSpec

/**
 * §11.4's `flow.if`, the one MVP action with arms.
 *
 * `then` and `else` are §11.2's own words — `branches: Map<BranchName, ActionSequence> =
 * emptyMap(), // "then"/"else" for flow.if` — so they are read rather than chosen. §11.2 gives
 * that map an `emptyMap()` default, so an action is not obliged to carry arms at all, and `then`
 * is required because an `if` whose consequent is missing has nothing to do.
 *
 * The condition is not a parameter: §11.5 emits `if (...) { … } else { … }` without naming an
 * argument, and §11.2 keeps the arm bodies in the document, where a spec cannot reach them.
 */
public object FlowActions {

    /** `flow.if`: two arms, named by §11.2, and no parameter §11.5 names. */
    public val conditional: ActionSpec = ActionSpec(
        id = ActionId("flow.if"),
        metadata = ActionMetadata("If", description = "Runs the then arm, or the else arm"),
        params = emptyList(),
        branches = listOf(
            BranchSpec(BranchName("then"), required = true),
            BranchSpec(BranchName("else")),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(conditional)
}
