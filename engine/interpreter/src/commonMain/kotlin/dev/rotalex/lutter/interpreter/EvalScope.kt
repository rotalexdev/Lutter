package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * What an expression may read: state through the store, params through the map.
 * Event args and iteration variables refuse: handlers are Phase 7, loops post-MVP.
 */
public interface EvalScope {
    /** Resolves [target]; throws stating why when the skeleton cannot. */
    public fun read(target: RefTarget): Value
}

/**
 * Scope over a store plus page/component params. Unknown ids throw: presence is
 * analysis's promise (Phase 6), not a default the scope invents.
 */
public class MapEvalScope(
    private val states: StateStore,
    private val params: Map<ParamName, Value> = emptyMap(),
) : EvalScope {
    override fun read(target: RefTarget): Value = when (target) {
        is RefTarget.State -> states.get(target.id)
            ?: throw IllegalStateException("Unknown state '${target.id}': the skeleton holds only seeded values")
        is RefTarget.Param -> params[target.name]
            ?: throw IllegalStateException("Unknown param '${target.name}': the caller seeds page and component params")
        is RefTarget.EventArg -> throw IllegalStateException(
            "Event arg '${target.name}' resolves only inside an action handler (Phase 7)",
        )
        is RefTarget.Item -> throw IllegalStateException(
            "Iteration variable '${target.name}' has no binding: loops are post-MVP",
        )
    }
}
