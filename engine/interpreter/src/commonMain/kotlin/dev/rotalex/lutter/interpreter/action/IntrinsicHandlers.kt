package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.constantOrNull
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value

/**
 * The handlers for the actions whose meaning the engine owns.
 *
 * The other three are absent rather than refused: no section names the argument keys `host.call`
 * passes, a snackbar has no slot on `ActionEnv` to be written to, and `flow.if` carries no
 * condition — with its arms the executor's to run, a handler returning `Done` would run both.
 * No handler means the executor's own diagnostic, which is true, where a guess would be a false
 * claim about what a step did.
 */
public object IntrinsicHandlers {

    /** `nav.navigate`: opens the page the step names. */
    public val navigate: ActionHandler = Navigate()

    /** `nav.back`: steps the navigator back one entry. */
    public val back: ActionHandler = Back()

    /**
     * Every handler here, keyed by the action it performs.
     *
     * Built over [evaluator] rather than held as a table, because a computed argument runs
     * through it and the functions it resolves against are the host's, not the engine's.
     */
    public fun handlers(evaluator: Evaluator): Map<ActionId, ActionHandler> = mapOf(
        ActionId("nav.navigate") to navigate,
        ActionId("nav.back") to back,
        ActionId("state.set") to SetState(evaluator),
    )
}

// The action ids and the argument keys are spelled out because the specs that declare them live
// in `:engine:builtins`, which depends on this module and not the other way round. The ids are
// already on the wire: a step carries its own action id, and its arguments are keyed by the very
// key the spec names.
private val pageArgument = PropertyKey("page")

// These two are for the one action whose spec declares no parameter at all: nothing outside this
// file says what a write step is called, so its keys are read here rather than looked up.
private val targetArgument = PropertyKey("target")

/** §12.3's `state.set(event.value)`: the value being written, which any expression may produce. */
private val valueArgument = PropertyKey("value")

/**
 * `nav.navigate`.
 *
 * The target is a literal on the step and never an expression, because a page has no
 * `RefTarget` variant and therefore no expression form at all. Reading it through `env.scope`
 * would be looking for a binding that cannot exist.
 */
private class Navigate : ActionHandler {

    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        val argument = step.args[pageArgument]
            ?: return refusal(step, "has no '$pageArgument' argument")

        val ref = argument.constantOrNull() as? Value.Ref
            ?: return refusal(step, "argument '$pageArgument' is not a reference")

        if (ref.kind != RefKind.Page) {
            return refusal(step, "argument '$pageArgument' is a ${ref.kind.name.lowercase()} reference")
        }

        // The id is checked by construction rather than trusted: analysis compares it as a bare
        // string, so a document can carry one the model refuses to name, and its `require` is
        // caught here because a throw would leave the one path a step has for reporting.
        val page = runCatching { PageId(ref.id) }.getOrNull()
            ?: return refusal(step, "argument '$pageArgument' names '${ref.id}', which is not a page id")

        // The spec declares `page` alone, so there is nothing to pass beside it.
        env.navigator.navigate(page, emptyMap())
        return ActionOutcome.Done
    }
}

/** `nav.back`, which §13.2 gives no argument. */
private class Back : ActionHandler {

    /*
     * The Boolean is discarded. At the root there is nowhere to go, which is the navigator's
     * answer to the question rather than a failure of the step, and the only thing that could
     * read it — a `flow.if` — carries no condition to test it with.
     */
    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        env.navigator.back()
        return ActionOutcome.Done
    }
}

/**
 * `state.set`: writes the state its target argument names.
 *
 * The target is *named*, not evaluated. `Expr.Ref(RefTarget.State)` resolves to that state's own
 * value, so evaluating it would write back what it already holds; what the step needs is the id
 * inside the expression. No reference kind is added for a state for the same reason — a state is
 * nameable as an expression, and `RefKind` is a document constant's kind.
 */
private class SetState(private val evaluator: Evaluator) : ActionHandler {

    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        val target = step.args[targetArgument]
            ?: return refusal(step, "has no '$targetArgument' argument")

        val id = target.stateIdOrNull()
            ?: return refusal(step, "argument '$targetArgument' does not name a state")

        val written = step.args[valueArgument]
            ?: return refusal(step, "has no '$valueArgument' argument")

        val value = written.valueOrNull(evaluator, env.scope)
            ?: return refusal(step, "argument '$valueArgument' produces no value")

        // A store that refuses the write has its own message, naming the id and the reason. It is
        // caught rather than propagated because nothing checks a write target yet, so this is a
        // document fault arriving where a step's one reporting path is.
        val refused = runCatching { env.state.set(id, value) }.exceptionOrNull()
        if (refused != null) return refusal(step, "could not be written: ${refused.message}")

        return ActionOutcome.Done
    }
}

/**
 * The state a target argument names, or null when it names none.
 *
 * A constant cannot name one: a document constant is one of the four reference kinds and none of
 * them is a state. That is why no shape outside a computed read is tried here.
 */
private fun PropertyValue.stateIdOrNull(): StateId? = when (this) {
    is PropertyValue.Const -> null
    is PropertyValue.Computed -> ((expr as? Expr.Ref)?.target as? RefTarget.State)?.id
}

/**
 * The value an argument holds: a constant as it is, a computed one through [evaluator].
 *
 * A refusal answers null rather than throwing, because this package's evaluator throws for every
 * refusal it has and the caller turns a null into the diagnostic a step reports.
 */
private fun PropertyValue.valueOrNull(evaluator: Evaluator, scope: EvalScope): Value? = when (this) {
    is PropertyValue.Const -> constantOrNull()
    is PropertyValue.Computed -> constantOrNull() ?: runCatching { evaluator.evalUnchecked(expr, scope) }.getOrNull()
}

/** The refusal every failure here is, naming the action because a step carries no node id. */
private fun refusal(step: ActionStep, reason: String): ActionOutcome.Failed =
    ActionOutcome.Failed(RuntimeDiagnostic("Action '${step.action.value}' $reason"))
