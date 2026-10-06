// One file for the three contracts a step runs against: what a handler is handed, what it
// reports back, and what it may reach through. They are only readable together — a handler's
// signature is what says what the environment is for.
package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.StateWriter
import dev.rotalex.lutter.interpreter.env.DialogHost
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.action.ActionStep

/**
 * What one action does when it is reached.
 *
 * The step is the document's own, with its arguments unevaluated and its branches still
 * unrun: a handler reads what it needs through the environment and performs its one effect.
 * Running the branches is the executor's job rather than the handler's, because nothing on
 * the environment reaches back into it — a handler that ran its own arms would need the
 * executor handed to it as well.
 */
public interface ActionHandler {

    /** Performs [step]'s own effect against [env]. */
    public suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome
}

/**
 * Everything a handler is allowed to reach.
 *
 * Reads and writes are two members rather than one store, which is what lets a scope expose
 * reads over derived state without accepting writes back into it.
 *
 * [dialogs] carries a slot no MVP action writes to: dialog actions arrive after the MVP, and
 * declaring the slot now is what keeps a later dialog action from changing this signature.
 */
public interface ActionEnv {
    public val scope: EvalScope
    public val state: StateWriter
    public val navigator: Navigator
    public val dialogs: DialogHost
    public val host: HostFunctions
}

/**
 * What a step reports back.
 *
 * A failure is a value rather than an exception because the sequence has to stop on it and
 * the host has to be told. Throwing leaves the caller nothing to route, and an exception
 * escaping `run` unwinds the composition's own coroutine scope rather than one handler.
 */
public sealed interface ActionOutcome {

    /** The step did what it was asked to. */
    public data object Done : ActionOutcome

    /** The step could not. [diagnostic] says why, and stops the sequence it was reached in. */
    public data class Failed(public val diagnostic: RuntimeDiagnostic) : ActionOutcome
}
