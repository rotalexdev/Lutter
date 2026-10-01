package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** State and params resolve; everything else refuses with a reason. */
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
}
