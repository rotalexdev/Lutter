package dev.rotalex.lutter.androidapp

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.PreviewState
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
import kotlin.test.assertTrue

/**
 * The narrow arrangement, on a host JVM.
 *
 * Robolectric boots an Android runtime for the unit runner, so this runs on the same `check`
 * task as every other module's tests — no device, no emulator, no second CI job. The panes are
 * the same composables `:desktopApp` asserts; what is new here is the column and the activity's
 * share of the wiring, which is exactly the part a desktop test cannot reach.
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
    fun the_three_panes_are_stacked_and_the_canvas_renders_the_document() {
        val session = session()
        compose.setContent {
            MaterialTheme { AndroidEditorScreen(session, preview(session), BuiltinEngine.runtime(schema)) }
        }

        compose.onNodeWithTag("palette").assertExists()
        compose.onNodeWithTag("canvas").assertExists()
        compose.onNodeWithTag("inspector").assertExists()
        // A line only the document has: a host that composes three empty boxes fails here.
        compose.onNode(hasText("Continue")).assertExists()
    }

    @Test
    fun a_palette_click_inserts_through_the_session() {
        val session = session()
        val before = session.document.nodes.size
        compose.setContent {
            MaterialTheme { AndroidEditorScreen(session, preview(session), BuiltinEngine.runtime(schema)) }
        }

        // The palette is a 160dp `LazyColumn`, so a row past the fold is not composed until it
        // is scrolled to — the same gesture a user makes, and the reason the tag is asserted
        // after the scroll rather than assumed.
        compose.onNodeWithTag("palette").performScrollToNode(hasTestTag("palette:m3.Text"))
        compose.onNodeWithTag("palette:m3.Text").performClick()
        compose.waitForIdle()

        assertEquals(before + 1, session.document.nodes.size)
    }

    @Test
    fun the_inspector_applies_an_edited_expression() {
        val session = session()
        val editorPreview = preview(session)
        val node: NodeId = session.document.nodes.ids().first {
            session.document.nodes.require(it).type.value == "m3.Text"
        }
        val text = PropertyKey("text")
        val original = session.document.nodes.require(node).props[text]
        session.select(EditorSelection.Node(node))
        compose.setContent {
            MaterialTheme { AndroidEditorScreen(session, editorPreview, BuiltinEngine.runtime(schema)) }
        }

        compose.onNodeWithTag("inspector:text").performTextClearance()
        compose.onNodeWithTag("inspector:text").performTextInput("\"Edited\"")
        compose.onNodeWithTag("apply:text").performClick()
        compose.waitForIdle()

        val edited = session.document.nodes.require(node).props[text]
        assertNotEquals(original, edited)
        assertEquals(PropertyValue.Const(Value.Str("Edited")), edited)
        // The preview the composition follows, not a second one: a fresh `EditorPreview` would be
        // `Empty` and the assertion would prove nothing.
        assertTrue(editorPreview.state.value is PreviewState.Rendered)
    }

    /**
     * The repository's own fixture, inline.
     *
     * Not read from assets: the point of this test is the arrangement, and a document that
     * arrives through the asset pipeline would make a packaging change look like a layout bug.
     * The document is the same one `fixtures-navigating/button_navigate.json` holds.
     */
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
