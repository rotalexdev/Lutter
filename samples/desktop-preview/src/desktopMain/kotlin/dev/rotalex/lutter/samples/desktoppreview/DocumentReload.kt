package dev.rotalex.lutter.samples.desktoppreview

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * One loaded document: the form the codec read and the form analysis lowered from it.
 *
 * Both halves travel together because lowering drops one of them. `ResolvedDocument` carries no
 * `app.startPage` — the start page is a document fact no pass carries into the resolved form —
 * so a window that holds only the lowered half cannot say where to open. `main` already reads
 * the document twice over for that reason; this is that pair, named once, so the reload path
 * cannot end up carrying one half of it.
 *
 * **Not a `data class`.** The composition compares this by `equals` twice: through the snapshot
 * policy that decides whether a reload invalidates anything, and through the `key` that decides
 * whether the page store is rebuilt. A structural comparison over every node of a resolved
 * document, on every recomposition, is not a price worth paying for something that happens when
 * a file changes. Identity is also the honest question here — two loads of identical bytes still
 * produce two objects, and that *is* a reload.
 */
internal class LoadedDocument(
    val decoded: UiDocument,
    val resolved: ResolvedDocument,
)

/**
 * What the window says above the document, or that it has nothing to say.
 *
 * A state and not a string because the two things that can go wrong are not the same thing to a
 * reader: bytes that did not arrive ([Unreadable]) and bytes that arrived and were refused
 * ([Rejected]) want different words, and the second is the one a user has to go and fix in a
 * document. [Bundled] is neither — it is the packaged case, where nothing is being watched at
 * all and the user should know that before they edit a file and wonder.
 *
 * Every classifier here says `internal`, including the nested ones. The nested members of an
 * interface are `public` unless they are told otherwise, and this module's ABI dump is a
 * published artifact that this unit is not allowed to edit: a nested classifier written as
 * `public` is a `public` class in the bytecode and belongs in `api/desktop/desktop-preview.api`,
 * so the marker is load-bearing rather than decorative.
 */
internal sealed class PreviewStatus {

    /**
     * The line to draw above the document, or null when there is nothing to say.
     *
     * Null for [Watching] on purpose: a window that renders a document should not carry chrome
     * explaining that it is working, and the screenshot this module publishes is a picture of
     * the document rather than of the preview.
     */
    val message: String?

    /** Whether [message] is a refusal rather than a note about what the preview is doing. */
    val isFailure: Boolean

    /** A file is being watched and its bytes are the ones on screen. */
    data class Watching(val location: String) : PreviewStatus {
        override val message: String? = null
        override val isFailure: Boolean = false
    }

    /** Nothing on disk was named, so the copy bundled in the jar is what is on screen. */
    data object Bundled : PreviewStatus {
        override val message: String =
            "Showing the document bundled in the jar: no file is being watched. " +
                "Pass one to watch it: LutterPreview <document.json>"
        override val isFailure: Boolean = false
    }

    /**
     * The watched file is gone, locked, or mid-replacement. The last document stays on screen.
     *
     * Not a refusal: nothing was read, so there is nothing wrong with what *was* on disk.
     */
    data class Unreadable(val location: String, val reason: String) : PreviewStatus {
        override val message: String = "Cannot read '$location': $reason"
        override val isFailure: Boolean = true
    }

    /** The bytes on disk are not a document this engine renders. The last one stays on screen. */
    data class Rejected(val reason: String) : PreviewStatus {
        override val message: String = "The document on disk was refused: $reason"
        override val isFailure: Boolean = true
    }
}

/**
 * Re-reads one document through [DocumentStorage] and reports what it finds (PLAN §32 Phase 10).
 *
 * ### Why a poll and not a watch
 *
 * The alternatives weighed were `java.nio.file.WatchService`, a modification-time check, and
 * this. The first two lose for reasons that are about correctness rather than taste.
 *
 * **A `WatchService` does not survive an editor's save.** Most editors do not write a file in
 * place: they write a temporary file beside it and rename it over the target, which replaces
 * the inode. A watch registered on the file then fires once — the move-away — and never again,
 * because it is watching a name that no longer resolves to anything. It has to be registered on
 * the parent directory, filtered by name, and re-armed when it goes; and on macOS the JDK's own
 * implementation is `PollingWatchService`, a sampler that runs every two seconds, so on the
 * platform most people will run this from source the "watch" is a slower poll than the one
 * below. A watch that has stopped reporting is the one failure a preview cannot have: the user
 * saves, nothing happens, and nothing on screen says why.
 *
 * **A modification time cannot be the check.** It is one `stat` instead of one read, and it is
 * wrong twice: a file system with a coarse timestamp cannot separate two saves inside its own
 * resolution, and a timestamp is not content, so a `touch`, a `git checkout` that restores the
 * bytes already on screen, and a restore-from-backup all read as a change. It is also not
 * `DocumentStorage`: it needs a path and a stat, which is the one implementation this preview
 * must not depend on — the storage seam is what lets a test drive this loop with no filesystem
 * at all.
 *
 * **This costs one read of a small file, every [POLL_INTERVAL], on a background dispatcher.**
 * That is the whole cost, it is only paid while the window is open, and what it buys is that the
 * answer does not depend on the operating system delivering an event: the bytes on disk are
 * compared with the bytes on screen, directly. That is the trade this module wants, because the
 * failure mode above is a preview that silently stops working.
 *
 * ### What a change costs
 *
 * A decode and a `resolve`, on the calling dispatcher. `UiRuntime` is *not* rebuilt: it is
 * assembled from the schema, which a document does not carry, so a reload is an analysis and not
 * a new engine. The result is one [LoadedDocument], and swapping that value is the whole of the
 * re-render.
 *
 * ### Two saves inside one interval
 *
 * They collapse, with nothing to arrange. The loop asks what is on disk *now* and keeps no queue
 * of pending reloads, so a document written twice between two reads is seen once, at its final
 * contents. That is the correct answer for a preview rather than a lucky one: the intermediate
 * state is a file nobody meant to leave behind.
 *
 * @param storage where the bytes come from. The only thing this loop knows about a filesystem.
 * @param location the document to watch, as [DocumentStorage] spells a location.
 * @param schema the schema both loads are resolved against — built once by the caller, because
 *   the schema is what the engine is built from and not what a document carries.
 * @param onLoaded a document was loaded. Called on the first read too, so the screen and the
 *   loop's idea of it start from the same observation rather than from two that might disagree.
 * @param onStatus what the loop last saw. Written whenever it changes and not when it does not,
 *   so a healthy poll costs a comparison rather than a recomposition.
 */
internal class DocumentReloader(
    private val storage: DocumentStorage,
    private val location: String,
    private val schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    private val onLoaded: (LoadedDocument) -> Unit,
    private val onStatus: (PreviewStatus) -> Unit,
    private val interval: Duration = POLL_INTERVAL,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {

    /**
     * Reads, compares and loads, forever, until the coroutine this runs in is cancelled.
     *
     * The wait is at the bottom so the first read is immediate, and the first read always
     * loads: the caller opened its window on a document it read itself, and the loop has not
     * seen a single byte yet. That costs one extra decode and resolve a quarter of a second
     * after launch, and it buys the only arrangement in which a torn read between the caller's
     * read and the loop's cannot leave a stale document on screen for good.
     *
     * Cancellation is not an error here and is rethrown before anything else: the window
     * closing cancels this coroutine, and a loop that swallowed that would keep reading a file
     * for a window that is gone.
     */
    suspend fun run(): Unit {
        // Bytes the document on screen was built from, and the bytes already refused. The
        // second exists so a half-written file is decoded once rather than on every tick; the
        // first is cleared when the file comes back to it, so undoing a bad edit takes the
        // error off the screen instead of stranding it there.
        var settled: ByteArray? = null
        var refused: ByteArray? = null

        while (true) {
            // Null only when the read failed, and [readOrReport] has already said why. The
            // `if` rather than a null arm of a `when` so the smart cast that makes the bytes
            // usable below is one the compiler can read off an ordinary condition.
            val bytes: ByteArray? = readOrReport()
            if (bytes != null) {
                val current: ByteArray? = settled
                when {
                    current != null && current.contentEquals(bytes) -> {
                        // Untouched since the document on screen was built from it. A refusal
                        // the file has been put back from goes with it.
                        refused = null
                        onStatus(PreviewStatus.Watching(location))
                    }

                    refused != null && refused.contentEquals(bytes) -> {
                        // The same bytes already refused. Decoding them again would fail the
                        // same way on every tick until the file is rewritten, and a half-written
                        // file stays half-written until it is rewritten.
                    }

                    else -> {
                        refused = bytes
                        val loaded: LoadedDocument? = loadOrReport(bytes)
                        if (loaded != null) {
                            settled = bytes
                            refused = null
                            onLoaded(loaded)
                            onStatus(PreviewStatus.Watching(location))
                        }
                    }
                }
            }
            sleep(interval)
        }
    }

    /** [location]'s bytes, or null after reporting that it could not be read. */
    private suspend fun readOrReport(): ByteArray? =
        try {
            storage.read(location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            // A deleted file, a half-finished rename, a lock. None of them is a document that
            // failed to decode, and none of them ends the loop: the file comes back.
            onStatus(PreviewStatus.Unreadable(location, describe(unreadable)))
            null
        }

    /** [bytes] decoded and resolved, or null after reporting that it is not a document. */
    private fun loadOrReport(bytes: ByteArray): LoadedDocument? =
        try {
            val decoded: UiDocument = JsonDocumentCodec.decode(bytes).document
            LoadedDocument(decoded, BuiltinEngine.resolve(schema, decoded))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: Exception) {
            // A file saved half-written is the ordinary case here, not an edge: an editor
            // truncates before it writes, and a preview that dies on one is a preview nobody
            // leaves open. The last good document stays on screen and the reason goes above it.
            onStatus(PreviewStatus.Rejected(describe(rejected)))
            null
        }

    private companion object {
        /**
         * How long between reads.
         *
         * A quarter of a second, which is under the threshold where a reload reads as instant
         * and comfortably over the threshold where a save is read as several reloads. Four reads
         * of a document-sized file a second, on a dispatcher that does nothing else, is not a
         * cost worth optimising against; a debounce above it would make the common case slower
         * to buy a bound that a byte comparison already provides.
         */
        val POLL_INTERVAL: Duration = 250.milliseconds
    }
}

/**
 * What refused, in a form a maintainer can act on.
 *
 * The type as well as the message because a message on its own does not say *what* refused: a
 * `SerializationException` says the bytes are not JSON and an `IllegalStateException` saying the
 * document does not resolve are two different files to go and look at.
 */
private fun describe(failure: Throwable): String {
    val name: String = failure::class.simpleName ?: "failure"
    val message: String? = failure.message
    return if (message.isNullOrBlank()) name else "$name: $message"
}
