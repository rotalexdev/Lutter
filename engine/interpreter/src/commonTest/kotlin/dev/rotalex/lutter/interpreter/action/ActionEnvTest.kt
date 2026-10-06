package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.env.DialogHost
import dev.rotalex.lutter.interpreter.env.HostFunction
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each slot on the environment is reachable from a handler, and the state one writes through. */
class ActionEnvTest {

    private val stateId: StateId = StateId("s_count")

    @Test
    fun `a write through the environment is readable through its scope`() {
        val env = FakeActionEnv()

        env.state.set(stateId, Value.Int32(3))

        assertEquals(Value.Int32(3), env.scope.read(RefTarget.State(stateId)))
    }

    @Test
    fun `the environment writes into the store it was given`() {
        val store = MapStateStore()

        FakeActionEnv(state = store).state.set(stateId, Value.Int32(3))

        assertEquals(Value.Int32(3), store.get(stateId))
    }

    @Test
    fun `an environment that registers nothing shows no dialog`() {
        assertEquals(DialogHost.None, FakeActionEnv().dialogs)
    }

    @Test
    fun `a handler sees the dialog host the environment was given`() {
        val dialogs: DialogHost = object : DialogHost {}
        val handler = ReadingDialogs()
        val executor = executorOf(ActionId("test.read") to handler)

        drive { executor.run(sequenceOfSteps(step("test.read")), FakeActionEnv(dialogs = dialogs)) }

        assertEquals(dialogs, handler.seen)
    }

    @Test
    fun `a host function found by name runs on the arguments it is given`() {
        val doubled: HostFunction = { args -> Value.Int32((args.single() as Value.Int32).v * 2) }
        val results = mutableListOf<Value?>()
        val handler = CallingHost(results)
        val executor = executorOf(ActionId("test.call") to handler)
        val env = FakeActionEnv(host = HostFunctions(mapOf("double" to doubled)))

        drive { executor.run(sequenceOfSteps(step("test.call")), env) }

        assertEquals(listOf<Value?>(Value.Int32(42)), results)
    }
}
