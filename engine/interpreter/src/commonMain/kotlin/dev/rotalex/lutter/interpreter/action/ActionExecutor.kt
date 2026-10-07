package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.ids.ActionId

/**
 * Runs one action sequence in order, and stops it at the first failure.
 *
 * Sequential on purpose: a document is a script, and two steps that touch the same state have
 * an order their author wrote. Running them at once would make what a document means depend
 * on the host's scheduler.
 *
 * Steps, and nothing else. A step's arms belong to the action whose spec declares them, so this
 * runner has no arm to run and no action to recognise: `flow.if` chooses one and asks for it back
 * through [SequenceRunner], and a handler for an action with no arms never sees one.
 */
public class ActionExecutor(
    handlers: Map<ActionId, ActionHandler>,
) {

    // Copied so a table a composition builds cannot change under a sequence already running.
    private val handlers: Map<ActionId, ActionHandler> = handlers.toMap()

    /**
     * Runs [sequence] against [env] and reports what stopped it, or that nothing did.
     *
     * The outcome is returned rather than routed from here because the diagnostic's
     * destination belongs to the host, and the host is not visible from this module.
     *
     * The runner passed down recurses through this method, so a failure inside an arm stops that
     * arm and the steps after the step carrying it — and the depth it reaches is bounded by
     * nothing but the stack, which is the same answer the model gives for a document's own nesting.
     */
    public suspend fun run(sequence: ActionSequence, env: ActionEnv): ActionOutcome {
        for (step in sequence.steps) {
            val handler = handlers[step.action]
            if (handler == null) {
                return ActionOutcome.Failed(
                    RuntimeDiagnostic("No handler is registered for action '${step.action.value}'"),
                )
            }

            val outcome = handler.execute(step, env) { nested -> run(nested, env) }
            if (outcome is ActionOutcome.Failed) return outcome
        }

        return ActionOutcome.Done
    }
}
