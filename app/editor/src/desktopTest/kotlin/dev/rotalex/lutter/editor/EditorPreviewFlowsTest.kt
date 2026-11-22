package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import dev.rotalex.lutter.serialization.storage.FileDocumentStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The two suspended paths of [EditorPreview].
 *
 * Desktop-only by convention rather than by capability: `runBlocking` is what the rest of this
 * build uses to wait on a suspend function in a test, and the file case additionally needs the
 * JVM's filesystem. The platform-neutral behaviour is in `EditorPreviewTest`.
 */
class EditorPreviewFlowsTest {

    private val schema = editorSchema()
    private val resolved: ResolvedDocument =
        assertNotNull(Analyzer(schema).analyze(editorDocument()).resolved) {
            "the preview fixtures must resolve, or no render case is reachable"
        }

    @Test
    fun `follow re-resolves after a dispatch`() = runBlocking {
        val session = session()
        val preview = EditorPreview(
            session = session,
            analyze = { AnalysisResult(emptyList(), resolved) },
        )
        val watcher = launch { preview.follow() }
        try {
            // The first read is immediate, so the preview starts from the same document the
            // session holds rather than from nothing.
            delay(100)
            assertEquals(0L, assertIs<PreviewState.Rendered>(preview.state.value).revision)

            session.dispatch(Command.RemoveNode(LEAF))
            delay(200)

            assertEquals(1L, assertIs<PreviewState.Rendered>(preview.state.value).revision)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch renders external bytes and keeps the last good render on a refusal`() = runBlocking {
        val storage = MemoryStorage()
        val location = "document.json"
        val good = JsonDocumentCodec.encode(editorDocument())
        storage.write(location, good)
        val preview = EditorPreview(
            session = session(),
            analyze = { AnalysisResult(emptyList(), resolved) },
        )
        val watcher = launch { preview.watch(storage, location, interval = 20.milliseconds) }
        try {
            delay(100)
            val first = assertIs<PreviewState.Rendered>(preview.state.value)
            assertEquals(PreviewOrigin.External, first.origin)
            assertNull(first.revision)
            assertContentEquals(good, JsonDocumentCodec.encode(first.document))

            storage.write(location, "not a document".encodeToByteArray())
            delay(100)
            val refused = assertIs<PreviewState.Refused>(preview.state.value)
            assertEquals(PreviewOrigin.External, refused.origin)
            assertEquals(first, refused.lastRendered)
            assertNotNull(refused.reason)

            // The bytes on screen come back, so the refusal goes with them instead of staying
            // stranded above a document that reads fine.
            storage.write(location, good)
            delay(100)
            assertEquals(first, preview.state.value)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch survives a location it cannot read`() = runBlocking {
        val storage = MemoryStorage()
        storage.write("document.json", JsonDocumentCodec.encode(editorDocument()))
        val preview = EditorPreview(
            session = session(),
            analyze = { AnalysisResult(emptyList(), resolved) },
        )
        val watcher = launch { preview.watch(storage, "other.json", interval = 20.milliseconds) }
        try {
            delay(100)
            val refused = assertIs<PreviewState.Refused>(preview.state.value)
            assertEquals(PreviewOrigin.External, refused.origin)
            assertNotNull(refused.reason)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch reads a real file`() = runBlocking {
        val file = Files.createTempFile("lutter-preview", ".json")
        try {
            val storage: DocumentStorage = FileDocumentStorage()
            val location = file.toAbsolutePath().toString()
            storage.write(location, JsonDocumentCodec.encode(editorDocument()))
            val preview = EditorPreview(
                session = session(),
                analyze = { AnalysisResult(emptyList(), resolved) },
            )
            val watcher = launch { preview.watch(storage, location, interval = 20.milliseconds) }
            try {
                delay(100)
                val first = assertIs<PreviewState.Rendered>(preview.state.value)
                assertEquals(PreviewOrigin.External, first.origin)

                // A save that truncates before writing is the ordinary shape of a bad read: the
                // preview keeps the document it drew and says what it could not lower.
                storage.write(location, "{}".encodeToByteArray())
                delay(100)
                assertIs<PreviewState.Refused>(preview.state.value)

                storage.write(location, JsonDocumentCodec.encode(editorDocument()))
                delay(100)
                assertIs<PreviewState.Rendered>(preview.state.value)
            } finally {
                watcher.cancel()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `watch does not decode the same refused bytes twice`() = runBlocking {
        val storage = MemoryStorage()
        val location = "document.json"
        storage.write(location, "broken".encodeToByteArray())
        var analyzed: UiDocument? = null
        val preview = EditorPreview(
            session = session(),
            analyze = { document ->
                analyzed = document
                AnalysisResult(emptyList(), resolved)
            },
        )
        val watcher = launch { preview.watch(storage, location, interval = 20.milliseconds) }
        try {
            delay(150)
            assertIs<PreviewState.Refused>(preview.state.value)
            // The bytes never decoded, so analysis was never asked about them.
            assertTrue(analyzed == null)
            assertTrue(preview.state.value is PreviewState.Refused)
        } finally {
            watcher.cancel()
        }
    }

    private class MemoryStorage : DocumentStorage {
        private val files = mutableMapOf<String, ByteArray>()

        override suspend fun read(location: String): ByteArray =
            files[location] ?: throw IllegalStateException("no document at '$location'")

        override suspend fun write(location: String, bytes: ByteArray) {
            files[location] = bytes
        }
    }
}
