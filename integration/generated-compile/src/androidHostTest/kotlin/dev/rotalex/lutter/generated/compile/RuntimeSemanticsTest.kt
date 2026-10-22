package dev.rotalex.lutter.generated.compile

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.runtime.UiScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PLAN §36.1 bullet 7's Android half: the runtime renders with Compose on the Android unit
 * runner, without a device.
 *
 * The desktop half lives in `ConformanceTest`, where the desktop test runner runs headless and
 * the runtime and the generated code are compared per fixture — one semantics tree and one set
 * of pixels. That comparison already ran the runtime over every fixture, but on a JVM that
 * could at best imitate Android; the claim the plan asks for names both runners. This class is
 * the runner that actually boots Android: Robolectric supplies the framework, the androidx
 * junit4 compose test rule supplies the activity the composition lives in, and the fixtures
 * are drawn and driven through the same `ConformanceHarness` assembly the desktop side uses.
 *
 * Pixel capture is deliberately absent here. It is `ConformanceTest`'s claim and desktop
 * robolectric-supported image capture would only restate it on a different backend; what the
 * Android runner adds is a real framework path, so the rows assert what only this runner can
 * fail: that the composition builds under the actual activity/graph stack and that the §30
 * press moves the runtime's own stack.
 *
 * SDK 36 is pinned: it is the level this Robolectric release boots cleanly, and it sits inside
 * the 26–37 range the module compiles against, so the framework surface the fixtures reach is
 * real Android behavior, not a forward preview.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RuntimeSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        val HOME: PageId = PageId("p_home")
        val PROFILE: PageId = PageId("p_profile")
        const val NAVIGATING: String = "button_navigate"
        const val CONTINUE: String = "Continue"
    }

    /**
     * Every shared fixture renders under the runtime: the analyzer resolves it, the renderer
     * composes it, and the resulting tree carries text, so nothing along that path stopped at
     * a stub only the desktop runner would have furnished.
     *
     * One composition serves the whole loop rather than one `setContent` per fixture: this
     * runner allows a single `setContent` per activity (the second one fails with "has already
     * set content"), so the fixtures drive a recomposition instead — the same move the desktop
     * side expresses by re-running `setContent` inside `runComposeUiTest`. `key` makes each
     * fixture a fresh subtree, which matters for more than tidiness: the stateful fixture's
     * holders are seeded inside its own subtree, and a recomposition that reuses the previous
     * stateless fixture's would leave `text_field_state` reading a store that holds nothing.
     * Resolution runs once before the composition, and holding the runtime in one instance for
     * the loop is the same assembly `ConformanceHarness` documents: construction is what runs
     * the coverage check, not each draw.
     */
    @Test
    fun `the runtime renders every fixture under the Android runner`() {
        val runtime = ConformanceHarness.runtime(ConformanceHarness.schema())
        val environment = ConformanceHarness.environment()
        val resolved: Map<String, ResolvedDocument> =
            FixtureDocuments.ids.associateWith { id -> resolve(id) }

        val current = mutableStateOf(resolved.keys.first())
        rule.setContent {
            key(current.value) {
                UiScreen(runtime, resolved.getValue(current.value), HOME, environment)
            }
        }
        for (id in FixtureDocuments.ids) {
            current.value = id
            rule.waitForIdle()
            assertTrue(texts().isNotEmpty(), "fixture '$id'")
        }
    }

    /**
     * §30's example, driven rather than only drawn. The press travels through the seam the
     * runtime's `ButtonRenderer` hands out — the node's own `onClick`, run against the screen's
     * scope — so a click event produced by the real Android input stack must land in the
     * runtime's `BackStackNavigator` and push it onto Profile. The desktop click row in
     * `ConformanceTest` makes the same journey; this one makes it through an actual framework
     * dispatch.
     */
    @Test
    fun `a click on Continue moves the runtime stack onto Profile under the Android runner`() {
        val runtimeStack = BackStackNavigator(HOME)
        rule.setContent {
            UiApp(
                ConformanceHarness.runtime(ConformanceHarness.schema()),
                navigating(),
                RuntimeEnvironment(navigator = runtimeStack),
            )
        }
        assertTrue(continueButton().exposesClick(), "the runtime button exposes no click action")
        continueButton().performClick()
        rule.waitForIdle()

        assertEquals(PROFILE, runtimeStack.current.page, "the runtime press did not navigate")
        assertTrue(texts().isNotEmpty(), "the Profile page rendered nothing")
        assertTrue(runtimeStack.back(), "the runtime press did not push onto the stack")
        assertEquals(HOME, runtimeStack.current.page, "one pop did not return to the start")
    }

    /** The navigating fixture resolved. Its screen declares a navigator, so no registry holds it. */
    private fun navigating(): ResolvedDocument {
        val schema = ConformanceHarness.schema()
        val result = ConformanceHarness.analyze(schema, NAVIGATING, NavigatingFixtureDocuments::json)
        check(result.diagnostics.none { it.severity == Severity.Error }) {
            "fixture '$NAVIGATING': ${result.diagnostics}"
        }
        return checkNotNull(result.resolved) { "fixture '$NAVIGATING' resolved nothing" }
    }

    private fun resolve(id: String): ResolvedDocument {
        val schema = ConformanceHarness.schema()
        val result = ConformanceHarness.analyze(schema, id)
        check(result.diagnostics.none { it.severity == Severity.Error }) {
            "fixture '$id': ${result.diagnostics}"
        }
        return checkNotNull(result.resolved) { "fixture '$id' resolved nothing" }
    }

    /** The button by its label, which is the whole of §28.4's `click("Continue")` script. */
    private fun continueButton(): SemanticsNodeInteraction = rule.onNode(hasText(CONTINUE))

    /** §28.4's "click actions" row: the press is exposed, whatever runs behind it. */
    private fun SemanticsNodeInteraction.exposesClick(): Boolean =
        fetchSemanticsNode().config.valueOrNull(SemanticsActions.OnClick) != null

    /** Every text in the composed tree, in tree order. */
    private fun texts(): List<String> {
        val found = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.valueOrNull(SemanticsProperties.Text)?.forEach { found += it.text }
            for (child in node.children) walk(child)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return found
    }

    // The same optional read `ConformanceTest` spells: `SemanticsConfiguration` has no visible
    // null-returning member in this Compose version, so `get` plus `runCatching` is the one.
    private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
        runCatching { get(key) }.getOrNull()
}
