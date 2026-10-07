package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.interpreter.action.ActionOutcome
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.ScreenActionEnv
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §30's fixture: a `Button` labelled "Continue" whose `onClick` is `nav.navigate` to a second page.
 *
 * Two claims. The handler the analyzer resolved runs through `UiRuntime.runActions` and lands the
 * back stack on the page its step named, and the generator turns that same handler into a lambda
 * calling the generated navigator. What is deliberately not claimed here is that a *press* reaches
 * either one: the runtime has no click-to-handler seam, and `ConformanceTest` is where the
 * interaction script shows which side has one.
 */
class NavigationFixtureTest {

    @Test
    fun `the resolved handler navigates the stack onto the page its step named`() {
        val resolved = resolved()
        val button = assertNotNull(resolved.nodes[BUTTON], "no node '$BUTTON'")
        val sequence = assertNotNull(button.events[CLICK], "the button carries no '$CLICK' handler")
        val stack = BackStackNavigator(HOME)
        val environment = RuntimeEnvironment(navigator = stack)
        val store = MapStateStore()

        val outcome = drive {
            ConformanceHarness.runtime(ConformanceHarness.schema()).runActions(
                sequence,
                ScreenActionEnv(
                    screen = MapEvalScope(store),
                    state = store,
                    environment = environment,
                ),
                environment,
            )
        }

        assertEquals(ActionOutcome.Done, outcome.single(), "the step reported a failure")
        assertEquals(PROFILE, stack.current.page, "the stack did not move")
        // A push and not a replacement: one pop returning to the start page is the only thing
        // that tells `BackStackNavigator.navigate` from a navigation that overwrote the entry.
        assertTrue(stack.back(), "the stack did not grow")
        assertEquals(HOME, stack.current.page)
    }

    @Test
    fun `the generated screen names the route of the page the same document declares`() {
        val target = assertNotNull(resolved().pages[PROFILE], "no page '$PROFILE'")
        val screen = generate().content("screens/HomeScreen.kt")

        // The signature is the reason this fixture is generated outside the shared registry: a
        // screen that closes over a receiver declares it as a parameter with no default, so
        // §28.4's `{ modifier -> HomeScreen(modifier) }` cannot call one.
        assertTrue(
            screen.contains("public fun HomeScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {"),
            screen,
        )
        // The route is read off the resolved page rather than written here, so the two paths are
        // compared over one fact about the document rather than two agreeing literals.
        assertTrue(screen.contains("navigator.navigate(Route.${target.name})"), screen)
    }

    /** The fixture decoded and analyzed; a document with a finding would prove nothing below. */
    private fun resolved(): ResolvedDocument {
        val schema = ConformanceHarness.schema()
        val result = ConformanceHarness.analyze(schema, FIXTURE, NavigatingFixtureDocuments::json)

        assertTrue(result.diagnostics.isEmpty(), "fixture '$FIXTURE': ${result.diagnostics}")
        return assertNotNull(result.resolved, "fixture '$FIXTURE' resolved nothing")
    }

    /** The fixture regenerated, so a stale generated file fails here rather than passing quietly. */
    private fun generate(): CodegenResult {
        val schema = ConformanceHarness.schema()
        val files = KotlinGenerator(schema, CodegenOptions(PACKAGE + FIXTURE)).generate(resolved())

        assertTrue(
            files.diagnostics.none { it.severity == Severity.Error },
            "fixture '$FIXTURE': ${files.diagnostics}",
        )
        return files
    }

    private fun CodegenResult.content(path: String): String =
        files.files.firstOrNull { it.path == path }?.content
            ?: error("no '$path' in ${files.files.map { it.path }}")

    /**
     * Runs [block] to its first suspension, hand-rolled as `ScreenActionEnvTest` rolls its own:
     * the only coroutine dependency is the core library, and this needs no dispatcher.
     */
    private fun <T> drive(block: suspend () -> T): List<T> {
        val produced = mutableListOf<T>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>): Unit { produced += result.getOrThrow() }
            },
        )
        return produced
    }

    private companion object {
        const val FIXTURE: String = "button_navigate"
        const val PACKAGE: String = "dev.rotalex.lutter.generated."
        val HOME: PageId = PageId("p_home")
        val PROFILE: PageId = PageId("p_profile")
        val BUTTON: NodeId = NodeId("n_btn")
        val CLICK: EventKey = EventKey("onClick")
    }
}
