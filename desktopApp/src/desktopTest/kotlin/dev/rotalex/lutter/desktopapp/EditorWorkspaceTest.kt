package dev.rotalex.lutter.desktopapp

import dev.rotalex.lutter.editor.EditorSelection
import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.EditorWorkspace
import dev.rotalex.lutter.editor.Opened
import dev.rotalex.lutter.editor.toRecord
import dev.rotalex.lutter.model.doc.UiDocument
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
 * The desktop half of saving: real files, a real workspace directory.
 *
 * The saving *rules* — a first run seeds the workspace, a corrupt document is kept rather than
 * deleted, a saved selection comes back — are [dev.rotalex.lutter.editor.EditorWorkspace]'s and are
 * tested there against an in-memory storage, because that is where the rules live and both hosts
 * inherit them. What is tested here is what only a filesystem can get wrong: creating missing
 * parents, not leaving a temporary file behind, and editing a named document where it lives
 * instead of shadowing it with a working copy.
 *
 * Every test uses a throw-away directory: the alternative is a suite that deletes somebody's work.
 */
class EditorWorkspaceTest {

    private val temporary: File = Files.createTempDirectory("lutter-workspace").toFile()

    @AfterTest
    fun removeTheTemporaryWorkspace() {
        temporary.deleteRecursively()
    }

    private fun documentLocation(): String = File(temporary, "document.json").path

    private fun recordLocation(): String = File(temporary, "session.json").path

    private fun seed(): UiDocument = readDocument(DEFAULT_DOCUMENT)

    private suspend fun open(
        document: String = documentLocation(),
        record: String = recordLocation(),
    ): Opened = EditorWorkspace.open(
        documentLocation = document,
        recordLocation = record,
        seed = seed(),
        storage = AtomicDocumentStorage(),
    )

    @Test
    fun `a first run writes the seed out, so the first edit has somewhere to land`() = runBlocking {
        val opened = open()

        assertNull(opened.notice, "a first run is not a problem to report")
        assertNull(opened.record, "a first run has no selection to restore")
        assertEquals(encoded(seed()), encoded(opened.document))
        assertTrue(
            File(documentLocation()).isFile,
            "the working copy was not written, so nothing could be saved on top of it",
        )
    }

    @Test
    fun `a location with no directory above it is created rather than refused`() = runBlocking {
        val deep = File(temporary, "one/two/three/document.json")

        open(document = deep.path, record = File(temporary, "one/two/three/session.json").path)

        assertTrue(deep.isFile, "missing parents were not created")
    }

    @Test
    fun `a save leaves no temporary file behind`() = runBlocking {
        val opened = open()
        val root = assertNotNull(opened.document.pages.values.firstOrNull()).root

        opened.workspace.save(
            document = opened.document,
            record = EditorSessionRecord(
                documentLocation = documentLocation(),
                selection = EditorSelection.Node(root).toRecord(),
            ),
        )

        assertEquals(
            listOf("document.json", "session.json"),
            temporary.list()?.sorted().orEmpty(),
            "an interrupted-write copy is visible after a completed write",
        )
    }

    @Test
    fun `a saved selection is on disk under the same workspace and comes back`() = runBlocking {
        val first = open()
        val root = assertNotNull(first.document.pages.values.firstOrNull()).root
        first.workspace.save(
            document = first.document,
            record = EditorSessionRecord(
                documentLocation = documentLocation(),
                selection = EditorSelection.Node(root).toRecord(),
            ),
        )

        val second = open()

        assertEquals(
            EditorSelection.Node(root).toRecord(),
            assertNotNull(second.record).selection,
            "the selection was not read back from the file the first open wrote",
        )
    }

    @Test
    fun `a document that does not parse is kept beside the one that replaced it`() = runBlocking {
        val corrupt = "{ this is not a document".encodeToByteArray()
        File(documentLocation()).writeBytes(corrupt)

        val opened = open()

        assertEquals(encoded(seed()), encoded(opened.document), "the starter did not open")
        assertNotNull(opened.notice, "somebody's bytes were set aside and the editor says nothing")
        assertTrue(
            File(documentLocation() + ".corrupt").readBytes().contentEquals(corrupt),
            "the unreadable document was thrown away instead of kept",
        )
    }

    @Test
    fun `a named document is edited where it lives, not in the workspace`() = runBlocking {
        val named = File(temporary, "someone-elses.json")
        named.writeBytes(JsonDocumentCodec.encode(seed()))

        open(document = named.path)

        assertTrue(named.isFile, "the named document disappeared")
        assertFalse(
            File(documentLocation()).isFile,
            "a named document must not be shadowed by a working copy in the workspace",
        )
    }

    @Test
    fun `the workspace directory is absolute and named after the editor`() {
        // The override is a real answer, and it is the one a developer may be using.
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
