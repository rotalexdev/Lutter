package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.action.ActionHandler
import dev.rotalex.lutter.interpreter.action.IntrinsicHandlers
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.interpreter.eval.FunctionImpls
import dev.rotalex.lutter.model.ids.ActionId

/**
 * The interpreter seam: function implementations plus action handlers.
 *
 * §15.2 names both halves and both are here. The handlers default to the engine's own table, built
 * over [functions] — the same implementations a property's call resolves against, so an argument
 * written as a call and a property written as one are answered by one table.
 */
public class Implementations(
    public val functions: FunctionImpls = FunctionImpls.None,
    public val actions: Map<ActionId, ActionHandler> = IntrinsicHandlers.handlers(Evaluator(functions)),
) {
    public companion object {
        /** No functions and the engine's own actions: every call refuses, every action runs. */
        public val None: Implementations = Implementations()
    }
}
