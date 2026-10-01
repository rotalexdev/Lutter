package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Writes round-trip; unknown ids read as null rather than a default. */
class StateStoreTest {

    private val id: StateId = StateId("s_count")

    @Test
    fun `a write reads back the same value`() {
        val store: MapStateStore = MapStateStore()
        store.set(id, Value.Int32(3))

        assertEquals(Value.Int32(3), store.get(id))
    }

    @Test
    fun `a later write replaces the earlier value`() {
        val store: MapStateStore = MapStateStore()
        store.set(id, Value.Int32(3))
        store.set(id, Value.Int32(4))

        assertEquals(Value.Int32(4), store.get(id))
    }

    @Test
    fun `an unknown state reads as null`() {
        val store: MapStateStore = MapStateStore()

        assertNull(store.get(id))
    }

    @Test
    fun `seeds carry their initial values`() {
        val store: MapStateStore = MapStateStore(mapOf(id to Value.Str("Hi")))

        assertEquals(Value.Str("Hi"), store.get(id))
    }
}
