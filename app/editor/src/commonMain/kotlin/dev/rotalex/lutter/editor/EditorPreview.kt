package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.DocumentCodec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the preview is showing, or why it is showing something older.
 *
 * [Refused] carries [lastRendered] rather than the preview keeping a document off to one side:
 * "this edit does not resolve" and "the screen still shows the last thing that did" are one fact
 * about one screen, and a reader that has to join two sources to learn it is a reader that will
 * eventually disagree with what is drawn.
 */
public sealed interface PreviewState {

    /** Nothing has been resolved yet. */
    public data object Empty : PreviewState

    /** [document] as it was read, and the form analysis lowered from it. */
    public data class Rendered(
        public val document: UiDocument,
        public val resolved: ResolvedDocument,
        public val origin: PreviewOrigin,
        /** The controller revision, or null when [origin] is [PreviewOrigin.External]. */
        public val revision: Long? = null,
    ) : PreviewState

    /**
     * [origin]'s document did not resolve. [lastRendered] stays on screen, or nothing does when
     * there never was a good render.
     */
    public data class Refused(
        public val origin: PreviewOrigin,
        public val diagnostics: List<Diagnostic>,
        public val lastRendered: Rendered?,
        /** What failed before there was a document to analyze — a decode or a read. */
        public val reason: String? = null,
    ) : PreviewState
}

/** Where the document on screen came from. */
public enum class PreviewOrigin {
    /** The editing session — the document the user is editing. */
    Session,

    /** Bytes at a watched location, read directly. */
    External,
}

/**
 * The document the session currently holds, lowered, with the last good render kept on refusal
 * (PLAN §32 Phase 17 S7).
 *
 * ### Two sources, one screen
 *
 * [follow] is the controller path: every revision the session announces re-resolves the document
 * it holds, so what the preview shows is what the editor just did. [watch] is the file path: a
 * document being edited elsewhere re-resolves from its bytes through [DocumentStorage], which is
 * the same seam the sample's `DocumentReloader` polls. Neither rebuilds a `UiRuntime` — the
 * runtime comes from the schema, and a document does not carry a schema — so a reload is an
 * analysis and not a new engine.
 *
 * ### The resolver is injected
 *
 * `:app:editor` has no builtins pack and does not want one: `BuiltinEngine.resolve` lives in a
 * Compose module, and a platform-neutral test should not need a renderer registry to learn
 * whether a document lowers. The host passes `{ BuiltinEngine.analyze(schema, it) }` or, in a
 * test, an [dev.rotalex.lutter.analysis.Analyzer] over the fixture schema.
 *
 * ### Refusals keep the last good render
 *
 * A document saved half-written is the ordinary case, not an edge: an editor truncates before it
 * writes. [PreviewState.Refused] reports it and leaves whatever resolved last on screen. The
 * revision is not marked as rendered on refusal, so the next [follow] tick retries rather than
 * trusting a failure it never adopted.
 *
 * @param session the document source of truth. The preview never writes to it.
 * @param analyze lowers one document, findings included.
 * @param codec reads watched bytes. The default is the canonical JSON codec.
 */
public class EditorPreview(
    private val session: EditorSession,
    private val analyze: (UiDocument) -> AnalysisResult,
    private val codec: DocumentCodec = JsonDocumentCodec,
) {

    private val stateFlow: MutableStateFlow<PreviewState> = MutableStateFlow(PreviewState.Empty)

    /** What the preview currently shows. Written by [refresh], [follow] and [watch]. */
    public val state: StateFlow<PreviewState> = stateFlow.asStateFlow()

    /**
     * The revision the last successful session render was built from, or null when the session has
     * not rendered yet. A refused revision is deliberately not recorded.
     */
    private var renderedRevision: Long? = null

    /**
     * Re-resolves the session's document when its revision moved, and returns what is shown.
     *
     * Cheap to call on every announcement: an unchanged revision returns the current state
     * untouched, which is what keeps a renderer from being woken for a revision it already drew.
     */
    public fun refresh(): PreviewState {
        val revision: Long = session.controller.state.value.revision
        val current: PreviewState = stateFlow.value
        if (revision == renderedRevision && current is PreviewState.Rendered) return current
        return renderSession(revision, session.document)
    }

    /**
     * Re-resolves on every controller revision until cancelled.
     *
     * Suspends for the lifetime of the collector; a host runs it in a scope tied to its window.
     * Cancellation is rethrown: it means the window is gone.
     */
    public suspend fun follow(): Nothing {
        refresh()
        try {
            session.controller.changes.collect { refresh() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        // `changes` is a `SharedFlow`, so the collection above only ends by cancellation, which
        // is rethrown. The call is unreachable rather than a loop that could exit.
        error("the controller's change flow completed")
    }

    /**
     * Re-resolves [location]'s bytes whenever they change, keeping the last good render on a
     * refusal, until cancelled.
     *
     * The comparison is on bytes, not on a modification time: a `touch` and a `git checkout` that
     * restores the bytes already on screen are not edits, and a coarse timestamp cannot separate
     * two saves inside its own resolution. The same bytes that were refused are not decoded twice,
     * so a half-written file costs one failure rather than one per tick.
     *
     * @param storage where the bytes come from — the only thing this path knows about a filesystem.
     * @param location the document to watch, as [DocumentStorage] spells it.
     * @param interval how long between reads. A quarter of a second: under the threshold where a
     *   reload reads as instant and over the one where a save reads as several reloads.
     */
    public suspend fun watch(
        storage: DocumentStorage,
        location: String,
        interval: Duration = DEFAULT_POLL_INTERVAL,
    ): Nothing {
        var settled: ByteArray? = null
        var refused: ByteArray? = null
        while (true) {
            val bytes: ByteArray? = readOrKeep(storage, location)
            if (bytes != null) {
                val onScreen: ByteArray? = settled
                when {
                    onScreen != null && onScreen.contentEquals(bytes) -> {
                        // Unchanged since the render on screen; a refusal the file was restored
                        // from goes with it.
                        refused = null
                    }

                    refused != null && refused.contentEquals(bytes) -> {
                        // Already refused, and decoding the same half-written file again would
                        // fail the same way.
                    }

                    else -> {
                        refused = bytes
                        val rendered: PreviewState.Rendered? = renderExternal(bytes)
                        if (rendered != null) {
                            settled = bytes
                            refused = null
                        }
                    }
                }
            }
            delay(interval)
        }
    }

    /** [location]'s bytes, or null after leaving the last good render in place. */
    private suspend fun readOrKeep(storage: DocumentStorage, location: String): ByteArray? =
        try {
            storage.read(location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            // A deleted file, a half-finished rename, a lock. The file comes back, so this is not
            // a document that failed to decode and it does not end the watch.
            stateFlow.value = PreviewState.Refused(
                origin = PreviewOrigin.External,
                diagnostics = emptyList(),
                lastRendered = lastRendered(),
                reason = describe(unreadable),
            )
            null
        }

    /** [bytes] decoded and resolved, or null after publishing the refusal. */
    private fun renderExternal(bytes: ByteArray): PreviewState.Rendered? {
        val decoded: UiDocument = try {
            codec.decode(bytes).document
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: Exception) {
            refuse(PreviewOrigin.External, describe(rejected))
            return null
        }
        return renderExternalDocument(decoded)
    }

    private fun renderExternalDocument(document: UiDocument): PreviewState.Rendered? {
        val result: AnalysisResult = analyze(document)
        val resolved: ResolvedDocument? = result.resolved
        if (resolved == null) {
            stateFlow.value = PreviewState.Refused(
                origin = PreviewOrigin.External,
                diagnostics = result.diagnostics,
                lastRendered = lastRendered(),
            )
            return null
        }
        val rendered = PreviewState.Rendered(
            document = document,
            resolved = resolved,
            origin = PreviewOrigin.External,
            revision = null,
        )
        stateFlow.value = rendered
        return rendered
    }

    private fun renderSession(revision: Long, document: UiDocument): PreviewState {
        val result: AnalysisResult = analyze(document)
        val resolved: ResolvedDocument? = result.resolved
        val next: PreviewState = if (resolved == null) {
            PreviewState.Refused(
                origin = PreviewOrigin.Session,
                diagnostics = result.diagnostics,
                lastRendered = lastRendered(),
            )
        } else {
            renderedRevision = revision
            PreviewState.Rendered(
                document = document,
                resolved = resolved,
                origin = PreviewOrigin.Session,
                revision = revision,
            )
        }
        stateFlow.value = next
        return next
    }

    private fun refuse(origin: PreviewOrigin, reason: String): Unit {
        stateFlow.value = PreviewState.Refused(
            origin = origin,
            diagnostics = emptyList(),
            lastRendered = lastRendered(),
            reason = reason,
        )
    }

    private fun lastRendered(): PreviewState.Rendered? = stateFlow.value as? PreviewState.Rendered

    private companion object {
        val DEFAULT_POLL_INTERVAL: Duration = 250.milliseconds

        /** What refused, in a form a maintainer can act on: the type says *what* refused. */
        fun describe(failure: Throwable): String {
            val name: String = failure::class.simpleName ?: "failure"
            val message: String? = failure.message
            return if (message.isNullOrBlank()) name else "$name: $message"
        }
    }
}
