package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.model.ids.ActionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Steps run in the order the document lists them, and the first failure ends the sequence. */
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
    fun `a step's branches run after the step that carries them`() {
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.then") to Recording(ran, "then"),
            ActionId("test.else") to Recording(ran, "else"),
        )
        val sequence = sequenceOfSteps(
            branching("test.a", arm("then", "test.then"), arm("else", "test.else")),
        )

        drive { executor.run(sequence, FakeActionEnv()) }

        assertEquals(listOf("a", "then", "else"), ran)
    }

    @Test
    fun `a failure inside a branch stops the rest of that branch`() {
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.one") to Recording(ran, "one"),
            ActionId("test.two") to Refusing(ran, "two", "two refused"),
            ActionId("test.three") to Recording(ran, "three"),
        )
        val sequence = sequenceOfSteps(
            branching("test.a", arm("then", "test.one", "test.two", "test.three")),
        )

        drive { executor.run(sequence, FakeActionEnv()) }

        assertEquals(listOf("a", "one", "two"), ran)
    }

    @Test
    fun `a failure inside a branch stops the steps after its parent`() {
        val executor = executorOf(
            ActionId("test.a") to Recording(ran, "a"),
            ActionId("test.one") to Refusing(ran, "one", "one refused"),
            ActionId("test.b") to Recording(ran, "b"),
        )
        val sequence = sequenceOfSteps(
            branching("test.a", arm("then", "test.one")),
            step("test.b"),
        )

        drive { executor.run(sequence, FakeActionEnv()) }

        assertEquals(listOf("a", "one"), ran)
    }

    private fun executorWithASuspendedMiddleStep(): ActionExecutor = executorOf(
        ActionId("test.a") to Recording(ran, "a"),
        ActionId("test.b") to Recording(ran, "b", after = { gate.await(); ran += "b done" }),
        ActionId("test.c") to Recording(ran, "c"),
    )
}
