package dev.rotalex.lutter.desktopapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.ui.EditorShell
import dev.rotalex.lutter.editor.ui.theme.LutterTheme
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the window shows and what its panes do, asserted on a headless runner.
 *
 * The window itself cannot be asserted, so this drives [EditorShell] over the same document
 * `main` opens: a host that compiles and renders nothing would pass a build and prove nothing.
 * The runner's surface is wider than [dev.rotalex.lutter.editor.ui.EditorShell]'s compact
 * breakpoint, so what is under test here is the three-pane arrangement — the phone's one-pane
 * one is `AndroidEditorScreenTest`'s, in the module that can boot an Android window.
 */
@OptIn(ExperimentalTestApi::class)
class EditorScreenTest {

    private val schema = BuiltinEngine.schema()

    @Test
    fun `the three panes are on screen and the canvas renders the document`() = runComposeUiTest {
        val session = session()
        val preview = preview(session)
        setContent {
            LutterTheme { EditorShell(session, preview, BuiltinEngine.runtime(schema), save = null) }
        }

        onNodeWithTag("palette").fetchSemanticsNode()
        onNodeWithTag("canvas").fetchSemanticsNode()
        onNodeWithTag("inspector").fetchSemanticsNode()

        // The canvas is the document: the starter's own heading, which no pane label shares, so
        // finding it proves the JSON was lowered and drawn rather than that a tag exists.
        onNode(hasText("Lutter")).fetchSemanticsNode()
    }

    @Test
    fun `a palette click inserts through the session`() = runComposeUiTest {
        val session = session()
        val preview = preview(session)
        val before = session.document.nodes.size
        setContent {
            LutterTheme { EditorShell(session, preview, BuiltinEngine.runtime(schema), save = null) }
        }

        onNodeWithTag("palette:m3.Text").performClick()
        waitForIdle()

        assertEquals(before + 1, session.document.nodes.size)
    }

    @Test
    fun `the app bar's undo takes the insert back`() = runComposeUiTest {
        val session = session()
        val preview = preview(session)
        val before = session.document.nodes.size
        setContent {
            LutterTheme { EditorShell(session, preview, BuiltinEngine.runtime(schema), save = null) }
        }

        onNodeWithTag("palette:m3.Text").performClick()
        waitForIdle()
        assertEquals(before + 1, session.document.nodes.size)

        // The button, not the session: a frame that drew an undo that did nothing would pass a
        // session-level test and fail the person.
        onNodeWithContentDescription("Undo").performClick()
        waitForIdle()

        assertEquals(before, session.document.nodes.size)
    }

    @Test
    fun `the inspector applies an edited expression to the selected node`() = runComposeUiTest {
        val session = session()
        val preview = preview(session)
        val document: UiDocument = session.document
        val node: NodeId = document.nodes.ids().first {
            document.nodes.require(it).type.value == "m3.Text"
        }
        val text = PropertyKey("text")
        val original = document.nodes.require(node).props[text]
        session.select(EditorSelection.Node(node))
        setContent {
            LutterTheme { EditorShell(session, preview, BuiltinEngine.runtime(schema), save = null) }
        }

        onNodeWithTag("inspector:text").performTextClearance()
        onNodeWithTag("inspector:text").performTextInput("\"Edited\"")
        // The field commits on the keyboard's Done, which is one of the two ways out of it; the
        // other is losing focus, and neither is a per-row button any more.
        onNodeWithTag("inspector:text").performImeAction()
        waitForIdle()

        val edited = session.document.nodes.require(node).props[text]
        assertNotEquals(original, edited)
        assertEquals(PropertyValue.Const(Value.Str("Edited")), edited)
        // The canvas is still the same document, lowered: the edit resolved.
        assertTrue(preview.state.value is PreviewState.Rendered)
    }

    private fun session(): EditorSession =
        EditorSession.open(readDocument(DEFAULT_DOCUMENT), schema)

    private fun preview(session: EditorSession): EditorPreview =
        EditorPreview(session, analyze = { BuiltinEngine.analyze(schema, it) })
}
