package dev.rotalex.lutter.runtime

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.interpreter.StateStore
import dev.rotalex.lutter.interpreter.StateWriter
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value

/**
 * §15.3's store: one `mutableStateOf` per held slot, so a read subscribes the composition it
 * happens in and a write invalidates nothing else.
 *
 * PLAN §12.2 settles the shape of the seam — the interpreter depends on the [StateStore] and
 * [StateWriter] interfaces and the Compose-backed implementation lives here — so this implements
 * the interpreter's pair and `:engine:runtime`'s own same-named marker is gone. One name per
 * concept across the graph is worth more than a second empty interface nobody implemented.
 *
 * ### A derived declaration has no slot at all
 *
 * §12.1 computes derived state on read and §12.3 refuses it as a write target, so seeding leaves
 * it out and [set] names it rather than accepting a value nothing would ever read again.
 */
public class SnapshotStateStore private constructor(
    private val slots: Map<StateId, MutableState<Value>>,
    private val derived: Set<StateId>,
) : StateStore, StateWriter {

    /** The current value of [id], or null when no held declaration carries one. */
    override fun get(id: StateId): Value? = slots[id]?.value

    /** Replaces [id]'s value, which invalidates every composition that read it. */
    override fun set(id: StateId, value: Value) {
        val slot = slots[id] ?: throw IllegalStateException(unwritable(id))
        slot.value = value
    }

    private fun unwritable(id: StateId): String = when {
        id in derived -> "State '$id' is derived, and §12.3 refuses a derived declaration as a write target"
        else -> "No state declaration holds '$id'; §6.2 ids are declared, never minted"
    }

    public companion object {

        /** §15.2's default: no declarations, so every read is null and every write is refused. */
        public val Empty: SnapshotStateStore = SnapshotStateStore(emptyMap(), emptySet())

        /**
         * A store over [declarations]: each held one seeded from its `initial`, each derived one
         * left without a slot.
         *
         * A held declaration with no `initial` is seeded with nothing rather than with a default.
         * §12.1 says exactly one of `initial` and `derived`, and a document supplying neither has
         * not said what the slot holds — so a read answers null and the author sees the absence
         * instead of a value this engine invented.
         */
        public fun seeded(declarations: List<ResolvedState>): SnapshotStateStore {
            val derived = declarations.filter { it.derived != null }.map { it.decl.id }.toSet()
            val slots = declarations
                .filter { it.derived == null }
                .mapNotNull { state -> state.decl.initial?.let { initial -> state.decl.id to mutableStateOf(initial) } }
                .toMap()
            return SnapshotStateStore(slots, derived)
        }
    }
}
