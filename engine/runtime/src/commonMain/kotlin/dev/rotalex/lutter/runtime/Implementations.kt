package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.eval.FunctionImpls

/**
 * The interpreter seam: function implementations plus action handlers.
 *
 * §15.2 names both halves, and only the first is here. An empty action map would claim coverage
 * the runtime cannot honour — Phase 7's `ActionHandler` and `IntrinsicHandlers` are what fill it,
 * and a `state.set` target has no writable slot to reach until they do.
 */
public class Implementations(
    public val functions: FunctionImpls = FunctionImpls.None,
) {
    public companion object {
        /** No implementations. Every call refuses until the host registers a function. */
        public val None: Implementations = Implementations()
    }
}
