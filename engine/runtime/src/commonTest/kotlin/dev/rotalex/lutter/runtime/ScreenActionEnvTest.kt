package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.env.DialogHost
import dev.rotalex.lutter.interpreter.env.HostFunction
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.interpreter.env.SnackbarHost
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The environment a document's handler runs against, and where a failure it reports ends up.
 *
 * The store is the interpreter's own rather than a snapshot one: which store a screen holds is
 * the screen's business, and the wiring this covers does not change with the implementation.
 */
class ScreenActionEnvTest {

    private val count: StateId = StateId("s_count")

    /** Only here so the host map is not empty: an empty one would compare equal either way. */
    private val doubling: HostFunction = { args -> args.single() }

    @Test
    fun `the environment's navigator is the one the runtime was given`() {
        val going = Going()

        assertEquals(going, env(RuntimeEnvironment(going)).navigator)
    }

    @Test
    fun `the environment's host functions are the ones the runtime was given`() {
        val host = HostFunctions(mapOf("echo" to doubling))
        val environment = RuntimeEnvironment(Going(), host)

        assertEquals(host, env(environment).host)
    }

    @Test
    fun `an environment shows no dialog, because nothing can raise one yet`() {
        assertEquals(DialogHost.None, env().dialogs)
    }

    @Test
    fun `an environment shows no snackbar, because nothing renders one yet`() {
        assertEquals(SnackbarHost.None, env().snackbars)
    }

    @Test
    fun `a handler reads the state the store it was given holds`() {
        val store = MapStateStore(mapOf(count to Value.Int32(3)))

        assertEquals(Value.Int32(3), env(store = store).scope.read(RefTarget.State(count)))
    }

    @Test
    fun `an event arg resolves where the environment binds it`() {
        val store = MapStateStore()
        val env = env(store = store, eventArgs = mapOf("value" to Value.Str("typed")))

        assertEquals(Value.Str("typed"), env.scope.read(RefTarget.EventArg("value")))
    }

    @Test
    fun `a handler writes the state the step named into the store it was given`() {
        val store = MapStateStore()
        val environment = RuntimeEnvironment(Going())
        val env = env(environment, store, mapOf("value" to Value.Str("typed")))

        runActions(writing(), env, environment)

        assertEquals(Value.Str("typed"), store.get(count))
    }

    @Test
    fun `a failure reaches the diagnostics sink the environment was given`() {
        val seen = mutableListOf<RuntimeDiagnostic>()
        val environment = RuntimeEnvironment(Going(), diagnostics = { seen += it })

        runActions(ActionStep(ActionId("nav.navigate")), env(environment), environment)

        assertTrue(seen.single().message.contains("nav.navigate"))
    }

    private fun env(
        environment: RuntimeEnvironment = RuntimeEnvironment(Going()),
        store: MapStateStore = MapStateStore(),
        eventArgs: Map<String, Value> = emptyMap(),
    ): ScreenActionEnv = ScreenActionEnv(
        screen = MapEvalScope(store),
        state = store,
        environment = environment,
        eventArgs = eventArgs,
    )

    private fun runActions(step: ActionStep, env: ScreenActionEnv, environment: RuntimeEnvironment) {
        drive { runtime().runActions(ActionSequence(listOf(step)), env, environment) }
    }

    /** `state.set(event.value)`: the value is read from the event, the target names the state. */
    private fun writing(): ActionStep = ActionStep(
        action = ActionId("state.set"),
        args = mapOf(
            PropertyKey("target") to PropertyValue.Computed(Expr.Ref(RefTarget.State(count))),
            PropertyKey("value") to PropertyValue.Computed(Expr.Ref(RefTarget.EventArg("value"))),
        ),
    )

    private fun runtime(): UiRuntime = UiRuntime(
        RendererRegistryBuilder().build(),
        ModifierApplierRegistryBuilder().build(),
        Implementations.None,
        Schema.build<ComponentSpec, ModifierSpec, String, String, String> {},
    )

    /**
     * Runs [block] to its first suspension and returns what it produced.
     *
     * Hand-rolled for the reason the interpreter's own tests roll theirs: the only coroutine
     * dependency is the core library, and `startCoroutine` needs no dispatcher to run through.
     */
    private fun <T> drive(block: suspend () -> T): MutableList<T> {
        val produced = mutableListOf<T>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>): Unit { produced += result.getOrThrow() }
            },
        )
        return produced
    }

    private class Going : Navigator {
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}
