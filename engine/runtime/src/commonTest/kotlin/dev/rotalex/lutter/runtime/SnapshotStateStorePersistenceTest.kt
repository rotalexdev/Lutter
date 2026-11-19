package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §12.1's persistent-state row: a `Persistence.Saveable` declaration is read back from and written
 * to a [PersistentStore], so a value outlives the composition that held it.
 *
 * "Outlives" is tested the only way a unit test can: a second store over the same persisted
 * entries, which is what a restarted process sees. The entries themselves are a test double —
 * the runtime never owns an encoding, and §12.1 calls `PersistentStore` a hook, not a format.
 */
class SnapshotStateStorePersistenceTest {

    private val count: StateId = StateId("s_count")
    private val page: PageId = PageId("p_home")

    @Test
    fun `a saveable declaration a second store reads is the value the first store wrote`() {
        val persistent = MemoryPersistentStore()
        val before = storeOf(persistent, page, saveable(count, "count", Value.Int32(0)))
        before.set(count, Value.Int32(7))

        val after = storeOf(persistent, page, saveable(count, "count", Value.Int32(0)))

        assertEquals(Value.Int32(7), after.get(count))
    }

    @Test
    fun `a saveable declaration nothing wrote still holds its initial`() {
        val store = storeOf(MemoryPersistentStore(), page, saveable(count, "count", Value.Int32(3)))

        assertEquals(Value.Int32(3), store.get(count))
    }

    @Test
    fun `a declaration that is not saveable ignores what the store holds`() {
        val persistent = MemoryPersistentStore()
        val before = storeOf(persistent, page, saveable(count, "count", Value.Int32(0)))
        before.set(count, Value.Int32(7))

        // Same id, same scope, but a document that no longer asks for it to be saved: §12.1's
        // `None` means the composition is the whole lifetime, so a stale entry is not this
        // declaration's value.
        val after = storeOf(persistent, page, held(count, "count", Value.Int32(1)))

        assertEquals(Value.Int32(1), after.get(count))
    }

    @Test
    fun `a write to a declaration that is not saveable reaches no store`() {
        val persistent = MemoryPersistentStore()
        val store = storeOf(persistent, page, held(count, "count", Value.Int32(0)))

        store.set(count, Value.Int32(9))

        assertEquals(Value.Int32(9), store.get(count))
        assertTrue(persistent.isEmpty(), "the store was written ${persistent.keys()}")
    }

    @Test
    fun `the same declaration id in two scopes does not collide`() {
        val persistent = MemoryPersistentStore()
        val home = storeOf(persistent, page, saveable(count, "count", Value.Int32(0)))
        val settings = storeOf(persistent, PageId("p_settings"), saveable(count, "count", Value.Int32(0)))

        home.set(count, Value.Int32(1))

        assertEquals(Value.Int32(1), home.get(count))
        assertEquals(Value.Int32(0), settings.get(count))
    }

    @Test
    fun `a store handed nothing persists nothing`() {
        val store = SnapshotStateStore.seeded(listOf(saveable(count, "count", Value.Int32(0))))

        store.set(count, Value.Int32(4))

        assertEquals(Value.Int32(4), store.get(count))
        assertNull(store.get(StateId("s_absent")))
    }

    private fun storeOf(
        persistent: PersistentStore,
        page: PageId,
        vararg declarations: ResolvedState,
    ): SnapshotStateStore =
        SnapshotStateStore.seeded(declarations.toList(), store = persistent, scope = page.value)

    private fun held(id: StateId, name: String, initial: Value? = null): ResolvedState =
        ResolvedState(StateDecl(id = id, name = name, type = TypeRef.Int32, initial = initial), null)

    private fun saveable(id: StateId, name: String, initial: Value? = null): ResolvedState = ResolvedState(
        StateDecl(
            id = id,
            name = name,
            type = TypeRef.Int32,
            initial = initial,
            persistence = Persistence.Saveable,
        ),
        null,
    )
}

/** The entries a host would keep somewhere a composition does not reach, held in memory here. */
private class MemoryPersistentStore : PersistentStore {

    private val entries: MutableMap<String, Value> = mutableMapOf()

    override fun read(key: String): Value? = entries[key]

    override fun write(key: String, value: Value) {
        entries[key] = value
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun keys(): Set<String> = entries.keys.toSet()
}
