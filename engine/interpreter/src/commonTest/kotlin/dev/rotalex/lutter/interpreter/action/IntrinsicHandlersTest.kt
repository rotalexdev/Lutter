package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine's own handlers, run through an [ActionExecutor] built from the table they ship in.
 *
 * Every case goes through the executor rather than calling a handler directly, because the table
 * is the deliverable: a handler that works and was never registered would pass a direct call.
 */
class IntrinsicHandlersTest {

    @Test
    fun `a navigation reaches the navigator as the page the step named`() {
        val went = Going()

        outcomeOf(navigatingTo("p_profile"), went)

        assertEquals(PageId("p_profile"), went.opened)
    }

    @Test
    fun `a navigation passes no arguments beside the page`() {
        val went = Going()

        outcomeOf(navigatingTo("p_profile"), went)

        assertEquals(emptyMap<ParamName, Value>(), went.args)
    }

    @Test
    fun `a page written as a computed literal is the page it wraps`() {
        val went = Going()

        outcomeOf(
            step("nav.navigate").copy(
                args = mapOf(
                    PropertyKey("page") to
                        PropertyValue.Computed(Expr.Const(Value.Ref(RefKind.Page, "p_profile"))),
                ),
            ),
            went,
        )

        assertEquals(PageId("p_profile"), went.opened)
    }

    /**
     * The boundary of the argument a page may be written as: a literal, spelled either way.
     *
     * A named read cannot produce a page — there is no `RefTarget` for one — and `ActionEnv`
     * carries neither an evaluator nor the checked expression a call would need.
     */
    @Test
    fun `a page computed by a call fails the run`() {
        val computed = step("nav.navigate").copy(
            args = mapOf(
                PropertyKey("page") to
                    PropertyValue.Computed(Expr.Call(FunctionId("test.page"), emptyList())),
            ),
        )

        assertTrue(outcomeOf(computed, Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a step with no page argument fails the run`() {
        assertTrue(outcomeOf(step("nav.navigate"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `an argument that is not a reference fails the run`() {
        assertTrue(outcomeOf(navigatingTo(Value.Str("p_profile")), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a reference that is not a page fails the run`() {
        assertTrue(outcomeOf(navigatingTo(Value.Ref(RefKind.Resource, "r_logo")), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `an id the model refuses fails the run rather than throwing`() {
        assertTrue(outcomeOf(navigatingTo("not a page id"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for an unusable page names the action`() {
        val outcome = outcomeOf(step("nav.navigate"), Going()) as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("nav.navigate"))
    }

    @Test
    fun `a back asks the navigator to step back`() {
        val went = Going()

        outcomeOf(step("nav.back"), went)

        assertEquals(1, went.backs)
    }

    @Test
    fun `a back the navigator refuses still reports the step as done`() {
        assertEquals(ActionOutcome.Done, outcomeOf(step("nav.back"), Going()))
    }

    @Test
    fun `showing a snackbar has no handler`() {
        assertTrue(outcomeOf(step("ui.showSnackbar"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `writing state has no handler`() {
        assertTrue(outcomeOf(step("state.set"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `calling a host function has no handler`() {
        assertTrue(outcomeOf(step("host.call"), Going()) is ActionOutcome.Failed)
    }

    private fun outcomeOf(step: ActionStep, going: Going): ActionOutcome {
        val executor = ActionExecutor(IntrinsicHandlers.handlers)
        val env = FakeActionEnv(navigator = going)
        return drive { executor.run(sequenceOfSteps(step), env) }.single()
    }

    private fun navigatingTo(id: String): ActionStep =
        navigatingTo(Value.Ref(RefKind.Page, id))

    private fun navigatingTo(value: Value): ActionStep =
        step("nav.navigate").copy(
            args = mapOf(PropertyKey("page") to PropertyValue.Const(value)),
        )

    /** The navigator every case runs against, recording what reached it. */
    private class Going : Navigator {
        var opened: PageId? = null
        var args: Map<ParamName, Value> = emptyMap()
        var backs: Int = 0

        override fun navigate(page: PageId, args: Map<ParamName, Value>) {
            opened = page
            this.args = args
        }

        override fun back(): Boolean {
            backs++
            return false
        }
    }
}
