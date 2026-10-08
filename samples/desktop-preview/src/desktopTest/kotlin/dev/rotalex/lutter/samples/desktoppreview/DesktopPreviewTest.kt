package dev.rotalex.lutter.samples.desktoppreview

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.BackStackNavigator
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a human sees in the window, asserted on a headless runner.
 *
 * The frame itself cannot be, so this asserts the content the window hands Compose — [UiApp]
 * over the document [main] loads, through that same loader. Without it the module would compile
 * and prove nothing, which is what a sample in `check` that renders is for.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPreviewTest {

    @Test
    fun `the window opens on Welcome and Continue reaches Profile`() = runComposeUiTest {
        val schema = BuiltinEngine.schema()
        val decoded = readDocument(DEFAULT_DOCUMENT)
        val document = BuiltinEngine.resolve(schema, decoded)
        val runtime = BuiltinEngine.runtime(schema)
        val navigator = BackStackNavigator(decoded.app.startPage)

        setContent {
            UiApp(runtime, document, RuntimeEnvironment(navigator = navigator))
        }

        // `fetchSemanticsNode` fails when nothing matches, so it is the assertion.
        onNode(hasText("Welcome")).fetchSemanticsNode()
        onNode(hasText("Continue")).performClick()
        // The press dispatches through a coroutine, so the stack moves after the click returns.
        waitForIdle()

        assertEquals(PageId("p_profile"), navigator.current.page)
    }
}
