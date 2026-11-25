package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The rules of saving, against a storage that keeps bytes in a map.
 *
 * Both hosts inherit these: the desktop window and the Android activity differ only in *where* the
 * two locations point. Testing them here instead of twice is the reason [EditorWorkspace] lives in
 * this module — a host test that re-tested the same rules would be a second answer to one
 * question.
 */
class EditorWorkspaceTest {

    private val storage = MapStorage()
    private val seed: UiDocument = editorDocument()

    private val documentLocation: String = "workspace/document.json"
    private val recordLocation: String = "workspace/session.json"

    private suspend fun open(): Opened = EditorWorkspace.open(
        documentLocation = documentLocation,
        recordLocation = recordLocation,
        seed = seed,
        storage = storage,
    )

    @Test
    fun `a first run writes the seed out and reports nothing`() = runBlocking {
        val opened = open()

        assertNull(opened.notice, "a first run is not a problem to report")
        assertNull(opened.record, "a first run has no selection to restore")
        assertEquals(encoded(seed), encoded(opened.document))
        assertEquals(
            encoded(seed),
            storage.bytesAt(documentLocation)?.decodeToString(),
            "the seed was not written, so the first edit would have nowhere to land",
        )
    }

    @Test
    fun `the document is written before the record`() = runBlocking {
        val order = mutableListOf<String>()
        val recording = object : DocumentStorage {
            override suspend fun read(location: String): ByteArray = storage.read(location)
            override suspend fun write(location: String, bytes: ByteArray) {
                order += location
                storage.write(location, bytes)
            }
        }
        val opened = EditorWorkspace.open(documentLocation, recordLocation, seed, recording)
        order.clear()

        opened.workspace.save(opened.document, EditorSessionRecord(documentLocation))

        assertEquals(
            listOf(documentLocation, recordLocation),
            order,
            "the record was written ahead of the document it describes, so a crash between the " +
                "two would leave a selection pointing into a file that does not have that shape",
        )
    }

    @Test
    fun `a saved document and its selection come back on the next open`() = runBlocking {
        val first = open()
        val session = session(first.document)
        val selection = EditorSelection.Node(LEAF)
        session.select(selection)

        first.workspace.save(
            document = session.document,
            record = EditorSessionRecord(documentLocation, selection.toRecord()),
        )

        val second = open()
        assertEquals(
            encoded(session.document),
            encoded(second.document),
            "the document came back different from the one that was saved",
        )
        val record = assertNotNull(second.record)
        assertEquals(selection.toRecord(), record.selection, "the selection was not restored")
        assertEquals(
            documentLocation,
            record.documentLocation,
            "the record points somewhere other than the document it was saved with",
        )
    }

    @Test
    fun `a document that does not parse is kept, and the seed opens instead`() = runBlocking {
        val corrupt = "{ not a document".encodeToByteArray()
        storage.write(documentLocation, corrupt)

        val opened = open()

        assertEquals(encoded(seed), encoded(opened.document), "the seed did not open")
        assertNotNull(opened.notice, "somebody's bytes were set aside and nobody is told")
        assertTrue(
            storage.bytesAt(documentLocation + ".corrupt")?.contentEquals(corrupt) == true,
            "the unreadable document was thrown away instead of kept",
        )
    }

    @Test
    fun `a missing document is a first run, not a failure`() = runBlocking {
        val opened = EditorWorkspace.open("fresh/document.json", "fresh/session.json", seed, storage)

        assertNull(opened.notice)
        assertEquals(encoded(seed), encoded(opened.document))
    }

    @Test
    fun `a successful save reports when it happened`() = runBlocking {
        val saver = WorkspaceSaver(open().workspace, now = { "10:00:00" })

        saver.save(seed, EditorSelection.None)

        assertEquals(SaveState.Saved(at = "10:00:00"), saver.state)
    }

    @Test
    fun `a write that fails becomes a failed state that names the reason`() = runBlocking {
        var allowed = 1 // the seed's own write, so the workspace can open at all
        val flaky = object : DocumentStorage {
            override suspend fun read(location: String): ByteArray = storage.read(location)
            override suspend fun write(location: String, bytes: ByteArray) {
                if (allowed <= 0) throw IllegalStateException("the disk is full")
                allowed -= 1
                storage.write(location, bytes)
            }
        }
        val workspace =
            EditorWorkspace.open(documentLocation, recordLocation, seed, flaky).workspace
        val saver = WorkspaceSaver(workspace, now = { "10:00:01" })

        saver.save(seed, EditorSelection.None)

        val failed = assertIs<SaveState.Failed>(saver.state)
        assertEquals("the disk is full", failed.reason)
        assertEquals("10:00:01", failed.at)
    }

    @Test
    fun `a selection naming a node the document does not hold is dropped on reopen`() = runBlocking {
        val first = open()
        first.workspace.save(
            document = first.document,
            record = EditorSessionRecord(documentLocation, EditorSelection.Node(ABSENT).toRecord()),
        )

        val second = open()
        val reopened = EditorSession.open(second.document, editorSchema(), assertNotNull(second.record))

        assertEquals(
            EditorSelection.None,
            reopened.selection.value,
            "a restored selection that cannot be drawn must not be an error, but it must not " +
                "survive either",
        )
    }

    private fun encoded(document: UiDocument): String =
        JsonDocumentCodec.encode(document).decodeToString()
}
