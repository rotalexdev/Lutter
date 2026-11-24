package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EditorSessionStoreTest {

    private val record = EditorSessionRecord(
        documentLocation = "sessions/editor.json",
        selection = EditorSessionRecord.SelectionRecord.Node(LEAF.value),
        panels = EditorSessionRecord.PanelRecord(palette = false, inspector = true),
    )

    @Test
    fun `a record round-trips through the storage seam`() = runBlocking {
        val storage = MapStorage()
        val store = EditorSessionStore(storage)

        store.save("session.json", record)
        val loaded = store.load("session.json")

        assertEquals(record, loaded)
    }

    @Test
    fun `a location nothing is stored at has no record`() = runBlocking {
        assertNull(EditorSessionStore(MapStorage()).load("absent.json"))
    }

    @Test
    fun `bytes that do not parse have no record`() = runBlocking {
        val storage = MapStorage()
        storage.write("session.json", "not json".encodeToByteArray())

        assertNull(EditorSessionStore(storage).load("session.json"))
    }

    @Test
    fun `a record from a later editor still opens`() = runBlocking {
        val storage = MapStorage()
        storage.write(
            "session.json",
            """
            {
              "documentLocation": "document.json",
              "selection": { "type": "node", "id": "n_leaf" },
              "panels": { "palette": true, "inspector": false },
              "zoom": 1.5
            }
            """.trimIndent().encodeToByteArray(),
        )

        val loaded = assertNotNull(EditorSessionStore(storage).load("session.json"))

        assertEquals("document.json", loaded.documentLocation)
        assertEquals(EditorSessionRecord.SelectionRecord.Node(LEAF.value), loaded.selection)
        assertFalse(loaded.panels.inspector)
    }

    @Test
    fun `a page selection round-trips`() = runBlocking {
        val storage = MapStorage()
        val store = EditorSessionStore(storage)
        val pageRecord = record.copy(selection = EditorSessionRecord.SelectionRecord.Page(HOME.value))

        store.save("session.json", pageRecord)

        assertEquals(pageRecord, store.load("session.json"))
    }

    @Test
    fun `reopening restores the selection and starts with no history`() = runBlocking {
        val reopened = EditorSession.open(editorDocument(), editorSchema(), record)

        assertEquals(EditorSelection.Node(LEAF), reopened.selection.value)
        assertFalse(reopened.controller.state.value.canUndo)
        assertFalse(reopened.controller.state.value.canRedo)
    }

    @Test
    fun `a selection the document does not back reopens with nothing selected`() = runBlocking {
        val missing = record.copy(
            selection = EditorSessionRecord.SelectionRecord.Node(NodeId("n_absent").value),
        )

        val reopened = EditorSession.open(editorDocument(), editorSchema(), missing)

        assertEquals(EditorSelection.None, reopened.selection.value)
    }

    @Test
    fun `a page the document does not hold reopens with nothing selected`() = runBlocking {
        val missing = record.copy(
            selection = EditorSessionRecord.SelectionRecord.Page(PageId("p_absent").value),
        )

        val reopened = EditorSession.open(editorDocument(), editorSchema(), missing)

        assertEquals(EditorSelection.None, reopened.selection.value)
    }

    @Test
    fun `the record mirrors every selection arm`() {
        assertEquals(EditorSessionRecord.SelectionRecord.None, EditorSelection.None.toRecord())
        assertEquals(
            EditorSelection.Node(LEAF),
            EditorSessionRecord.SelectionRecord.Node(LEAF.value).toSelection(),
        )
        assertEquals(
            EditorSelection.Page(HOME),
            EditorSessionRecord.SelectionRecord.Page(HOME.value).toSelection(),
        )
    }

    @Test
    fun `the saved record is the document the host can read`() = runBlocking {
        val storage = MapStorage()
        EditorSessionStore(storage).save("session.json", record)

        val text = assertNotNull(storage.bytesAt("session.json")).decodeToString()

        assertTrue(text.contains("\"documentLocation\": \"sessions/editor.json\""))
        assertTrue(text.contains("\"palette\": false"))
    }
}
