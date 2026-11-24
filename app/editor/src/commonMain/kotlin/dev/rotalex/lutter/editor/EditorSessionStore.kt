package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.storage.DocumentStorage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What reopening a document should restore (PLAN §32 Phase 17 S6).
 *
 * History is deliberately absent: §26.3 says the undo stack is session state, not document state,
 * and a stack restored from disk would let a user undo an edit whose document no longer exists in
 * the form the inverse patch expects. The record is what to *reopen*, never what to un-do.
 *
 * [selection] is a string-keyed mirror of [EditorSelection] rather than that type itself: the
 * selection's ids are value classes from `:engine:model`, which does not depend on serialization,
 * so the record spells them out and the mapping lives in one place here.
 */
@Serializable
public data class EditorSessionRecord(
    /** Where the document lives, in [DocumentStorage]'s spelling. */
    public val documentLocation: String,
    public val selection: SelectionRecord = SelectionRecord.None,
    public val panels: PanelRecord = PanelRecord(),
) {
    /** The selection, as JSON can hold it. */
    @Serializable
    public sealed interface SelectionRecord {
        @Serializable
        public data object None : SelectionRecord

        @Serializable
        @SerialName("node")
        public data class Node(public val id: String) : SelectionRecord

        @Serializable
        @SerialName("page")
        public data class Page(public val id: String) : SelectionRecord
    }

    /** Which panes the host had open. The host decides what "open" means; this only remembers. */
    @Serializable
    public data class PanelRecord(
        public val palette: Boolean = true,
        public val inspector: Boolean = true,
    )
}

/** [selection] as the record spells it. */
public fun EditorSelection.toRecord(): EditorSessionRecord.SelectionRecord =
    when (this) {
        EditorSelection.None -> EditorSessionRecord.SelectionRecord.None
        is EditorSelection.Node -> EditorSessionRecord.SelectionRecord.Node(id.value)
        is EditorSelection.Page -> EditorSessionRecord.SelectionRecord.Page(id.value)
    }

/** The selection [record] names. */
public fun EditorSessionRecord.SelectionRecord.toSelection(): EditorSelection =
    when (this) {
        EditorSessionRecord.SelectionRecord.None -> EditorSelection.None
        is EditorSessionRecord.SelectionRecord.Node -> EditorSelection.Node(NodeId(id))
        is EditorSessionRecord.SelectionRecord.Page -> EditorSelection.Page(PageId(id))
    }

/**
 * Reads and writes [EditorSessionRecord]s through [DocumentStorage] (PLAN §32 Phase 17 P4).
 *
 * A store and not a session field: where a session record lives is a host's answer — one file
 * beside the document, one per workspace, one in an app's data directory — and a session that
 * assumed one would be a session that cannot be tested without it.
 *
 * ### A missing or unreadable record is not an error
 *
 * [load] answers null for a location nothing is stored at and for bytes that do not parse. Both
 * mean the same thing to a host — there is nothing to restore — and both are ordinary: a first
 * run has no record, and a record written by an older editor is not a failure of this one. A
 * corrupt record is not deleted; the host decides whether the next [save] replaces it.
 */
public class EditorSessionStore(
    private val storage: DocumentStorage,
    private val json: Json = DEFAULT_JSON,
) {

    /** Writes [record] at [location], replacing whatever was there. */
    public suspend fun save(location: String, record: EditorSessionRecord): Unit {
        val text: String = json.encodeToString(EditorSessionRecord.serializer(), record)
        storage.write(location, text.encodeToByteArray())
    }

    /** The record at [location], or null when nothing is stored there or it does not parse. */
    public suspend fun load(location: String): EditorSessionRecord? {
        val bytes: ByteArray = try {
            storage.read(location)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (missing: Exception) {
            return null
        }
        return try {
            json.decodeFromString(EditorSessionRecord.serializer(), bytes.decodeToString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (corrupt: Exception) {
            null
        }
    }

    private companion object {
        /**
         * Strict about what it reads in one direction only: unknown keys are ignored so a record
         * written by a later editor still opens here, and missing optional fields take their
         * defaults. Everything else — a wrong type, a bad discriminator — is a null record rather
         * than a crash, because the caller's answer to both is the same.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

/** The schema type this module's session is generic over. */
public typealias EditorSchema = SchemaView<
    ComponentSpec,
    ModifierSpec,
    ActionSpec,
    FunctionSpec,
    TypeSpec>

/**
 * A session reopened on [document], with [record]'s selection when the document still backs it.
 *
 * The history starts empty: [EditorSession.open] creates a fresh `DocumentController`, and that is
 * the whole of "clears history" — there is nothing to clear, because the stack was never
 * persisted.
 *
 * A selection naming a node or page this document does not hold becomes [EditorSelection.None]
 * rather than an error: a record outlives the document it describes, and a selection that cannot
 * be drawn is not a reason to refuse to open one that can.
 */
public fun EditorSession.Companion.open(
    document: UiDocument,
    schema: EditorSchema,
    record: EditorSessionRecord,
): EditorSession {
    val session: EditorSession = EditorSession.open(document, schema)
    session.select(session.backedSelection(record.selection.toSelection()))
    return session
}

/** [selection] when [EditorSession.document] holds what it names, [EditorSelection.None] otherwise. */
public fun EditorSession.backedSelection(selection: EditorSelection): EditorSelection =
    when (selection) {
        EditorSelection.None -> EditorSelection.None
        is EditorSelection.Node ->
            if (selection.id in document.nodes) selection else EditorSelection.None
        is EditorSelection.Page ->
            if (document.pages.containsKey(selection.id)) selection else EditorSelection.None
    }
