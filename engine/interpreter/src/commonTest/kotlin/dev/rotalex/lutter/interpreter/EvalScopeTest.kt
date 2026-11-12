package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * State and params resolve, a handler's scope adds the event's arguments, and everything else
 * refuses with a reason.
 */
class EvalScopeTest {

    private val stateId: StateId = StateId("s_count")

    @Test
    fun `a seeded state resolves`() {
        val scope: EvalScope = MapEvalScope(MapStateStore(mapOf(stateId to Value.Int32(3))))

        assertEquals(Value.Int32(3), scope.read(RefTarget.State(stateId)))
    }

    @Test
    fun `a written state resolves`() {
        val store: MapStateStore = MapStateStore()
        store.set(stateId, Value.Bool(true))

        assertEquals(Value.Bool(true), MapEvalScope(store).read(RefTarget.State(stateId)))
    }

    @Test
    fun `an unknown state refuses naming the id`() {
        val scope: EvalScope = MapEvalScope(MapStateStore())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.State(stateId))
        }
        assertTrue(failure.message?.contains("s_count") == true)
    }

    @Test
    fun `a seeded param resolves`() {
        val scope: EvalScope = MapEvalScope(
            MapStateStore(),
            mapOf(ParamName("title") to Value.Str("Hi")),
        )

        assertEquals(Value.Str("Hi"), scope.read(RefTarget.Param(ParamName("title"))))
    }

    @Test
    fun `an unknown param refuses naming it`() {
        val scope: EvalScope = MapEvalScope(MapStateStore())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Param(ParamName("title")))
        }
        assertTrue(failure.message?.contains("title") == true)
    }

    @Test
    fun `an event arg refuses outside a handler`() {
        val scope: EvalScope = MapEvalScope(MapStateStore())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.EventArg("value"))
        }
        assertTrue(failure.message?.contains("Phase 7") == true)
    }

    /**
     * The one item read no frame resolves against. It is this refusal, not a scope bug:
     * pass 5 resolves an item only where an iterating slot's children read through one.
     */
    @Test
    fun `an item ref refuses outside any frame`() {
        val scope: EvalScope = MapEvalScope(MapStateStore())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("Iteration variable 'item'") == true)
    }

    @Test
    fun `an item ref through a handler's scope still refuses`() {
        val scope = handlerScope(mapOf("item" to Value.Int32(1)))

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("Iteration variable 'item'") == true)
    }

    /**
     * The frame the runtime wraps: the variable its slot's iteration declared, bound over the
     * scope everything else still reads through.
     */
    @Test
    fun `the frame binds the item name and nothing else`() {
        val scope = ItemScope(MapEvalScope(MapStateStore(mapOf(stateId to Value.Int32(3)))), "item", Value.Str("a"))

        assertEquals(Value.Str("a"), scope.read(RefTarget.Item("item")))
        assertEquals(Value.Int32(3), scope.read(RefTarget.State(stateId)))
    }

    @Test
    fun `the frame answers only its own variable`() {
        val scope = ItemScope(MapEvalScope(MapStateStore()), "row", Value.Str("a"))

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("Iteration variable 'item'") == true)
    }

    @Test
    fun `a nested frame's variable shadows the outer one`() {
        val inner = ItemScope(MapEvalScope(MapStateStore()), "item", Value.Str("inner"))
        val outer = ItemScope(inner, "item", Value.Str("outer"))

        assertEquals(Value.Str("outer"), outer.read(RefTarget.Item("item")))
    }

    @Test
    fun `two frames at different names both resolve`() {
        val silent =
            ItemScope(ItemScope(MapEvalScope(MapStateStore()), "row", Value.Str("row")), "item", Value.Str("a"))

        assertEquals(Value.Str("a"), silent.read(RefTarget.Item("item")))
        assertEquals(Value.Str("row"), silent.read(RefTarget.Item("row")))
    }

    @Test
    fun `an event arg resolves where a handler's scope binds it`() {
        val scope = handlerScope(mapOf("value" to Value.Str("typed")))

        assertEquals(Value.Str("typed"), scope.read(RefTarget.EventArg("value")))
    }

    @Test
    fun `an event arg the handler's scope does not bind refuses naming it`() {
        val scope = handlerScope(emptyMap())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.EventArg("value"))
        }
        assertTrue(failure.message?.contains("value") == true)
    }

    @Test
    fun `a handler's scope reads the state its inner scope holds`() {
        val scope = handlerScope(mapOf("value" to Value.Str("typed")))

        assertEquals(Value.Int32(3), scope.read(RefTarget.State(stateId)))
    }

    /** The binding the wrapper must not add: it answers an event's arguments and nothing else. */
    @Test
    fun `the event-arg wrapper does not adjust the item binding its inner frame carries`() {
        val scope = ItemScope(
            handlerScope(mapOf("value" to Value.Str("typed"))),
            "item",
            Value.Str("a"),
        )

        assertEquals(Value.Str("a"), scope.read(RefTarget.Item("item")))
        assertEquals(Value.Str("typed"), scope.read(RefTarget.EventArg("value")))
        assertEquals(Value.Int32(3), scope.read(RefTarget.State(stateId)))
    }

    private fun handlerScope(args: Map<String, Value>): EvalScope =
        EventArgScope(MapEvalScope(MapStateStore(mapOf(stateId to Value.Int32(3)))), args)
}
