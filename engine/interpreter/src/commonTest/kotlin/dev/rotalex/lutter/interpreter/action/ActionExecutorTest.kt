package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.model.ids.ActionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Steps run in the order the document lists them, and the first failure ends the sequence.
 *
 * A step's arms are absent from every case here: they belong to the action whose spec declares
 * them, and `flow.if` is where they are run.
 */
class ActionExecutorTest {

    private val ran: MutableList<String> = mutableListOf()
    private val gate: Gate = Gate()

    @Test
    fun `steps run in the order the document lists them`() {
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.b") to Recording(ran, "b"),
            ActionId("test.c") to Recording(ran, "c"),
        )

        drive { executor.run(sequenceOfSteps(step("test.a"), step("test.b"), step("test.c")), FakeActionEnv()) }

        assertEquals(listOf("a", "b", "c"), ran)
    }

    @Test
    fun `a step after a suspended step has not started`() {
        val executor = executorWithASuspendedMiddleStep()

        drive { executor.run(sequenceOfSteps(step("test.a"), step("test.b"), step("test.c")), FakeActionEnv()) }

        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun `the steps after a suspended step run once it resumes`() {
        val executor = executorWithASuspendedMiddleStep()

        drive { executor.run(sequenceOfSteps(step("test.a"), step("test.b"), step("test.c")), FakeActionEnv()) }
        gate.open()

        assertEquals(listOf("a", "b", "b done", "c"), ran)
    }

    @Test
    fun `a sequence that reached its end reports that nothing stopped it`() {
        val executor = executorOf(ActionId("test.a") to Recording(ran, "a"))

        val outcome = drive { executor.run(sequenceOfSteps(step("test.a")), FakeActionEnv()) }.single()

        assertEquals(ActionOutcome.Done, outcome)
    }

    @Test
    fun `a failure stops the steps after it`() {
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.b") to Refusing(ran, "b", "b refused"),
            ActionId("test.c") to Recording(ran, "c"),
        )

        drive { executor.run(sequenceOfSteps(step("test.a"), step("test.b"), step("test.c")), FakeActionEnv()) }

        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun `a failure carries the diagnostic the handler raised`() {
        val executor = executorOf(ActionId("test.b") to Refusing(ran, "b", "b refused"))

        val outcome = drive { executor.run(sequenceOfSteps(step("test.b")), FakeActionEnv()) }.single()

        assertEquals(ActionOutcome.Failed(RuntimeDiagnostic("b refused")), outcome)
    }

    @Test
    fun `an action with no registered handler stops the run`() {
        val executor = executorOf(ActionId("test.a") to Recording(ran, "a"))

        val outcome = drive { executor.run(sequenceOfSteps(step("test.a"), step("test.z")), FakeActionEnv()) }.single()

        assertTrue(outcome is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for an unhandled action names the action`() {
        val executor = executorOf()

        val outcome =
            drive { executor.run(sequenceOfSteps(step("test.z")), FakeActionEnv()) }
                .single() as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("test.z"))
    }

    @Test
    fun `a step's arms are the action's to run, not the runner's`() {
        // The executor does not know what an arm is: only the action whose spec declares branches
        // can say which one a condition selects, so a generic runner taking both would make
        // `flow.if` run its two halves in sequence. `ConditionalHandlerTest` covers the arms.
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.then") to Recording(ran, "then"),
            ActionId("test.else") to Recording(ran, "else"),
        )
        val sequence = sequenceOfSteps(
            branching("test.a", arm("then", "test.then"), arm("else", "test.else")),
        )

        drive { executor.run(sequence, FakeActionEnv()) }

        assertEquals(listOf("a"), ran)
    }

    private fun executorWithASuspendedMiddleStep(): ActionExecutor = executorOf(
        ActionId("test.a") to Recording(ran, "a"),
        ActionId("test.b") to Recording(ran, "b", after = { gate.await(); ran += "b done" }),
        ActionId("test.c") to Recording(ran, "c"),
    )
}
