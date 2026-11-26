package dev.rotalex.lutter.desktopapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.editor.ui.CrashReportScreen
import dev.rotalex.lutter.editor.ui.theme.LutterTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The launch after a crash: the trace is on screen, readable, and one action dismisses it.
 *
 * A screen that recorded nothing would be worse than no screen at all — it would tell a reporter
 * that the editor crashed and then hide the one thing that says where.
 */
@OptIn(ExperimentalTestApi::class)
class CrashReportScreenTest {

    private val trace: String = buildString {
        appendLine("java.lang.IllegalStateException: the sheet was removed mid-animation")
        appendLine("\tat dev.rotalex.lutter.editor.ui.EditorShellKt.invoke(EditorShell.kt:190)")
        appendLine("\tat androidx.compose.runtime.Recomposer.applyChanges(Recomposer.kt:1)")
    }

    @Test
    fun `the trace is shown and continue reports that it was read`() = runComposeUiTest {
        var continued = false
        setContent {
            LutterTheme {
                CrashReportScreen(trace = trace, onContinue = { continued = true })
            }
        }

        onNodeWithTag("crash-report").fetchSemanticsNode()
        // The exception's own line, not a label: a screen that printed "a crash happened" would
        // pass a tag assertion and give a maintainer nothing.
        onNode(hasText("the sheet was removed mid-animation", substring = true)).fetchSemanticsNode()

        onNodeWithTag("crash-continue").performClick()
        assertTrue(continued)
    }
}
