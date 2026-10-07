package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `host.call` reaches the function its name argument spells, with the arguments it carries.
 *
 * It is the one MVP action whose callee may suspend, so the ordering cases here are about a
 * sequence crossing a real suspension rather than about the call itself.
 */
class HostCallHandlerTest {

    private val ran: MutableList<String> = mutableListOf()
    private val received: MutableList<List<Value>> = mutableListOf()

    @Test
    fun `a call reaches the function its name argument spells`() {
        val env = FakeActionEnv(
            host = HostFunctions(
                mapOf(
                    "submit" to AnsweringHost(received, "submit", ran = ran).function,
                    "other" to AnsweringHost(received, "other", ran = ran).function,
                ),
            ),
        )

        drive { run(calling("submit"), env) }

        assertEquals(listOf("submit"), ran)
    }

    @Test
    fun `the function receives the arguments in the order the step lists them`() {
        val host = AnsweringHost(received, "submit")
        val step = calling("submit", items(Value.Str("a-1"), Value.Int32(2)))

        drive { run(step, envOver(host)) }

        assertEquals(listOf(listOf(Value.Str("a-1"), Value.Int32(2))), received)
    }

    @Test
    fun `a step carrying no argument list passes no arguments`() {
        val host = AnsweringHost(received, "submit")

        drive { run(calling("submit"), envOver(host)) }

        assertEquals(listOf(emptyList<Value>()), received)
    }

    @Test
    fun `a computed argument list is evaluated before it is passed`() {
        val host = AnsweringHost(received, "submit")
        val computed = PropertyValue.Computed(
            Expr.ListLiteral(listOf(Expr.Const(Value.Str("a-1")), Expr.Const(Value.Int32(2)))),
        )

        drive { run(calling("submit", computed), envOver(host)) }

        assertEquals(listOf(listOf(Value.Str("a-1"), Value.Int32(2))), received)
    }

    @Test
    fun `a function that returns nothing still reports the step as done`() {
        val host = AnsweringHost(received, "submit", answer = null)

        val outcome = drive { run(calling("submit"), envOver(host)) }.single()

        assertEquals(ActionOutcome.Done, outcome)
    }

    @Test
    fun `a name no host function answers to fails the run`() {
        assertIs<ActionOutcome.Failed>(drive { run(calling("absent"), FakeActionEnv()) }.single())
    }

    @Test
    fun `the diagnostic for a name nothing answers to names the action`() {
        val outcome = drive { run(calling("absent"), FakeActionEnv()) }.single() as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("host.call"))
    }

    @Test
    fun `the diagnostic for a name nothing answers to names the function`() {
        val outcome = drive { run(calling("absent"), FakeActionEnv()) }.single() as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("absent"))
    }

    @Test
    fun `a step with no name argument fails the run`() {
        assertIs<ActionOutcome.Failed>(drive { run(step("host.call"), FakeActionEnv()) }.single())
    }

    @Test
    fun `a name that is not a string fails the run`() {
        val step = calling("submit").copy(args = mapOf(NAME to PropertyValue.Const(Value.Int32(7))))

        assertIs<ActionOutcome.Failed>(drive { run(step, FakeActionEnv()) }.single())
    }

    @Test
    fun `an argument list that is not a list fails the run`() {
        val step = calling("submit", PropertyValue.Const(Value.Int32(2)))

        assertIs<ActionOutcome.Failed>(drive { run(step, FakeActionEnv()) }.single())
    }

    @Test
    fun `a name no expression produces fails the run`() {
        val unanswered = PropertyValue.Computed(Expr.Call(FunctionId("test.missing"), emptyList()))
        val step = calling("submit").copy(args = mapOf(NAME to unanswered))

        assertIs<ActionOutcome.Failed>(drive { run(step, FakeActionEnv()) }.single())
    }

    @Test
    fun `a function that suspends is awaited before the next step`() {
        val gate = Gate()
        val host = AnsweringHost(received, "submit", before = { gate.await() })
        val executor = engineExecutor(ActionId("test.after") to Recording(ran, "after"))

        drive { executor.run(sequenceOfSteps(calling("submit"), step("test.after")), envOver(host)) }

        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun `the steps after a resumed host call run in order`() {
        val gate = Gate()
        val host = AnsweringHost(received, "submit", before = { gate.await() }, ran = ran)
        val executor = engineExecutor(ActionId("test.after") to Recording(ran, "after"))

        drive { executor.run(sequenceOfSteps(calling("submit"), step("test.after")), envOver(host)) }
        gate.open()

        assertEquals(listOf("submit", "after"), ran)
    }

    @Test
    fun `a failure after a resumed host call stops the steps after it`() {
        val gate = Gate()
        val host = AnsweringHost(received, "submit", before = { gate.await() }, ran = ran)
        val executor = engineExecutor(
            ActionId("test.one") to Recording(ran, "one"),
            ActionId("test.two") to Refusing(ran, "two", "two refused"),
            ActionId("test.three") to Recording(ran, "three"),
        )
        val sequence = sequenceOfSteps(
            calling("submit"),
            step("test.one"),
            step("test.two"),
            step("test.three"),
        )

        drive { executor.run(sequence, envOver(host)) }
        gate.open()

        assertEquals(listOf("submit", "one", "two"), ran)
    }

    private suspend fun run(step: ActionStep, env: ActionEnv): ActionOutcome =
        engineExecutor().run(sequenceOfSteps(step), env)

    /** An environment registering [host] under the name the step spells. */
    private fun envOver(host: AnsweringHost): ActionEnv =
        FakeActionEnv(host = HostFunctions(mapOf("submit" to host.function)))

    private fun calling(name: String, args: PropertyValue? = null): ActionStep {
        val written = mutableMapOf<PropertyKey, PropertyValue>(NAME to PropertyValue.Const(Value.Str(name)))
        if (args != null) written[ARGS] = args
        return step("host.call").copy(args = written)
    }

    private fun items(vararg values: Value): PropertyValue = PropertyValue.Const(Value.ListOf(values.toList()))

    private companion object {
        val NAME: PropertyKey = PropertyKey("name")
        val ARGS: PropertyKey = PropertyKey("args")
    }
}
