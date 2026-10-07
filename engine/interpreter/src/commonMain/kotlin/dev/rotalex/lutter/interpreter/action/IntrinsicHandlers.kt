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
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value

/**
 * The handlers for the actions whose meaning the engine owns.
 *
 * `ui.showSnackbar` is the one MVP action still absent rather than refused: §11.4 names the id and
 * nothing about what it carries, and `ActionEnv` has no snackbar slot to be written to. No handler
 * means the executor's own diagnostic, which is true, where a guess would be a false claim about
 * what a step did.
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
        ActionId("flow.if") to Conditional(evaluator),
        ActionId("host.call") to CallHost(evaluator),
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

/** The condition `flow.if` chooses an arm by, and the two arms §11.2 names for it. */
private val conditionArgument = PropertyKey("cond")

private val thenArm = BranchName("then")

private val elseArm = BranchName("else")

/** `host.call`'s callee key, and the positional list that §11.6's declaration types. */
private val nameArgument = PropertyKey("name")

private val argsArgument = PropertyKey("args")

/**
 * `nav.navigate`.
 *
 * The target is a literal on the step and never an expression, because a page has no
 * `RefTarget` variant and therefore no expression form at all. Reading it through `env.scope`
 * would be looking for a binding that cannot exist.
 */
private class Navigate : ActionHandler {

    override suspend fun execute(step: ActionStep, env: ActionEnv, run: SequenceRunner): ActionOutcome {
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
     * answer to the question rather than a failure of the step, and nothing records it where a
     * `flow.if` could test it: it is this handler's own return value, and no step reads another's.
     */
    override suspend fun execute(step: ActionStep, env: ActionEnv, run: SequenceRunner): ActionOutcome {
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

    override suspend fun execute(step: ActionStep, env: ActionEnv, run: SequenceRunner): ActionOutcome {
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
 * `flow.if`: evaluates its condition and runs the one arm the condition selects.
 *
 * §11.2 keeps the arm bodies in the document and §11.3 names them in the spec, so choosing one is
 * this action's own job and the executor's is only to be asked. A condition that is not a boolean,
 * and an expression that refuses, are both a document fault and are reported as one.
 */
private class Conditional(private val evaluator: Evaluator) : ActionHandler {

    override suspend fun execute(step: ActionStep, env: ActionEnv, run: SequenceRunner): ActionOutcome {
        val condition = step.args[conditionArgument]
            ?: return refusal(step, "has no '$conditionArgument' argument")

        val value = condition.valueOrNull(evaluator, env.scope)
            ?: return refusal(step, "argument '$conditionArgument' produces no value")

        val taken = value as? Value.Bool
            ?: return refusal(step, "argument '$conditionArgument' is ${value::class.simpleName}, not a Boolean")

        if (!taken.v) {
            // §11.3 requires `then` alone: an `if` with no consequent has nothing to do, and one
            // with no alternative has nothing to fall back to, which is the same as doing nothing.
            val alternative = step.branches[elseArm] ?: return ActionOutcome.Done
            return run(alternative)
        }

        val consequent = step.branches[thenArm]
            ?: return refusal(step, "carries no '$thenArm' arm")

        return run(consequent)
    }
}

/**
 * `host.call`: the function its name argument spells, called with the step's own arguments.
 *
 * Absence is a refusal rather than a throw because `HostFunctions.find` answers null for a name it
 * does not hold — that is what its type says — and a throw here would leave the one path a step has
 * for reporting and unwind the composition's coroutine instead.
 *
 * The arguments are positional and unchecked, because `HostFunctions` is a map from name to a
 * lambda and carries no declaration: arity belongs to the pass that can read `HostFunctionDecl`,
 * which is the one holding the document.
 */
private class CallHost(private val evaluator: Evaluator) : ActionHandler {

    override suspend fun execute(step: ActionStep, env: ActionEnv, run: SequenceRunner): ActionOutcome {
        val named = step.args[nameArgument]
            ?: return refusal(step, "has no '$nameArgument' argument")

        val name = (named.valueOrNull(evaluator, env.scope) as? Value.Str)?.v
            ?: return refusal(step, "argument '$nameArgument' is not a string")

        val function = env.host.find(name)
            ?: return refusal(step, "names no host function '$name'")

        val written = step.args[argsArgument]
        val arguments = if (written == null) {
            emptyList()
        } else {
            val list = written.valueOrNull(evaluator, env.scope) as? Value.ListOf
                ?: return refusal(step, "argument '$argsArgument' is not a list of arguments")
            list.items
        }

        // Whatever it answers stays with the host: a null is a function returning nothing, and
        // `ActionEnv` has nowhere for a value to land.
        function(arguments)
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
