package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.coroutines.cancellation.CancellationException

/**
 * The document the editor owns, and where it remembers it (PLAN §32 Phase 17 S6).
 *
 * ### One rule, two hosts
 *
 * This lives in `:app:editor` and not in a host because both hosts save: the desktop window and
 * the Android activity edit the same document with the same panes, and two saving routines would
 * be two answers to "did that save?" — the question a person asks at the moment they stop typing.
 * What stays a host's decision is **where** the bytes go: a workspace directory on the desktop, an
 * app's private `filesDir` on Android, each through [DocumentStorage].
 *
 * ### The editor must be able to save
 *
 * Until this class existed the editor edited a document that lived only in memory: close the
 * window, or turn the phone, and the work was gone — silently, with nothing on screen saying so.
 * That is not a missing feature, it is a trap.
 *
 * ### One document at a time, and a location that can be predicted
 *
 * The editor works on exactly one document, so "save" needs no dialog and no picker. The host
 * decides the location once, at open:
 *
 * - a document named on the command line is edited **in place** — naming a file is asking to edit
 *   that file, and writing somewhere else would be the editor having a different opinion;
 * - otherwise the document is a working copy in the host's own workspace, seeded from the starter
 *   that ships with the app. The repository is never written to, which is what makes `gradle run`
 *   safe and what makes a packaged launch possible at all.
 *
 * The session record is a separate location on purpose: it is editor state, and dropping a
 * `session.json` beside a colleague's document would be this editor leaving litter in somebody
 * else's directory.
 *
 * ### Why a document that does not parse is kept
 *
 * A corrupt working copy is still the only copy of somebody's work. It is written to
 * `<name>.corrupt` before the seed replaces it, and [Opened.notice] says so — so the editor
 * starts, and the bytes are on disk for whoever wants to look at them.
 */
public class EditorWorkspace private constructor(
    private val storage: DocumentStorage,

    /** Where the document lives, in [DocumentStorage]'s spelling. */
    public val documentLocation: String,

    /** Where the session record lives. */
    private val recordLocation: String,
) {

    private val store: EditorSessionStore = EditorSessionStore(storage)

    /**
     * Writes [document], then [record].
     *
     * The order is the point: a record written ahead of the document would describe a shape the
     * file does not have yet, and a selection naming a node that is not there is worse than a stale
     * one that is. Both go through [storage], whose implementations are what make each write
     * indivisible — this class never assumes an atomic filesystem, it only assumes the seam.
     */
    public suspend fun save(document: UiDocument, record: EditorSessionRecord): Unit {
        storage.write(documentLocation, JsonDocumentCodec.encode(document))
        store.save(recordLocation, record.copy(documentLocation = documentLocation))
    }

    public companion object {

        /**
         * The workspace holding [documentLocation] and [recordLocation], opened on the document at
         * [documentLocation] when that location holds one and on [seed] when it does not.
         *
         * [seed] is written out immediately when nothing was restored, so the first frame already
         * has a file behind it and the very first edit has somewhere to land.
         */
        public suspend fun open(
            documentLocation: String,
            recordLocation: String,
            seed: UiDocument,
            storage: DocumentStorage,
        ): Opened {
            val workspace = EditorWorkspace(storage, documentLocation, recordLocation)
            val stored: ByteArray? = readOrNull(storage, documentLocation)
            val decoded: UiDocument? = stored?.let { bytes ->
                try {
                    JsonDocumentCodec.decode(bytes).document
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    // Kept, not deleted: see the class comment.
                    storage.write(documentLocation + CORRUPT_SUFFIX, bytes)
                    null
                }
            }

            val notice: String? =
                if (decoded == null && stored != null) {
                    "The saved document did not parse; it is kept at " +
                        "'$documentLocation$CORRUPT_SUFFIX' and the starter was opened instead."
                } else {
                    null
                }
            if (decoded == null) storage.write(documentLocation, JsonDocumentCodec.encode(seed))

            return Opened(
                workspace = workspace,
                document = decoded ?: seed,
                record = workspace.store.load(recordLocation),
                notice = notice,
            )
        }

        private const val CORRUPT_SUFFIX: String = ".corrupt"

        /** Bytes at [location], or null when nothing is there — a first run is not a failure. */
        private suspend fun readOrNull(storage: DocumentStorage, location: String): ByteArray? =
            try {
                storage.read(location)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: Exception) {
                null
            }
    }
}

/** [EditorWorkspace.open]'s answer, so a host builds its window from one object rather than three. */
public class Opened(
    public val workspace: EditorWorkspace,
    public val document: UiDocument,

    /** The selection to restore, or null on a first run. */
    public val record: EditorSessionRecord?,

    /** Something the user should be told about the state the document was found in. */
    public val notice: String?,
)
