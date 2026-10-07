package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.BranchSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * §11.4's `flow.if`, the one MVP action with arms.
 *
 * `then` and `else` are §11.2's own words — `branches: Map<BranchName, ActionSequence> =
 * emptyMap(), // "then"/"else" for flow.if` — so they are read rather than chosen. §11.2 gives
 * that map an `emptyMap()` default, so an action is not obliged to carry arms at all, and `then`
 * is required because an `if` whose consequent is missing has nothing to do.
 *
 * §11.2 keeps the arm bodies in the document, where a spec cannot reach them, and that is why
 * this spec names the arms without reaching into them: the action's handler picks one and asks
 * the executor to run it, because only the action knows what its condition selects.
 */
public object FlowActions {

    /**
     * `flow.if`: two arms, named by §11.2, and the condition that chooses between them.
     *
     * `cond` is a typed parameter rather than a shape because `TypeRef.Bool` says what it is, and
     * `params` is where a `TypeRef` types an argument — which is also what gives the analysis pass
     * its typecheck for free. `prop<Nothing>` because the phantom type is a renderer's access type
     * and no renderer reads a step's argument.
     */
    public val conditional: ActionSpec = ActionSpec(
        id = ActionId("flow.if"),
        metadata = ActionMetadata("If", description = "Runs the then arm, or the else arm"),
        params = listOf(prop<Nothing>("cond", TypeRef.Bool, required = true)),
        branches = listOf(
            BranchSpec(BranchName("then"), required = true),
            BranchSpec(BranchName("else")),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(conditional)
}
