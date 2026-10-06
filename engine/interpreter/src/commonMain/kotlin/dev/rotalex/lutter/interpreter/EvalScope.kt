package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value

/**
 * What an expression may read: state through the store, params through the map, and the
 * arguments of the event being handled wherever a scope binds them.
 *
 * Iteration variables refuse on every scope: loops are post-MVP.
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

/**
 * One scope over another, plus the arguments of the event being handled.
 *
 * A handler's scope is the scope its screen already reads through, with what the event carried
 * bound on top — so this wraps rather than repeating the state and param rules beside it, and
 * every read it does not answer stays the inner scope's own answer.
 *
 * [args] is keyed by a bare [String] because `RefTarget.EventArg` carries one. An unbound name
 * refuses rather than answering null: only the host that dispatched the event knows what it
 * carried, and an absent one is a document reading something the event never had.
 */
public class EventArgScope(
    private val inner: EvalScope,
    private val args: Map<String, Value> = emptyMap(),
) : EvalScope {

    override fun read(target: RefTarget): Value = when (target) {
        is RefTarget.EventArg -> args[target.name]
            ?: throw IllegalStateException(
                "Unknown event argument '${target.name}': the host binds what it dispatched",
            )

        else -> inner.read(target)
    }
}
