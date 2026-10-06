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
 * A step's handler runs before the branches on that step, and the branches after it, which is
 * what lets a handler that chooses an arm do so by returning rather than by calling back into
 * the executor. Branches run in the order the document lists them, and a failure inside one
 * stops that arm, the steps after the step carrying it, and nothing on the far side.
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
     * Recursion is over the branches, which nothing bounds, so a document nested deeper than
     * the stack exhausts it rather than reporting a diagnostic.
     */
    public suspend fun run(sequence: ActionSequence, env: ActionEnv): ActionOutcome {
        for (step in sequence.steps) {
            val handler = handlers[step.action]
            if (handler == null) {
                return ActionOutcome.Failed(
                    RuntimeDiagnostic("No handler is registered for action '${step.action.value}'"),
                )
            }

            val outcome = handler.execute(step, env)
            if (outcome is ActionOutcome.Failed) return outcome

            for (arm in step.branches.values) {
                val stopped = run(arm, env)
                if (stopped is ActionOutcome.Failed) return stopped
            }
        }

        return ActionOutcome.Done
    }
}
