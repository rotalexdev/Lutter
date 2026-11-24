package dev.rotalex.lutter.desktopapp

import dev.rotalex.lutter.editor.EditorSessionRecord
import dev.rotalex.lutter.editor.EditorSessionStore
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import dev.rotalex.lutter.serialization.storage.FileDocumentStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The document the editor owns, and where it remembers it (PLAN §32 Phase 17 S6).
 *
 * ### The editor must be able to save
 *
 * Until this class existed the window edited a document that lived only in memory: close it and
 * the work was gone, silently, with no indicator that anything had been lost. That is not a
 * missing feature, it is a trap, and it is why a packaged editor could not honestly be handed to
 * anyone.
 *
 * ### One document at a time, and a location the user can predict
 *
 * The editor works on exactly one document, so "save" needs no dialog and no file picker. The
 * location is decided once, at open:
 *
 * - a path named on the command line is edited **in place** — naming a file is asking to edit
 *   that file, and writing somewhere else would be the editor having a different opinion;
 * - with no path, the document is `<workspace>/document.json`, a working copy seeded from the
 *   starter that ships inside the jar. The repository is never written to, which is what makes
 *   `gradle run` safe to use.
 *
 * The session record — where the selection was — always lives beside the workspace, never beside
 * a document the user named: it is editor state, and dropping a `session.json` next to a
 * colleague's file would be this editor leaving litter in someone else's directory.
 *
 * ### Why every write is atomic
 *
 * The window saves on every applied edit, which means a save can be interrupted by a crash, a
 * power failure or a killed process at any moment — including halfway through writing the file.
 * A plain overwrite at that instant leaves a truncated document, and the next open would refuse
 * it. So writes go to a sibling temporary file and are then moved over the target with
 * `ATOMIC_MOVE`: either the old bytes are there or the new ones are, never a prefix of either.
 *
 * ### Why a document that does not parse is kept
 *
 * A corrupt working copy is still the only copy of somebody's work. It is renamed to
 * `<name>.corrupt` before the starter is written in its place, and [Opened.notice] says so — the
 * editor starts, and the bytes are on disk for whoever wants to look at them.
 */
internal class EditorWorkspace private constructor(
    private val storage: DocumentStorage,

    /** Where the document lives, in [DocumentStorage]'s spelling. */
    val documentLocation: String,

    /** Where the session record lives. */
    private val recordLocation: String,
) {

    private val store: EditorSessionStore = EditorSessionStore(storage)

    /** The document and the session record, as they are on disk right now. */
    suspend fun save(document: UiDocument, record: EditorSessionRecord): Unit = withContext(Dispatchers.IO) {
        // The document first: a record written ahead of it would describe a shape the file does
        // not have yet, and a selection naming a node that is not there is worse than a stale
        // selection that is.
        storage.write(documentLocation, JsonDocumentCodec.encode(document))
        store.save(recordLocation, record.copy(documentLocation = documentLocation))
    }

    companion object {

        /**
         * The workspace under [directory], opened on the document at [documentLocation] when that
         * location holds one and on [seed] when it does not.
         *
         * [seed] is written out immediately when nothing was restored, so the first frame already
         * has a file behind it and the very first edit has somewhere to land.
         */
        suspend fun open(
            directory: File,
            seed: UiDocument,
            documentLocation: String,
            storage: DocumentStorage = AtomicDocumentStorage(),
        ): Opened = withContext(Dispatchers.IO) {
            val recordLocation: String = File(directory, SESSION_FILE).path
            val workspace = EditorWorkspace(storage, documentLocation, recordLocation)

            val stored: ByteArray? = readOrNull(storage, documentLocation)
            val decoded: UiDocument? = stored?.let { bytes ->
                try {
                    JsonDocumentCodec.decode(bytes).document
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: Exception) {
                    // Kept, not deleted: see the class comment.
                    Files.write(File(documentLocation + CORRUPT_SUFFIX).toPath(), bytes)
                    null
                }
            }

            val notice: String? = when {
                decoded != null -> null
                stored != null ->
                    "The saved document did not parse; it is kept at " +
                        "'$documentLocation$CORRUPT_SUFFIX' and the starter was opened instead."
                else -> null
            }
            if (decoded == null) storage.write(documentLocation, JsonDocumentCodec.encode(seed))

            Opened(
                workspace = workspace,
                document = decoded ?: seed,
                record = workspace.store.load(recordLocation),
                notice = notice,
            )
        }

        private const val SESSION_FILE: String = "session.json"
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

/** [EditorWorkspace.open]'s answer, so the window is built from one object rather than three. */
internal class Opened(
    val workspace: EditorWorkspace,
    val document: UiDocument,
    /** The selection to restore, or null on a first run. */
    val record: EditorSessionRecord?,
    /** Something the user should be told about the state the document was found in. */
    val notice: String?,
)

/**
 * [DocumentStorage] whose writes cannot be observed half-done.
 *
 * A temporary sibling and `ATOMIC_MOVE`, because the alternative — [FileDocumentStorage]'s
 * in-place overwrite — is a truncated document if the process dies mid-write, and this window
 * writes on every keystroke that reaches the document.
 *
 * The temporary file is a sibling with a fixed name rather than `createTempFile`: the move must
 * stay inside one filesystem to be atomic, and a stale `.tmp` from a previous crash is simply
 * overwritten, which is the behaviour that leaves no litter to clean up.
 */
internal class AtomicDocumentStorage(
    private val delegate: DocumentStorage = FileDocumentStorage(),
) : DocumentStorage {

    override suspend fun read(location: String): ByteArray = delegate.read(location)

    override suspend fun write(location: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val target: Path = File(location).toPath()
        target.parent?.let { parent -> Files.createDirectories(parent) }
        val temporary: Path = target.resolveSibling(target.fileName.toString() + TEMPORARY_SUFFIX)
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (atomic: IOException) {
            // A filesystem that cannot do it atomically (some network mounts) still gets a
            // replace, which is what the old writer did; refusing to save would be worse.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX: String = ".tmp"
    }
}

/**
 * Where the editor keeps its working copy and its session record.
 *
 * `LUTTER_EDITOR_HOME` wins over everything, which is what lets a test point the editor at a
 * throw-away directory instead of the developer's home — and what lets a person run two editors
 * on two documents without either of them overwriting the other's working copy.
 *
 * Otherwise the platform's own place for application data: `%APPDATA%` on Windows,
 * `~/Library/Application Support` on macOS, `$XDG_DATA_HOME` (or `~/.local/share`) everywhere
 * else. Creating directories in `~/Downloads` or beside a jar is the behaviour this avoids.
 */
internal fun workspaceDirectory(): File {
    val override: String? = System.getenv("LUTTER_EDITOR_HOME")
    if (!override.isNullOrBlank()) return File(override)

    val home: String = System.getProperty("user.home")
    val os: String = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Windows", ignoreCase = true) ->
            File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "LutterEditor")
        os.startsWith("Mac", ignoreCase = true) ->
            File(home, "Library/Application Support/LutterEditor")
        else ->
            File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "lutter-editor")
    }
}
