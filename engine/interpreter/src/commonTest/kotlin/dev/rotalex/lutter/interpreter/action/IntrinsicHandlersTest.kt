package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.EventArgScope
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.interpreter.env.SnackbarHost
import dev.rotalex.lutter.interpreter.eval.Evaluator
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
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

    private val count: StateId = StateId("s_count")

    private val flag: StateId = StateId("s_flag")

    private val items: StateId = StateId("s_items")

    private val handlers: Map<ActionId, ActionHandler> = IntrinsicHandlers.handlers(Evaluator())

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
    fun `a snackbar reaches the host with the message the step named`() {
        val host = RecordingSnackbars()

        outcomeOf(snackbaring(), envShowing(host))

        assertEquals(listOf("saved"), host.shown)
    }

    @Test
    fun `a snackbar message read from an event arg reaches the host`() {
        val host = RecordingSnackbars()
        val store = MapStateStore()
        val env = FakeActionEnv(
            state = store,
            scope = EventArgScope(MapEvalScope(store), mapOf("value" to Value.Str("typed"))),
            snackbars = host,
        )

        outcomeOf(snackbaring(computed(Expr.Ref(RefTarget.EventArg("value")))), env)

        assertEquals(listOf("typed"), host.shown)
    }

    @Test
    fun `a snackbar shown against the host that shows nothing is still done`() {
        assertEquals(ActionOutcome.Done, outcomeOf(snackbaring(), FakeActionEnv()))
    }

    @Test
    fun `two snackbars in one sequence both reach the host`() {
        val host = RecordingSnackbars()

        drive {
            ActionExecutor(handlers).run(
                sequenceOfSteps(snackbaring(), snackbaring(literal(Value.Str("again")))),
                envShowing(host),
            )
        }

        assertEquals(listOf("saved", "again"), host.shown)
    }

    @Test
    fun `a step with no message fails the run`() {
        assertTrue(outcomeOf(step("ui.showSnackbar"), FakeActionEnv()) is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for a snackbar with no message names the action`() {
        val outcome = outcomeOf(step("ui.showSnackbar"), FakeActionEnv()) as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("ui.showSnackbar"))
    }

    @Test
    fun `a message that is not a string fails the run`() {
        assertTrue(outcomeOf(snackbaring(literal(Value.Int32(7))), FakeActionEnv()) is ActionOutcome.Failed)
    }

    @Test
    fun `a host call with no name argument fails the run`() {
        assertTrue(outcomeOf(step("host.call"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a state write puts the value the step named into the store`() {
        val store = MapStateStore()

        outcomeOf(writing(literal(Value.Int32(7))), envOver(store))

        assertEquals(Value.Int32(7), store.get(count))
    }

    @Test
    fun `a state write evaluates a computed value against the environment`() {
        val store = MapStateStore(mapOf(count to Value.Int32(3)))
        val value = computed(
            Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1))),
        )

        outcomeOf(writing(value), envOver(store))

        assertEquals(Value.Int32(4), store.get(count))
    }

    @Test
    fun `a state write takes its value from an event arg`() {
        val store = MapStateStore()
        val env = FakeActionEnv(
            state = store,
            scope = EventArgScope(MapEvalScope(store), mapOf("value" to Value.Str("typed"))),
        )

        outcomeOf(writing(computed(Expr.Ref(RefTarget.EventArg("value")))), env)

        assertEquals(Value.Str("typed"), store.get(count))
    }

    @Test
    fun `a value the operator rows refuse fails the run`() {
        val store = MapStateStore(mapOf(count to Value.Int32(3)))
        val refused = computed(
            Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Str("x"))),
        )

        assertTrue(outcomeOf(writing(refused), envOver(store)) is ActionOutcome.Failed)
    }

    @Test
    fun `a target that is not a state reference fails the run`() {
        val target = literal(Value.Str("s_count"))

        assertTrue(outcomeOf(writing(literal(Value.Int32(1)), target), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a target computed by a call fails the run`() {
        val target = computed(Expr.Call(FunctionId("test.target"), emptyList()))

        assertTrue(outcomeOf(writing(literal(Value.Int32(1)), target), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a target naming a param rather than a state fails the run`() {
        val target = computed(Expr.Ref(RefTarget.Param(ParamName("count"))))

        assertTrue(outcomeOf(writing(literal(Value.Int32(1)), target), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for an unusable target names the action`() {
        val target = literal(Value.Str("s_count"))

        val outcome = outcomeOf(writing(literal(Value.Int32(1)), target), Going()) as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("state.set"))
    }

    @Test
    fun `a step with no target fails the run`() {
        assertTrue(outcomeOf(step("state.set"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a step with no value fails the run`() {
        val target = computed(Expr.Ref(RefTarget.State(count)))

        assertTrue(outcomeOf(writing(null, target), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a toggle negates the Bool the target names`() {
        val store = MapStateStore(mapOf(flag to Value.Bool(true)))

        outcomeOf(toggling(), envOver(store))

        assertEquals(Value.Bool(false), store.get(flag))
    }

    @Test
    fun `a toggle of a false Bool flips it true`() {
        val store = MapStateStore(mapOf(flag to Value.Bool(false)))

        outcomeOf(toggling(), envOver(store))

        assertEquals(Value.Bool(true), store.get(flag))
    }

    @Test
    fun `a toggle of a target that is not a Boolean fails the run`() {
        val store = MapStateStore(mapOf(flag to Value.Str("yes")))

        assertTrue(outcomeOf(toggling(), envOver(store)) is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for an untoggleable target names the action`() {
        val store = MapStateStore(mapOf(flag to Value.Str("yes")))

        val outcome = outcomeOf(toggling(), envOver(store)) as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("state.toggle"))
    }

    @Test
    fun `a toggle with no target fails the run`() {
        assertTrue(outcomeOf(step("state.toggle"), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `an append adds the value at the end of the list`() {
        val store = MapStateStore(mapOf(items to Value.ListOf(listOf(Value.Int32(1)))))

        outcomeOf(appending(literal(Value.Int32(2))), envOver(store))

        assertEquals(Value.ListOf(listOf(Value.Int32(1), Value.Int32(2))), store.get(items))
    }

    @Test
    fun `an append against a target that is not a list fails the run`() {
        val store = MapStateStore(mapOf(items to Value.Int32(3)))

        assertTrue(outcomeOf(appending(literal(Value.Int32(2))), envOver(store)) is ActionOutcome.Failed)
    }

    @Test
    fun `the diagnostic for a non-list append target names the action`() {
        val store = MapStateStore(mapOf(items to Value.Int32(3)))

        val outcome = outcomeOf(appending(literal(Value.Int32(2))), envOver(store)) as ActionOutcome.Failed

        assertTrue(outcome.diagnostic.message.contains("list.append"))
    }

    @Test
    fun `an append with no value fails the run`() {
        assertTrue(outcomeOf(appending(null), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a remove takes every occurrence of the value out of the list`() {
        val store = MapStateStore(
            mapOf(items to Value.ListOf(listOf(Value.Int32(1), Value.Int32(2), Value.Int32(1)))),
        )

        outcomeOf(removing(literal(Value.Int32(1))), envOver(store))

        assertEquals(Value.ListOf(listOf(Value.Int32(2))), store.get(items))
    }

    @Test
    fun `a remove of a value the list does not hold is done`() {
        val store = MapStateStore(mapOf(items to Value.ListOf(listOf(Value.Int32(1)))))

        assertEquals(
            ActionOutcome.Done,
            outcomeOf(removing(literal(Value.Int32(9))), envOver(store)),
        )
        assertEquals(Value.ListOf(listOf(Value.Int32(1))), store.get(items))
    }

    @Test
    fun `a remove against a target that is not a list fails the run`() {
        val store = MapStateStore(mapOf(items to Value.Str("rows")))

        assertTrue(outcomeOf(removing(literal(Value.Int32(1))), envOver(store)) is ActionOutcome.Failed)
    }

    @Test
    fun `a dialog reaches the host with the title and message the step named`() {
        val host = RecordingDialogs()

        outcomeOf(dialoging(), FakeActionEnv(dialogs = host))

        assertEquals(listOf("dialog:Save|Are you sure"), host.shown)
    }

    @Test
    fun `a sheet reaches the same host through its sheet member`() {
        val host = RecordingDialogs()

        outcomeOf(sheeting(), FakeActionEnv(dialogs = host))

        assertEquals(listOf("sheet:Save|Are you sure"), host.shown)
    }

    @Test
    fun `a dialog against the host that shows nothing is still done`() {
        assertEquals(ActionOutcome.Done, outcomeOf(dialoging(), FakeActionEnv()))
    }

    @Test
    fun `a dialog with no title fails the run`() {
        val args = mapOf(PropertyKey("message") to literal(Value.Str("Are you sure")))

        assertTrue(outcomeOf(step("ui.showDialog").copy(args = args), Going()) is ActionOutcome.Failed)
    }

    @Test
    fun `a dialog title that is not a string fails the run`() {
        val args = mapOf(
            PropertyKey("title") to literal(Value.Int32(7)),
            PropertyKey("message") to literal(Value.Str("Are you sure")),
        )

        assertTrue(outcomeOf(step("ui.showDialog").copy(args = args), Going()) is ActionOutcome.Failed)
    }

    private fun outcomeOf(step: ActionStep, going: Going): ActionOutcome =
        outcomeOf(step, FakeActionEnv(navigator = going))

    private fun outcomeOf(step: ActionStep, env: ActionEnv): ActionOutcome =
        drive { ActionExecutor(handlers).run(sequenceOfSteps(step), env) }.single()

    /** An environment reading and writing one store, which is what a screen hands a handler. */
    private fun envOver(store: MapStateStore): FakeActionEnv =
        FakeActionEnv(state = store, scope = MapEvalScope(store))

    /** `state.set(target = <computed state ref>, value = …)`, as a document writes it. */
    private fun writing(value: PropertyValue?, target: PropertyValue = reads()): ActionStep {
        val args = mutableMapOf<PropertyKey, PropertyValue>(PropertyKey("target") to target)
        if (value != null) args[PropertyKey("value")] = value
        return step("state.set").copy(args = args)
    }

    private fun envShowing(snackbars: SnackbarHost): FakeActionEnv = FakeActionEnv(snackbars = snackbars)

    /** `ui.showSnackbar(message = …)`, as a document writes it. */
    private fun snackbaring(message: PropertyValue = literal(Value.Str("saved"))): ActionStep =
        step("ui.showSnackbar").copy(args = mapOf(PropertyKey("message") to message))

    /** `state.toggle(target = <computed state ref>)`, as a document writes it. */
    private fun toggling(target: PropertyValue = reads(flag)): ActionStep =
        step("state.toggle").copy(args = mapOf(PropertyKey("target") to target))

    /** `list.append(target = <computed state ref>, value = …)`, as a document writes it. */
    private fun appending(value: PropertyValue?, target: PropertyValue = reads(items)): ActionStep =
        valueAction("list.append", value, target)

    /** `list.remove(target = <computed state ref>, value = …)`, as a document writes it. */
    private fun removing(value: PropertyValue?, target: PropertyValue = reads(items)): ActionStep =
        valueAction("list.remove", value, target)

    /** The shape the two list actions share: a state target and a value, both required. */
    private fun valueAction(id: String, value: PropertyValue?, target: PropertyValue): ActionStep {
        val args = mutableMapOf<PropertyKey, PropertyValue>(PropertyKey("target") to target)
        if (value != null) args[PropertyKey("value")] = value
        return step(id).copy(args = args)
    }

    /** `ui.showDialog(title = …, message = …)`, as a document writes it. */
    private fun dialoging(): ActionStep = presenting("ui.showDialog")

    /** `ui.showBottomSheet(title = …, message = …)`, the sheet form. */
    private fun sheeting(): ActionStep = presenting("ui.showBottomSheet")

    private fun presenting(id: String): ActionStep = step(id).copy(
        args = mapOf(
            PropertyKey("title") to literal(Value.Str("Save")),
            PropertyKey("message") to literal(Value.Str("Are you sure")),
        ),
    )

    /** The state read a target argument carries: the same computed form every write names. */
    private fun reads(state: StateId = count): PropertyValue =
        computed(Expr.Ref(RefTarget.State(state)))

    private fun literal(value: Value): PropertyValue = PropertyValue.Const(value)

    private fun computed(expr: Expr): PropertyValue = PropertyValue.Computed(expr)

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

        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())

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
