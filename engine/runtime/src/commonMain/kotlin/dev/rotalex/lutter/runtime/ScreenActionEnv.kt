package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.EventArgScope
import dev.rotalex.lutter.interpreter.StateWriter
import dev.rotalex.lutter.interpreter.action.ActionEnv
import dev.rotalex.lutter.interpreter.env.DialogHost
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.interpreter.env.SnackbarHost
import dev.rotalex.lutter.model.value.Value

/**
 * The environment a document's handler runs against, over one screen's scope and page store.
 *
 * Reads and writes are handed in rather than taken from [RuntimeEnvironment] because page state
 * lives in a store the screen remembers, and only the screen knows which page it is.
 *
 * [eventArgs] are the arguments of the event that fired, bound where an expression may read
 * `RefTarget.EventArg`. They are supplied here because no engine type sees a platform event: the
 * host that dispatched one owns the values, and a document cannot invent them.
 */
public class ScreenActionEnv(
    screen: EvalScope,
    override val state: StateWriter,
    private val environment: RuntimeEnvironment,
    eventArgs: Map<String, Value> = emptyMap(),
) : ActionEnv {

    override val scope: EvalScope = EventArgScope(screen, eventArgs)
    override val navigator: Navigator get() = environment.navigator
    override val dialogs: DialogHost get() = DialogHost.None

    // Nothing renders a snackbar yet, so a host that really shows one arrives with the rendering
    // path rather than as a slot on `RuntimeEnvironment` that only one value can ever fill.
    override val snackbars: SnackbarHost get() = SnackbarHost.None

    override val host: HostFunctions get() = environment.host
}
