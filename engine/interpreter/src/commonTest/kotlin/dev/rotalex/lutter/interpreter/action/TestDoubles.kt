// Doubles and two control primitives shared by the action tests: an environment that can be
// inspected afterwards, handlers that record or refuse, and a way to run a suspend function
// without a dispatcher. They live together because a test that reads one of them mid-flight
// is reading all of them.
package dev.rotalex.lutter.interpreter.action

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.env.DialogHost
import dev.rotalex.lutter.interpreter.env.HostFunctions
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** An environment a test can inspect afterwards. */
internal class FakeActionEnv(
    override val state: MapStateStore = MapStateStore(),
    override val scope: EvalScope = MapEvalScope(state),
    override val navigator: Navigator = GoingNowhere,
    override val dialogs: DialogHost = DialogHost.None,
    override val host: HostFunctions = HostFunctions.None,
) : ActionEnv

/** For the tests that are not about navigation. */
internal object GoingNowhere : Navigator {
    override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
    override fun back(): Boolean = false
}

/** Records its own effect, and optionally suspends before reporting done. */
internal class Recording(
    private val ran: MutableList<String>,
    private val label: String,
    private val after: suspend () -> Unit = {},
) : ActionHandler {
    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        ran += label
        after()
        return ActionOutcome.Done
    }
}

/** Records its own effect and then fails, which is what ends a sequence. */
internal class Refusing(
    private val ran: MutableList<String>,
    private val label: String,
    private val message: String,
) : ActionHandler {
    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        ran += label
        return ActionOutcome.Failed(RuntimeDiagnostic(message))
    }
}

/** Keeps the dialog slot it was handed, so a test can see the environment reach a handler. */
internal class ReadingDialogs : ActionHandler {
    var seen: DialogHost? = null

    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        seen = env.dialogs
        return ActionOutcome.Done
    }
}

/** Resolves a host function by name, calls it, and keeps whatever came back. */
internal class CallingHost(private val results: MutableList<Value?>) : ActionHandler {
    override suspend fun execute(step: ActionStep, env: ActionEnv): ActionOutcome {
        val function = env.host.find("double")
            ?: return ActionOutcome.Failed(RuntimeDiagnostic("double is not registered"))

        results += function(listOf(Value.Int32(21)))
        return ActionOutcome.Done
    }
}

internal fun executorOf(vararg handlers: Pair<ActionId, ActionHandler>): ActionExecutor =
    ActionExecutor(mapOf(*handlers))

internal fun step(action: String): ActionStep = ActionStep(action = ActionId(action))

internal fun sequenceOfSteps(vararg steps: ActionStep): ActionSequence = ActionSequence(steps.toList())

internal fun branching(action: String, vararg arms: Pair<BranchName, ActionSequence>): ActionStep =
    ActionStep(action = ActionId(action), branches = arms.toMap())

internal fun arm(name: String, vararg actions: String): Pair<BranchName, ActionSequence> =
    BranchName(name) to ActionSequence(actions.map { step(it) })

/**
 * A suspension a test releases by hand.
 *
 * Hand-rolled because the module's only coroutine dependency is the core library: there is no
 * test dispatcher here to name, and a core-only `startCoroutine` needs no dispatcher at all.
 */
internal class Gate {
    private var waiting: Continuation<Unit>? = null

    /** Suspends the calling step until [open]. */
    suspend fun await(): Unit = suspendCoroutine { waiting = it }

    /** Lets the suspended step continue; everything after it runs before this returns. */
    fun open(): Unit = waiting!!.resume(Unit)
}

/**
 * Runs [block] to its first suspension and returns what it produced, if it finished.
 *
 * The list is empty while [block] is still suspended, so a test can assert on which side of a
 * suspension it is looking at. Releasing a [Gate] continues on the calling thread, which is
 * what lets a later assertion see the whole sequence.
 */
internal fun <T> drive(block: suspend () -> T): MutableList<T> {
    val produced = mutableListOf<T>()
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>): Unit { produced += result.getOrThrow() }
        },
    )
    return produced
}
