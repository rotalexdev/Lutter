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

    @Test
    fun `an iteration variable refuses as post-MVP`() {
        val scope: EvalScope = MapEvalScope(MapStateStore())

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("post-MVP") == true)
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
    fun `an iteration variable refuses through a handler's scope too`() {
        val scope = handlerScope(mapOf("item" to Value.Int32(1)))

        val failure = assertFailsWith<IllegalStateException> {
            scope.read(RefTarget.Item("item"))
        }
        assertTrue(failure.message?.contains("post-MVP") == true)
    }

    private fun handlerScope(args: Map<String, Value>): EvalScope =
        EventArgScope(MapEvalScope(MapStateStore(mapOf(stateId to Value.Int32(3)))), args)
}
