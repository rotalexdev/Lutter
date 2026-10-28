package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `flow.if` chooses one arm and runs it, and nothing else runs one.
 *
 * Every case goes through an executor built from the table the engine ships, because the arm is run
 * by recursing through the same executor the step came from — a handler that ran its arms any other
 * way would not be the thing the runtime builds.
 */
class ConditionalHandlerTest {

    private val ran: MutableList<String> = mutableListOf()

    @Test
    fun `a true condition runs the then arm`() {
        val executor = executorWithArms()

        drive { executor.run(sequenceOfSteps(conditional(true)), FakeActionEnv()) }

        assertEquals(listOf("then"), ran)
    }

    @Test
    fun `a false condition runs the else arm`() {
        val executor = executorWithArms()

        drive { executor.run(sequenceOfSteps(conditional(false)), FakeActionEnv()) }

        assertEquals(listOf("else"), ran)
    }

    @Test
    fun `a condition read from state decides the arm`() {
        val executor = executorWithArms()
        val store = MapStateStore(mapOf(FLAG to Value.Bool(false)))
        val step = conditional(true, computed(Expr.Ref(RefTarget.State(FLAG))))

        drive {
            executor.run(sequenceOfSteps(step), FakeActionEnv(state = store, scope = MapEvalScope(store)))
        }

        assertEquals(listOf("else"), ran)
    }

    @Test
    fun `a false condition with no else arm reports the step as done`() {
        val executor = executorWithArms()
        val step = conditional(false).copy(branches = mapOf(THEN to sequenceOfSteps(step("test.then"))))

        val outcome = drive { executor.run(sequenceOfSteps(step), FakeActionEnv()) }.single()

        assertEquals(ActionOutcome.Done, outcome)
    }

    @Test
    fun `a true condition with no then arm fails the run`() {
        val executor = executorWithArms()
        val step = conditional(true).copy(branches = mapOf(ELSE to sequenceOfSteps(step("test.else"))))

        assertIs<ActionOutcome.Failed>(drive { executor.run(sequenceOfSteps(step), FakeActionEnv()) }.single())
    }

    @Test
    fun `a condition that is not a boolean fails the run`() {
        val executor = executorWithArms()

        val outcome = drive {
            executor.run(sequenceOfSteps(conditional(true, literal(Value.Int32(1)))), FakeActionEnv())
        }.single()

        assertIs<ActionOutcome.Failed>(outcome)
    }

    @Test
    fun `a condition whose expression refuses fails the run`() {
        val executor = executorWithArms()
        val refused = computed(Expr.Call(FunctionId("test.missing"), emptyList()))

        val outcome = drive {
            executor.run(sequenceOfSteps(conditional(true, refused)), FakeActionEnv())
        }.single()

        assertIs<ActionOutcome.Failed>(outcome)
    }

    @Test
    fun `a step with no condition fails the run`() {
        val executor = executorWithArms()

        assertIs<ActionOutcome.Failed>(
            drive { executor.run(sequenceOfSteps(step("flow.if")), FakeActionEnv()) }.single(),
        )
    }

    @Test
    fun `the diagnostic for an unusable condition names the action`() {
        val executor = executorWithArms()
        val step = conditional(true, literal(Value.Int32(1)))

        val outcome = drive { executor.run(sequenceOfSteps(step), FakeActionEnv()) }.single() as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("flow.if"))
    }

    @Test
    fun `a failure inside the taken arm stops the steps after it`() {
        val executor = engineExecutor(
            ActionId("test.one") to Refusing(ran, "one", "one refused"),
            ActionId("test.two") to Recording(ran, "two"),
            ActionId("test.after") to Recording(ran, "after"),
        )
        val step = conditional(true).copy(
            branches = mapOf(THEN to sequenceOfSteps(step("test.one"), step("test.two"))),
        )

        drive { executor.run(sequenceOfSteps(step, step("test.after")), FakeActionEnv()) }

        assertEquals(listOf("one"), ran)
    }

    @Test
    fun `the arm not taken runs nothing`() {
        val executor = executorWithArms()

        drive { executor.run(sequenceOfSteps(conditional(true)), FakeActionEnv()) }

        assertEquals(listOf("then"), ran)
    }

    @Test
    fun `a nested conditional runs the arm its own condition takes`() {
        val executor = executorWithArms()
        val inner = conditional(false)
        val outer = conditional(true).copy(branches = mapOf(THEN to sequenceOfSteps(inner)))

        drive { executor.run(sequenceOfSteps(outer), FakeActionEnv()) }

        assertEquals(listOf("else"), ran)
    }

    /** Both arms' steps, so a case can say which one ran without declaring the other's handler. */
    private fun executorWithArms(): ActionExecutor = engineExecutor(
        ActionId("test.then") to Recording(ran, "then"),
        ActionId("test.else") to Recording(ran, "else"),
    )

    private fun conditional(holds: Boolean, cond: PropertyValue = literal(Value.Bool(holds))): ActionStep =
        step("flow.if").copy(
            args = mapOf(COND to cond),
            branches = mapOf(
                THEN to sequenceOfSteps(step("test.then")),
                ELSE to sequenceOfSteps(step("test.else")),
            ),
        )

    private fun literal(value: Value): PropertyValue = PropertyValue.Const(value)

    private fun computed(expr: Expr): PropertyValue = PropertyValue.Computed(expr)

    private companion object {
        val COND: PropertyKey = PropertyKey("cond")
        val THEN: BranchName = BranchName("then")
        val ELSE: BranchName = BranchName("else")
        val FLAG: StateId = StateId("s_flag")
    }
}
