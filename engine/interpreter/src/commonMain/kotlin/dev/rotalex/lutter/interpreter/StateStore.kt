package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value

/**
 * Reads of state by declaration id. The interpreter depends only on this;
 * snapshot-backed implementations live in `:engine:runtime` (PLAN §12.2).
 */
public interface StateStore {
    /** The current value of [id], or null when nothing holds it. */
    public fun get(id: StateId): Value?
}

/**
 * Writes of state by declaration id. Separate from [StateStore] so derived
 * state can expose reads without accepting writes (PLAN §12.3).
 */
public interface StateWriter {
    /** Replaces the value held under [id]. */
    public fun set(id: StateId, value: Value)
}

/**
 * In-memory store for tests and previews. A plain map, no snapshot tracking;
 * subscribing reads are `:engine:runtime`'s job.
 */
public class MapStateStore(initial: Map<StateId, Value> = emptyMap()) : StateStore, StateWriter {
    private val slots: MutableMap<StateId, Value> = initial.toMutableMap()

    override fun get(id: StateId): Value? = slots[id]

    override fun set(id: StateId, value: Value) {
        slots[id] = value
    }
}
