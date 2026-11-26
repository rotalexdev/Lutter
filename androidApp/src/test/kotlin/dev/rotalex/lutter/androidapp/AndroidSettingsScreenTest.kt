package dev.rotalex.lutter.androidapp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorPreview
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorWorkspace
import dev.rotalex.lutter.editor.Opened
import dev.rotalex.lutter.editor.WorkspaceSaver
import dev.rotalex.lutter.editor.open
import dev.rotalex.lutter.editor.settings.EditorSettings
import dev.rotalex.lutter.editor.settings.InMemorySettingsStore
import dev.rotalex.lutter.editor.settings.ThemeChoice
import dev.rotalex.lutter.editor.ui.EditorShell
import dev.rotalex.lutter.editor.ui.EditorShellSave
import dev.rotalex.lutter.editor.ui.theme.LutterTheme
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * The settings screen, inside the frame it is opened from.
 *
 * The store is the real interface over the in-memory implementation, so what this proves is the
 * screen's wiring: the action that opens it, the control that changes a value, and the frame the
 * back action returns to. What it deliberately does not prove is the DataStore file — that is
 * `PreferencesSettingsStoreTest`, where a file can exist.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val schema = BuiltinEngine.schema()

    /**
     * The primary navigation is the only way between the two screens, and it is a destination in
     * the back stack rather than a flag: what this asserts is that the bar reaches the code and
     * that the editor is not left drawn underneath it.
     */
    @Test
    fun the_navigation_bar_reaches_the_code_destination() {
        val session = session()
        compose.setContent {
            LutterTheme {
                EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null)
            }
        }

        compose.onNodeWithTag("nav:code").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("code").assertExists()
        compose.onNodeWithTag("canvas").assertDoesNotExist()

        compose.onNodeWithTag("nav:editor").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("canvas").assertExists()
        compose.onNodeWithTag("code").assertDoesNotExist()
    }

    @Test
    fun the_settings_action_opens_the_screen_and_the_back_action_returns_to_the_canvas() {
        val store = InMemorySettingsStore()
        val session = session()
        compose.setContent {
            LutterTheme {
                EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null, settings = store)
            }
        }

        compose.openSettings()

        // The screen replaced the tabs, and the canvas is gone while it is shown: a settings page
        // drawn over a live editor would let a tap land on a document nobody is choosing.
        compose.onNodeWithTag("settings").assertExists()
        compose.onNodeWithTag("canvas").assertDoesNotExist()
        compose.onNodeWithTag("palette-open").assertDoesNotExist()

        compose.onNodeWithTag("settings-back").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("settings").assertDoesNotExist()
        compose.onNodeWithTag("canvas").assertExists()
    }

    @Test
    fun choosing_a_theme_writes_it_to_the_store() {
        val store = InMemorySettingsStore()
        val session = session()
        compose.setContent {
            LutterTheme {
                EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null, settings = store)
            }
        }

        compose.openSettings()
        compose.onNodeWithTag("settings:theme:Dark").performClick()
        compose.waitForIdle()

        assertEquals(ThemeChoice.Dark, store.settings.value.theme)
        // The screen is still a view of the store: the row that was picked is still there, and
        // the write is what changed its selection.
        compose.onNodeWithTag("settings:theme:Dark").assertExists()
    }

    @Test
    fun the_code_text_size_starts_where_the_store_put_it() {
        val store = InMemorySettingsStore(EditorSettings(codeTextScale = 1.2f))
        val session = session()
        compose.setContent {
            LutterTheme {
                EditorShell(session, preview(session), BuiltinEngine.runtime(schema), save = null, settings = store)
            }
        }

        compose.openSettings()

        // The row prints the value it is showing, so a screen that ignored the store would read
        // "×1.0" over a file that says 1.2.
        compose.onNodeWithText("Text size ×1.2").assertExists()
    }

    /**
     * Autosave off is a manual write, and the frame has to offer it.
     *
     * The assertion is the disk: with the switch off nothing is written on its own, and the Save
     * action the frame grew is what puts the selection in the session record. An `EditorShell`
     * that showed the button and did nothing would pass a screenshot and lose the work.
     */
    @Test
    fun turning_autosave_off_puts_a_save_action_in_the_frame_that_writes() {
        val storage = RecordingStorage()
        val document: UiDocument = JsonDocumentCodec.decode(DOCUMENT.encodeToByteArray()).document
        val opened: Opened = runBlocking {
            EditorWorkspace.open(
                documentLocation = "document.json",
                recordLocation = "session.json",
                seed = document,
                storage = storage,
            )
        }
        val session = EditorSession.open(opened.document, schema)
        val saver = WorkspaceSaver(opened.workspace) { "12:00:00" }
        val store = InMemorySettingsStore(EditorSettings(autosave = false))
        session.select(EditorSelection.Node(NodeId("n_title_leaf")))
        val before: String? = storage.text("session.json")

        compose.setContent {
            LutterTheme {
                EditorShell(
                    session = session,
                    preview = preview(session),
                    runtime = BuiltinEngine.runtime(schema),
                    save = EditorShellSave(
                        saver = saver,
                        location = opened.workspace.documentLocation,
                        notice = opened.notice,
                    ),
                    settings = store,
                )
            }
        }

        // Nothing on its own: the switch is off, so the selection above was not written.
        compose.waitForIdle()
        assertEquals(before, storage.text("session.json"))

        compose.onNodeWithTag("save-now").performClick()
        compose.waitForIdle()

        val written = assertNotNull(storage.text("session.json"))
        assertTrue(
            written.contains("n_title_leaf"),
            "Save reported success and the record still points somewhere else: $written",
        )
    }

    /**
     * Settings, the way a person reaches it: the app bar's "more options" menu.
     *
     * Not a tag on a hidden screen — the point of the test is the route, so it takes the route.
     */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.openSettings() {
        onNodeWithTag("more-options").performClick()
        waitForIdle()
        onNodeWithTag("settings-menu-item").performClick()
        waitForIdle()
    }

    private fun session(): EditorSession {
        val document: UiDocument = JsonDocumentCodec.decode(DOCUMENT.encodeToByteArray()).document
        return EditorSession.open(document, schema)
    }

    private fun preview(session: EditorSession): EditorPreview =
        EditorPreview(session, analyze = { BuiltinEngine.analyze(schema, it) })

    /** The storage seam with a map behind it, so a write can be read back as text. */
    private class RecordingStorage : DocumentStorage {
        private val files: MutableMap<String, ByteArray> = mutableMapOf()

        override suspend fun read(location: String): ByteArray =
            files[location] ?: throw IllegalStateException("no document at '$location'")

        override suspend fun write(location: String, bytes: ByteArray) {
            files[location] = bytes
        }

        fun text(location: String): String? = files[location]?.decodeToString()
    }

    private companion object {
        /** One text on one page: enough for a frame, and nothing for the settings to disturb. */
        const val DOCUMENT: String = """
        {
          "format": "forge.ui-document",
          "formatVersion": 1,
          "schemaVersion": 1,
          "payload": {
            "app": {"packageName": "com.example.conformance", "startPage": "p_home"},
            "components": {},
            "meta": {"name": "Settings"},
            "nodes": {
              "n_title_leaf": {
                "id": "n_title_leaf",
                "props": {"text": {"type": "const", "value": {"type": "str", "v": "Welcome"}}},
                "type": "m3.Text"
              }
            },
            "pages": {
              "p_home": {
                "id": "p_home",
                "name": "Home",
                "root": "n_title_leaf",
                "route": "home"
              }
            }
          }
        }
        """
    }
}
