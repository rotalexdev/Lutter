package dev.rotalex.lutter.androidapp

import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
import dev.rotalex.lutter.editor.ui.EditorShell
import dev.rotalex.lutter.editor.ui.theme.LutterTheme
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The narrow arrangement, on a host JVM.
 *
 * Robolectric boots an Android runtime for the unit runner, so this runs on the same `check`
 * task as every other module's tests — no device, no emulator, no second CI job. The frame is
 * the same `EditorShell` `:desktopApp` asserts; what is new here is which arrangement it picks
 * at a phone's width, and the sheets a phone needs because it has no room for a second pane.
 *
 * The runtime is pinned in `src/test/resources/robolectric.properties` (35, as NowInAndroid does):
 * this module targets 37, Robolectric ships up to 36, and 36's `ApplicationSharedMemory` reaches
 * into `FileDescriptor` internals that a JDK 21 runtime refuses without `--add-opens`.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val schema = BuiltinEngine.schema()

    @Test
    fun the_canvas_fills_the_window_and_the_panes_arrive_as_sheets() {
        val session = session()
        compose.setContent {
            LutterTheme { EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null) }
        }

        // One pane at a time: the canvas gets the window, and the palette and the inspector are
        // behind the button and the selection's actions rather than stacked above and below it.
        compose.onNodeWithTag("canvas").assertExists()
        compose.onNodeWithTag("palette-open").assertExists()
        compose.onNodeWithTag("palette").assertDoesNotExist()
        compose.onNodeWithTag("inspector").assertDoesNotExist()
        // A line only the document has: a host that composes an empty box fails here.
        compose.onNode(hasText("Continue")).assertExists()
    }

    @Test
    fun the_palette_sheet_inserts_through_the_session() {
        val session = session()
        val before = session.document.nodes.size
        compose.setContent {
            LutterTheme { EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null) }
        }

        compose.onNodeWithTag("palette-open").performClick()
        compose.waitForIdle()

        // The sheet is a `LazyColumn` limited to 520dp, so a row past the fold is not composed
        // until it is scrolled to — the same gesture a user makes, and the reason the tag is
        // asserted after the scroll rather than assumed.
        compose.onNodeWithTag("palette").performScrollToNode(hasTestTag("palette:m3.Text"))
        compose.onNodeWithTag("palette:m3.Text").performClick()
        compose.waitForIdle()

        assertEquals(before + 1, session.document.nodes.size)
    }

    @Test
    fun the_inspector_sheet_applies_an_edited_expression() {
        val session = session()
        val editorPreview = preview(session)
        val node: NodeId = session.document.nodes.ids().first {
            session.document.nodes.require(it).type.value == "m3.Text"
        }
        val text = PropertyKey("text")
        val original = session.document.nodes.require(node).props[text]
        session.select(EditorSelection.Node(node))
        compose.setContent {
            LutterTheme { EditorShell(session, editorPreview, BuiltinEngine.runtime(schema), save = null) }
        }

        // The selection dock is the only way to the inspector at this width, so the click on it
        // is part of what this test asserts: a dock that drew nothing would fail the next line.
        compose.onNodeWithTag("selection-actions").assertExists()
        compose.onNodeWithText("Properties").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("inspector:text").performTextClearance()
        compose.onNodeWithTag("inspector:text").performTextInput("\"Edited\"")
        // The field commits on the keyboard's Done, which is one of the two ways out of it; the
        // other is losing focus, and neither is a per-row button any more.
        compose.onNodeWithTag("inspector:text").performImeAction()
        compose.waitForIdle()

        val edited = session.document.nodes.require(node).props[text]
        assertNotEquals(original, edited)
        assertEquals(PropertyValue.Const(Value.Str("Edited")), edited)
        // The preview the composition follows, not a second one: a fresh `EditorPreview` would be
        // `Empty` and the assertion would prove nothing.
        assertTrue(editorPreview.state.value is PreviewState.Rendered)
    }

    @Test
    fun the_selection_dock_deletes_the_selected_node() {
        val session = session()
        val node = NodeId("n_title")
        session.select(EditorSelection.Node(node))
        compose.setContent {
            LutterTheme { EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null) }
        }

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        assertNull(session.document.nodes[node])
        // Removal reconciles the selection, so the dock goes with the node it was acting on.
        compose.onNodeWithTag("selection-actions").assertDoesNotExist()
    }

    /**
     * The repository's own fixture, inline.
     *
     * Not read from assets: the point of this test is the arrangement, and a document that
     * arrives through the asset pipeline would make a packaging change look like a layout bug.
     * The document is the same one `fixtures-navigating/button_navigate.json` holds.
     */
    @Test
    fun a_click_on_the_preview_selects_the_node_under_the_finger() {
        val session = session()
        // Start from nothing selected, so the assertion is about the click rather than about what
        // `open` restored.
        session.select(EditorSelection.None)
        compose.setContent {
            LutterTheme { EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null) }
        }

        // The overlay sits above the preview and owns the pointer, so this click selects the text
        // instead of doing nothing to it. Clicking the node's own centre is the honest gesture: a
        // fixed offset would be a statement about the fixture's padding, not about the hit test.
        compose.onNode(hasText("Welcome")).performTouchInput { click(center) }

        compose.waitForIdle()
        assertEquals(EditorSelection.Node(NodeId("n_title")), session.selection.value)
        compose.onNodeWithTag("canvas-selection").assertExists()
        compose.onNodeWithTag("selection-actions").assertExists()
    }

    private fun session(): EditorSession {
        val document = JsonDocumentCodec.decode(DOCUMENT.encodeToByteArray()).document
        return EditorSession.open(document, schema)
    }

    private fun preview(session: EditorSession): EditorPreview =
        EditorPreview(session, analyze = { BuiltinEngine.analyze(schema, it) })

    private companion object {
        const val DOCUMENT: String = """
        {
          "format": "forge.ui-document",
          "formatVersion": 1,
          "schemaVersion": 1,
          "payload": {
            "app": {"packageName": "com.example.conformance", "startPage": "p_home"},
            "components": {},
            "meta": {"name": "ButtonNavigate"},
            "nodes": {
              "n_btn": {
                "id": "n_btn",
                "slots": {"content": ["n_btn_label"]},
                "type": "m3.Button"
              },
              "n_btn_label": {
                "id": "n_btn_label",
                "props": {"text": {"type": "const", "value": {"type": "str", "v": "Continue"}}},
                "type": "m3.Text"
              },
              "n_profile_text": {
                "id": "n_profile_text",
                "props": {"text": {"type": "const", "value": {"type": "str", "v": "Profile"}}},
                "type": "m3.Text"
              },
              "n_root": {
                "id": "n_root",
                "props": {"spacing": {"type": "const", "value": {"type": "dp", "v": 8}}},
                "slots": {"children": ["n_title", "n_btn"]},
                "type": "core.Column"
              },
              "n_title": {
                "id": "n_title",
                "props": {"text": {"type": "const", "value": {"type": "str", "v": "Welcome"}}},
                "type": "m3.Text"
              }
            },
            "pages": {
              "p_home": {"id": "p_home", "name": "Home", "root": "n_root", "route": "home"},
              "p_profile": {"id": "p_profile", "name": "Profile", "root": "n_profile_text", "route": "profile"}
            }
          }
        }
        """
    }
}
