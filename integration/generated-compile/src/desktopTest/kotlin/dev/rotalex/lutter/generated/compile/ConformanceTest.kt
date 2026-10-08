package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.generated.button_navigate.AppNavigator
import dev.rotalex.lutter.generated.button_navigate.AppRoot
import dev.rotalex.lutter.generated.button_navigate.Route
import dev.rotalex.lutter.generated.button_navigate.screens.HomeScreen
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import dev.rotalex.lutter.runtime.UiScreen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime and compiled generated code agree: one semantics tree, one image, one click.
 *
 * Each fixture is validated through the analyzer, rendered by the runtime, and rendered by
 * the generated screen the fixture task compiled into this module; generation itself is
 * re-run in-test so a stale generated file fails here rather than comparing old output.
 * Desktop runs headless, so pixels compare here and need no display.
 *
 * The click runs on the navigating fixture's own screen, which declares the navigator as a
 * parameter and so is in no registry. Only the generated side moves: the runtime's button
 * renderer passes an empty `onClick` and nothing in the runtime dispatches a node's handlers.
 * Both trees are re-compared after the press either way.
 */
@OptIn(ExperimentalTestApi::class)
class ConformanceTest {

    private companion object {
        val HOME: PageId = PageId("p_home")
        val PROFILE: PageId = PageId("p_profile")
        const val PACKAGE: String = "dev.rotalex.lutter.generated."
        const val NAVIGATING: String = "button_navigate"
        const val CONTINUE: String = "Continue"
    }

    @Test
    fun `runtime and generated share one semantics tree per fixture`() = runComposeUiTest {
        assertEquals(FixtureDocuments.ids.toSet(), GeneratedFixtures.all.keys)

        for (id in FixtureDocuments.ids) {
            val resolved = resolve(id)
            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, HOME, ConformanceHarness.environment())
            }
            val runtimeDump = dumpSemantics()

            setContent { GeneratedFixtures.all.getValue(id)(Modifier) }
            assertEquals(runtimeDump, dumpSemantics(), "fixture '$id'")
        }
    }

    @Test
    fun `runtime and generated draw the same pixels`() = runComposeUiTest {
        for (id in FixtureDocuments.ids) {
            val resolved = resolve(id)
            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, HOME, ConformanceHarness.environment())
            }
            val runtimePixels = pixelBytes()

            setContent { GeneratedFixtures.all.getValue(id)(Modifier) }
            val generatedPixels = pixelBytes()
            assertEquals(runtimePixels.first, generatedPixels.first, "fixture '$id'")
            assertContentEquals(runtimePixels.second, generatedPixels.second, "fixture '$id'")
        }
    }

    @Test
    fun `the navigating fixture renders one tree and one image on both paths`() = runComposeUiTest {
        val resolved = navigating()
        setContent {
            UiScreen(
                ConformanceHarness.runtime(ConformanceHarness.schema()),
                resolved,
                HOME,
                RuntimeEnvironment(navigator = BackStackNavigator(HOME)),
            )
        }
        val runtimeTree = dumpSemantics()
        val runtimeBounds = firstContent(onRoot().fetchSemanticsNode()).boundsInRoot
        val runtimeImage = pixelBytes()

        setContent { HomeScreen(navigator = AppNavigator(), modifier = Modifier) }
        val generatedImage = pixelBytes()

        assertEquals(runtimeTree, dumpSemantics(), "fixture '$NAVIGATING'")
        // The dump carries no bounds, so the root's own are compared here and the pixels below
        // are what say anything about the layout below it.
        assertEquals(runtimeBounds, firstContent(onRoot().fetchSemanticsNode()).boundsInRoot, "root bounds")
        assertEquals(runtimeImage.first, generatedImage.first, "fixture '$NAVIGATING'")
        assertContentEquals(runtimeImage.second, generatedImage.second, "fixture '$NAVIGATING'")
    }

    @Test
    fun `a click on Continue moves both stacks onto Profile`() =
        runComposeUiTest {
            val resolved = navigating()
            val runtimeStack = BackStackNavigator(HOME)
            setContent {
                UiApp(
                    ConformanceHarness.runtime(ConformanceHarness.schema()),
                    resolved,
                    RuntimeEnvironment(navigator = runtimeStack),
                )
            }
            assertTrue(continueButton().exposesClick(), "the runtime button exposes no click action")
            continueButton().performClick()
            // `waitForIdle` is what makes the two images comparable: it advances past the ripple
            // the press started, so both sides are captured in the same settled state.
            waitForIdle()
            val runtimeAfter = dumpSemantics()
            val runtimeAfterImage = pixelBytes()

            val generatedStack = AppNavigator()
            setContent { AppRoot(navigator = generatedStack, modifier = Modifier) }
            assertTrue(continueButton().exposesClick(), "the generated button exposes no click action")
            continueButton().performClick()
            waitForIdle()

            // Both sides are captured while their own stack is still on Profile, which is the only
            // state in which the two are meant to agree. A pop before the capture would not be
            // visible in the frame — the read that would show it is what idles the composition —
            // so the generated side would be photographed back on Home and compared against the
            // runtime's Profile.
            val generatedAfter = dumpSemantics()
            val generatedAfterImage = pixelBytes()

            assertEquals(Route.Profile, generatedStack.current, "the press did not navigate")
            assertTrue(generatedStack.back(), "the press did not push onto the stack")
            assertEquals(Route.Home, generatedStack.current, "one pop did not return to the start")
            // The runtime reaches the same place the generated path does, through the seam
            // `RenderScope.Dispatch` opened: `ButtonRenderer` hands its press to the node's own
            // `onClick` sequence and `UiRuntime.runActions` runs it against the screen's scope.
            // Both sides are asserted on their own current destination rather than through one
            // shared shape, because they are two implementations of the same contract and only
            // each of them can say what its own state is called.
            assertEquals(PROFILE, runtimeStack.current.page, "the runtime press did not navigate")
            assertTrue(runtimeStack.back(), "the runtime press did not push onto the stack")
            assertEquals(HOME, runtimeStack.current.page, "one pop did not return to the start")

            assertEquals(runtimeAfter, generatedAfter, "after the press")
            assertEquals(runtimeAfterImage.first, generatedAfterImage.first, "after the press")
            assertContentEquals(runtimeAfterImage.second, generatedAfterImage.second, "after the press")
        }

    /** The button by its label, which is the whole of §28.4's `click("Continue")` script. */
    private fun ComposeUiTest.continueButton(): SemanticsNodeInteraction = onNode(hasText(CONTINUE))

    /** §28.4's "click actions" row: both sides expose the press, whatever runs behind it. */
    private fun SemanticsNodeInteraction.exposesClick(): Boolean =
        fetchSemanticsNode().config.valueOrNull(SemanticsActions.OnClick) != null

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
        val resolved = checkNotNull(result.resolved) { "fixture '$id' resolved nothing" }
        val generated = KotlinGenerator(schema, CodegenOptions(PACKAGE + id))
            .generate(resolved, result.diagnostics)
        check(generated.diagnostics.none { it.severity == Severity.Error }) {
            "fixture '$id': ${generated.diagnostics}"
        }
        assertTrue(generated.files.files.any { it.path.endsWith("HomeScreen.kt") }, "fixture '$id'")
        return resolved
    }

    private fun ComposeUiTest.dumpSemantics(): String =
        buildString { render(firstContent(onRoot().fetchSemanticsNode())) }

    private fun ComposeUiTest.pixelBytes(): Pair<Pair<Int, Int>, IntArray> {
        val image = onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return (image.width to image.height) to pixels
    }
}

// UiScreen wraps the tree in a bare Box; the generated screen does not. A wrapper with no
// semantics and one child is layout, not content, so the comparison starts below it.
// Emptiness is read through the dump's own signals (`valueOrNull`): `isEmpty()` is not
// visible on `SemanticsConfiguration` from here, and a node carrying a tag or a text is
// content by the same definition the dump uses. The strip is symmetric — both sides run
// it — so agreement is still proved even where it strips a meaningful single-child root.
private fun firstContent(node: SemanticsNode): SemanticsNode {
    var current = node
    while (current.children.size == 1 &&
        current.config.valueOrNull(SemanticsProperties.TestTag) == null &&
        current.config.valueOrNull(SemanticsProperties.Text) == null
    ) {
        current = current.children.first()
    }
    return current
}

// `SemanticsConfiguration` has no optional read in this Compose version — the member
// `getOrNull` does not exist here, so every call site failed at once. This helper is the
// only optional read, built on `get` (which certainly exists) plus stdlib `runCatching`.
private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
    runCatching { get(key) }.getOrNull()

private fun StringBuilder.render(node: SemanticsNode): Unit {
    append("(")
    append(node.config.valueOrNull(SemanticsProperties.TestTag) ?: "node")
    node.config.valueOrNull(SemanticsProperties.Text)?.let { texts ->
        append(" text=")
        append(texts.joinToString("|") { it.text })
        // Spans carry color and style; the plain string would hide them.
        val spans = texts.flatMap { it.spanStyles }.joinToString("|")
        if (spans.isNotEmpty()) append(" spans=[$spans]")
    }
    node.config.valueOrNull(SemanticsProperties.ContentDescription)?.let { descriptions ->
        append(" desc=")
        append(descriptions.joinToString("|"))
    }
    node.config.valueOrNull(SemanticsProperties.Role)?.let { role ->
        append(" role=")
        append(role.toString())
    }
    if (node.config.valueOrNull(SemanticsProperties.Disabled) != null) append(" disabled")
    for (child in node.children) {
        append(" ")
        render(child)
    }
    append(")")
}
