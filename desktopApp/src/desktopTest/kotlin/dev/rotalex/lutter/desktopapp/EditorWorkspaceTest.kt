package dev.rotalex.lutter.desktopapp

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSession
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.PropertyWrite
import dev.rotalex.lutter.editor.toRecord
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The editor's own copy of a document, and the promise that a close is not a loss.
 *
 * Every test opens a throw-away directory, because the alternative — the developer's real
 * workspace — makes the suite a thing that deletes somebody's work. A temperorary directory also
 * lets the corrupt-file test leave garbage where nothing cares.
 */
class EditorWorkspaceTest {

    private val temporary: File = Files.createTempDirectory("lutter-workspace").toFile()

    @AfterTest
    fun removeTheTemporaryWorkspace() {
        temporary.deleteRecursively()
    }

    private val schema = BuiltinEngine.schema()

    /** The starter, read through the same entry point the window uses. */
    private fun seed(): UiDocument = readDocument(DEFAULT_DOCUMENT)

    private fun workingCopy(): String = File(temporary, "document.json").path

    @Test
    fun `a first run writes the seed out, so the first edit has somewhere to land`() = runBlocking {
        val opened = EditorWorkspace.open(temporary, seed(), workingCopy())

        assertNull(opened.notice, "a first run is not a problem to report")
        assertNull(opened.record, "a first run has no selection to restore")
        assertEquals(encoded(seed()), encoded(opened.document))
        assertTrue(
            File(workingCopy()).isFile,
            "the working copy was not written, so nothing could be saved on top of it",
        )
    }

    @Test
    fun `an edit is on disk after a close and back after a reopen`() = runBlocking {
        val first = EditorWorkspace.open(temporary, seed(), workingCopy())
        val session = EditorSession.open(first.document, schema)
        val node: NodeId = assertNotNull(first.document.nodes.ids().firstOrNull())
        val text = PropertyKey("text")
        val write = session.setProperty(node, text, "\"Edited in a test\"")
        assertTrue(write is PropertyWrite.Applied, "the edit was refused: $write")
        first.workspace.save(
            document = session.document,
            record = EditorSessionRecord(
                documentLocation = workingCopy(),
                selection = EditorSelection.Node(node),
            ),
        )

        // A second process, from the same directory: nothing carried in memory.
        val second = EditorWorkspace.open(temporary, seed(), workingCopy())
        val restored = EditorSession.open(second.document, schema, assertNotNull(second.record))

        assertEquals(
            encoded(session.document),
            encoded(restored.document),
            "the document came back different from the one that was saved",
        )
        assertEquals(
            EditorSelection.Node(node).toRecord(),
            second.record?.selection,
            "the selection was not restored, so the record is not doing its job",
        )
    }

    @Test
    fun `a document that does not parse is kept, and the starter opens instead`() = runBlocking {
        val corrupt = "{ this is not a document".encodeToByteArray()
        File(workingCopy()).writeBytes(corrupt)

        val opened = EditorWorkspace.open(temporary, seed(), workingCopy())

        assertEquals(encoded(seed()), encoded(opened.document), "the starter did not open")
        assertNotNull(
            opened.notice,
            "somebody's bytes were set aside and the window says nothing about it",
        )
        assertTrue(
            File(workingCopy() + ".corrupt").readBytes().contentEquals(corrupt),
            "the unreadable document was thrown away instead of kept",
        )
    }

    @Test
    fun `a save leaves no temporary file behind`() = runBlocking {
        val opened = EditorWorkspace.open(temporary, seed(), workingCopy())
        opened.workspace.save(opened.document, EditorSessionRecord(documentLocation = workingCopy()))

        val names: List<String> = temporary.list()?.sorted().orEmpty()
        assertEquals(
            listOf("document.json", "session.json"),
            names,
            "an interrupted-write copy is visible after a completed write",
        )
    }

    @Test
    fun `a named document is edited where it lives, not in the workspace`() = runBlocking {
        val named = File(temporary, "someone-elses.json")
        named.writeBytes(JsonDocumentCodec.encode(seed()))

        val opened = EditorWorkspace.open(temporary, seed(), named.path)
        opened.workspace.save(
            opened.document,
            EditorSessionRecord(documentLocation = named.path),
        )

        assertTrue(named.isFile, "the named document disappeared")
        assertFalse(
            File(workingCopy()).isFile,
            "a named document must not be shadowed by a working copy in the workspace",
        )
    }

    @Test
    fun `a location with no directory above it is created rather than refused`() = runBlocking {
        val deep = File(temporary, "one/two/three/document.json")
        val opened = EditorWorkspace.open(deep.parentFile, seed(), deep.path)

        opened.workspace.save(opened.document, EditorSessionRecord(documentLocation = deep.path))

        assertTrue(deep.isFile, "missing parents were not created")
        assertEquals(
            emptyList(),
            Files.list(deep.parentFile.toPath()).use { paths ->
                paths.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
            },
            "a temporary file survived the move",
        )
    }

    @Test
    fun `reading a document that is not there is not an exception`() = runBlocking {
        val missing = File(temporary, "absent/document.json")
        // The seed is written where the caller asked, so the answer is the seed rather than a
        // failure: a first run and a deleted document are the same situation to an editor.
        val opened = EditorWorkspace.open(temporary, seed(), missing.path)

        assertEquals(encoded(seed()), encoded(opened.document))
        assertTrue(missing.isFile)
    }

    @Test
    fun `the workspace directory is a per-platform application directory, named after the editor`() {
        // The override is a real answer and the one a developer may be using.
        if (System.getenv("LUTTER_EDITOR_HOME") != null) return
        val directory = workspaceDirectory()

        assertTrue(directory.isAbsolute, "the workspace is '${directory.path}', which is relative")
        assertTrue(
            directory.name == "lutter-editor" || directory.name == "LutterEditor",
            "the workspace is named '${directory.name}'; it must be the editor's own directory " +
                "and not a bare application-data root",
        )
    }

    private fun encoded(document: UiDocument): String =
        JsonDocumentCodec.encode(document).decodeToString()
}
