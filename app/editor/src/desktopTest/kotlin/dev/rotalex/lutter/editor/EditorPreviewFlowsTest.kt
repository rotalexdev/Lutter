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
        Analyzer(schema).analyze(editorDocument()).resolved
            ?: error("the preview fixtures must resolve, or no render case is reachable")

    @Test
    fun `follow re-resolves after a dispatch`(): Unit = runBlocking {
        val session = session()
        val preview = EditorPreview(
            session = session,
            analyze = { AnalysisResult(emptyList(), resolved) },
        )
        val watcher = launch { preview.follow() }
        try {
            // The first read is immediate, so the preview starts from the same document the
            // session holds rather than from nothing.
            val first = assertIs<PreviewState.Rendered>(await(preview) { it is PreviewState.Rendered })
            assertEquals(0L, first.revision)

            session.dispatch(Command.RemoveNode(LEAF))

            val second = assertIs<PreviewState.Rendered>(
                await(preview) { it is PreviewState.Rendered && it.revision == 1L },
            )
            assertEquals(1L, second.revision)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch renders external bytes and keeps the last good render on a refusal`(): Unit = runBlocking {
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
            val first = assertIs<PreviewState.Rendered>(await(preview) { it is PreviewState.Rendered })
            assertEquals(PreviewOrigin.External, first.origin)
            assertNull(first.revision)
            assertContentEquals(good, JsonDocumentCodec.encode(first.document))

            storage.write(location, "not a document".encodeToByteArray())
            val refused = assertIs<PreviewState.Refused>(await(preview) { it is PreviewState.Refused })
            assertEquals(PreviewOrigin.External, refused.origin)
            assertEquals(first, refused.lastRendered)
            assertNotNull(refused.reason)

            // The bytes on screen come back, so the refusal goes with them instead of staying
            // stranded above a document that reads fine.
            storage.write(location, good)
            assertEquals(first, await(preview) { it is PreviewState.Rendered })
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch survives a location it cannot read`(): Unit = runBlocking {
        val storage = MemoryStorage()
        storage.write("document.json", JsonDocumentCodec.encode(editorDocument()))
        val preview = EditorPreview(
            session = session(),
            analyze = { AnalysisResult(emptyList(), resolved) },
        )
        val watcher = launch { preview.watch(storage, "other.json", interval = 20.milliseconds) }
        try {
            val refused = assertIs<PreviewState.Refused>(await(preview) { it is PreviewState.Refused })
            assertEquals(PreviewOrigin.External, refused.origin)
            assertNotNull(refused.reason)
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `watch reads a real file`(): Unit = runBlocking {
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
                val first = assertIs<PreviewState.Rendered>(await(preview) { it is PreviewState.Rendered })
                assertEquals(PreviewOrigin.External, first.origin)

                // A save that truncates before writing is the ordinary shape of a bad read: the
                // preview keeps the document it drew and says what it could not lower.
                storage.write(location, "{}".encodeToByteArray())
                assertIs<PreviewState.Refused>(await(preview) { it is PreviewState.Refused })

                storage.write(location, JsonDocumentCodec.encode(editorDocument()))
                assertIs<PreviewState.Rendered>(await(preview) { it is PreviewState.Rendered })
            } finally {
                watcher.cancel()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `watch does not decode the same refused bytes twice`(): Unit = runBlocking {
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
            assertIs<PreviewState.Refused>(await(preview) { it is PreviewState.Refused })
            // The bytes never decoded, so analysis was never asked about them.
            assertTrue(analyzed == null)
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Waits for the preview to reach a state, reporting what it did reach.
     *
     * A fixed `delay` makes the assertion about the runner's speed rather than about the preview:
     * the poll interval and the scheduler are both outside this test's control.
     */
    private suspend fun await(
        preview: EditorPreview,
        condition: (PreviewState) -> Boolean,
    ): PreviewState {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val state = preview.state.value
            if (condition(state)) return state
            delay(10)
        }
        error("preview never reached the expected state; it holds ${preview.state.value}")
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS: Long = 3_000
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
