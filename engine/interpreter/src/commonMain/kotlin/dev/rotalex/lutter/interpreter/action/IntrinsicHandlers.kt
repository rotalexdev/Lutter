package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.constantOrNull
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value

/**
 * The handlers for the actions whose meaning the engine owns.
 *
 * Only the navigation pair is here, and the other four are absent rather than refused: no section
 * names the argument keys `state.set` writes or `host.call` passes, a snackbar has no slot on
 * `ActionEnv` to be written to, and `flow.if` carries no condition — with its arms the executor's
 * to run, a handler returning `Done` would run both. No handler means the executor's own
 * diagnostic, which is true, where a guess would be a false claim about what a step did.
 */
public object IntrinsicHandlers {

    /** `nav.navigate`: opens the page the step names. */
    public val navigate: ActionHandler = Navigate()

    /** `nav.back`: steps the navigator back one entry. */
    public val back: ActionHandler = Back()

    /** Every handler here, keyed by the action it performs. */
    public val handlers: Map<ActionId, ActionHandler> = mapOf(
        ActionId("nav.navigate") to navigate,
        ActionId("nav.back") to back,
    )
}

// The action ids and the one argument key are spelled out because the specs that declare them
// live in `:engine:builtins`, which depends on this module and not the other way round. Both are
// already on the wire: a step carries its own action id, and its arguments are keyed by the very
// key the spec names.
private val pageArgument = PropertyKey("page")

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

/** The refusal every failure here is, naming the action because a step carries no node id. */
private fun refusal(step: ActionStep, reason: String): ActionOutcome.Failed =
    ActionOutcome.Failed(RuntimeDiagnostic("Action '${step.action.value}' $reason"))
