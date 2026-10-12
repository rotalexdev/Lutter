package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.history.History
import dev.rotalex.lutter.editing.history.MergeWindow
import dev.rotalex.lutter.editing.history.Transaction
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single gateway every edit goes through: intent in, one document out, one history entry
 * in between.
 *
 * PLAN §25.2 declares this class and §25.1 states the rule it exists to enforce — *"the engine
 * owns the document. An editor holds a `DocumentController`, never a copy of the model."* There
 * is exactly one writer of a `UiDocument` in this module, and this is where the call lands:
 * [CommandPlanner] produces a patch, [PatchApplier] applies it, and nothing else in this
 * module changes a document.
 *
 * ### The threading contract (§25.5), in full
 *
 * **Every method on this class is synchronous, and this class is not thread-safe.** Mutations
 * are single-writer: the caller serializes them, and §25.5 says so in those words — *"callers
 * serialize; the controller is not thread-safe by contract"*.
 *
 * There is no lock here and there will not be one, for the reason `PatchApplier`'s KDoc gives
 * for the same refusal on the same module: `UiDocument` and every record it holds are immutable,
 * so nothing prevents two threads from racing to produce a new one, and a lock over a type that
 * publishes through two flows would guard the wrong boundary. A caller that dispatches from a
 * background thread has to serialize itself; an editor that already owns a UI thread has
 * already done so.
 *
 * **[state] and [changes] are how an editor *observes* this class, not how it is
 * synchronized.** `StateFlow` and `SharedFlow` are thread-safe types — that is not in question
 * — but making a race disappear by making the *loser's* write observable is not a
 * synchronization strategy, it is a race with a notification attached. Read them; do not use
 * them to decide whether it is safe to write.
 *
 * ### Why the two flows and not one
 *
 * [state] answers *what is the document now* and always has an answer, so a late subscriber is
 * immediately correct. [changes] answers *what just happened* and has no answer until something
 * happens, so it costs a subscriber nothing to miss one. An editor needs both: the first to
 * draw, the second to know what to redraw. [DocumentChange.revision] is what ties them together.
 *
 * ### Three deviations from §25.2's spelling, and why each was forced
 *
 *  * **The class is generic in three type parameters.** §25.2 writes `schema: SchemaView`, and
 *    `SchemaView` has five type arguments. Two of them are fixed here because the only thing
 *    this module reads off a schema is a [ComponentSpec] and a [ModifierSpec];
 *    [CommandPlanner.plan] is generic in the other three for the same reason, and binding them
 *    here is what lets a caller pass the schema it already has.
 *  * **[dispatch] has three optional parameters after the command.** §25.2's call shape
 *    `dispatch(command)` still works unchanged. The additions are a [label], a [mergeKey] and a
 *    [window], and every one of them is a fact only the editor holds — see [Transaction.label],
 *    [Transaction.mergeKey] and [MergeWindow].
 *  * **[transaction]'s scope is this module's [TransactionScope],** which §25.2 names without
 *    declaring. It is declared at the bottom of this file rather than in one of its own,
 *    because it is two lines wide and exists only as this method's receiver.
 *
 * ### What a transaction is, precisely
 *
 * Several commands, one patch, one history entry, one revision, one announcement. Each
 * [TransactionScope.dispatch] inside the block plans against the document the previous one
 * produced — which is why "Wrap in Card" can wrap a node and then rename the node it moved —
 * and the ops accumulate in that order. §26.2's property is stated over a whole patch whose ops
 * can see each other's effects, so a composed patch is invertible in exactly the way a planned
 * one is, and undoing the entry restores the document the block started from.
 *
 * **Nothing is published until the block returns.** The block works on a local document, and the
 * state flow is written once at the end. That is not an optimisation: it means a block that
 * throws leaves the controller exactly as it found it, with no rollback to get wrong and no
 * half-applied edit on a stack that cannot undo it.
 *
 * Inside the block, [state] still describes the document the transaction started from. The
 * working document is deliberately not reachable from here — a caller that needs to read its own
 * intermediate result should dispatch against an id it minted, not reach into the engine.
 *
 * A transaction may not be nested. Two blocks each claiming to be "the one undo step" for edits
 * that interleave have no coherent answer, so the inner one is refused rather than guessed at.
 *
 * @see History for the stacks this feeds, and §26.3 for the four rules they encode.
 * @see TransactionScope for the receiver [transaction] hands its block.
 */
public class DocumentController<A : Any, F : Any, T : Any>(

    /** The document to start from. It becomes [DocumentState.document] at revision zero. */
    initial: UiDocument,

    /**
     * The schema commands are planned against.
     *
     * §8.4's overlay is applied per call inside [CommandPlanner.plan] rather than here, so
     * this holds whatever the caller passed and never becomes a cache of one document's
     * `doc.*` declarations applied to another.
     */
    private val schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,

    /**
     * Where fresh node ids come from.
     *
     * A parameter and not a member because §25.6's test client owns one:
     * [Command.DuplicateNode] and [Command.WrapNode] mint ids, and a generator invented inside
     * the controller would make planning the same command twice produce two different patches —
     * which would break §28.3's determinism claim for every scripted session.
     */
    private val ids: IdGenerator,

    /**
     * The stacks to record into.
     *
     * Injected so an editor can choose how many entries are kept — it is `History`'s `limit` —
     * and so a caller can hand the *same* history to a controller it is about to replace, which
     * is how a reopened document inherits nothing.
     */
    private val history: History = History(),
) {

    private val stateFlow: MutableStateFlow<DocumentState> =
        MutableStateFlow(DocumentState(document = initial, revision = 0L, canUndo = false, canRedo = false))

    private val changeFlow: MutableSharedFlow<DocumentChange> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = CHANGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The engine's position, as a flow.
     *
     * Always holds a current value: a collector that arrives after the last edit is immediately
     * correct and does not have to replay anything.
     */
    public val state: StateFlow<DocumentState> = stateFlow.asStateFlow()

    /**
     * Every applied patch, as a flow.
     *
     * **Nothing is replayed.** A subscriber that arrives late has missed the changes before it,
     * which is why [state] exists and why [DocumentChange.revision] is on the record: a
     * collector that sees a revision it did not receive knows to re-read [state].
     *
     * A subscriber that falls further behind than [CHANGE_BUFFER] edits loses the oldest ones
     * rather than stalling the writer, because [dispatch] is synchronous and must not block on a
     * slow renderer. Dropping is therefore possible and is why the revision is not merely a
     * convenience.
     */
    public val changes: SharedFlow<DocumentChange> = changeFlow.asSharedFlow()

    /** Whether a transaction block is running. Guards nesting and the mixed-dispatch trap. */
    private var insideTransaction: Boolean = false

    /**
     * One command, planned, applied and recorded as one history entry.
     *
     * A refused command returns its [CommandResult.Rejected] and does nothing else: no document
     * change, no revision, no announcement, no history entry, and — the part that is easy to get
     * wrong — **no clearing of the redo stack.** §26.3's second rule says a refused command
     * never enters history, and an edit that did not happen has not made the document a place
     * the undone edits could be redone into.
     *
     * @param label what the undo menu calls this edit. Empty when the caller has no name for it;
     *   §25.1 keeps undo-menu copy in the editor.
     * @param mergeKey what this edit is typing *into*, for the caller that is about to send more
     *   of the same. `null` means this edit never merges with anything, which is the ordinary
     *   case. Only the caller knows which field the caret is in, so only the caller names it.
     * @param window the caller's answer to "were those two keystrokes close together". `null`
     *   ends any open run; see [MergeWindow] for why only the caller can answer, and
     *   [History] for the comparison. A key with no window is not a merge: this class will not
     *   guess a timing nobody gave it.
     */
    public fun dispatch(
        command: Command,
        label: String = "",
        mergeKey: String? = null,
        window: MergeWindow? = null,
    ): CommandResult {
        check(!insideTransaction) {
            "a controller cannot dispatch from inside a transaction block: use the block's own " +
                "TransactionScope.dispatch, which plans against the document this transaction is " +
                "building rather than against the one it has not published yet"
        }

        val planned: CommandResult = CommandPlanner.plan(command, stateFlow.value.document, schema, ids)
        val patch: Patch = when (planned) {
            is CommandResult.Ok -> planned.patch
            is CommandResult.Rejected -> return planned
        }
        val applied: UiDocument = PatchApplier.apply(patch, stateFlow.value.document)

        // Recorded before the publish, so the `canUndo` and `canRedo` the publish copies out
        // of this history are the ones this edit produced.
        history.record(Transaction(label, patch, mergeKey), window)
        publish(applied, patch)
        return planned
    }

    /**
     * Runs [block] as one edit: one history entry, one revision, one announcement.
     *
     * This is what §26.3's third rule is for, and what "Wrap in Card" and a bulk paste ride on
     * — the point being that a user undoes the *gesture*, not the three commands it happened to
     * be built from.
     *
     * Commands dispatched inside the block apply to a local document, so each one is planned
     * against the result of the one before it, and the ops are concatenated in that order.
     *
     * Nothing reaches [state] or [changes] until the block returns normally. A block that throws
     * leaves the controller untouched — there is nothing to undo because nothing was published.
     *
     * @param label what the undo menu calls the whole gesture. §25.1 keeps that copy in the
     *   editor, so this is the editor's string.
     * @throws IllegalStateException if a transaction is already running. Nesting has no coherent
     *   answer about which label the combined entry would carry.
     */
    public fun <R> transaction(label: String, block: TransactionScope.() -> R): R {
        check(!insideTransaction) { "a transaction cannot be nested inside another transaction" }

        insideTransaction = true
        val run = TransactionRun()
        try {
            val outcome: R = block(run)
            val patch = Patch(run.ops)
            history.record(Transaction(label, patch, mergeKey = null), window = null)
            publish(run.document, patch)
            return outcome
        } finally {
            insideTransaction = false
        }
    }

    /**
     * Reverts the newest entry, or reports that there was none.
     *
     * Returns `false` rather than throwing when the stacks are empty, because an editor binding
     * this to a keystroke asks the question on every keystroke and most of them ask it when
     * there is nothing to undo.
     *
     * The entry's inverse is applied and announced like any other edit, so an editor that
     * repaints from [changes] sees the undo and not just the next forward dispatch.
     */
    public fun undo(): Boolean {
        val entry: Transaction = history.undo() ?: return false
        val inverse: Patch = entry.patch.inverse()
        publish(PatchApplier.apply(inverse, stateFlow.value.document), inverse)
        return true
    }

    /**
     * Re-applies the oldest undone entry, or reports that there was none.
     *
     * The mirror of [undo], and it advances [DocumentState.revision] too: the document is
     * different afterwards, which is the whole reason the revision exists.
     */
    public fun redo(): Boolean {
        val entry: Transaction = history.redo() ?: return false
        publish(PatchApplier.apply(entry.patch, stateFlow.value.document), entry.patch)
        return true
    }

    /**
     * Moves [document] into [state] and announces [patch], or does neither.
     *
     * The one place a new [DocumentState] is written, and the only reason [state]'s
     * `canUndo` and `canRedo` are ever right: they are copied out of [history] here, after the
     * caller has recorded into it.
     *
     * **A patch with no ops publishes nothing.** §26.2 calls an empty patch legal — it is what
     * a command with nothing to do produces — but it changed no document, so advancing the
     * revision and waking a renderer to repaint an identical value would be the engine
     * inventing work. [History.record] declines it for the same reason and independently,
     * because it is a public class and the rule belongs with it.
     */
    private fun publish(document: UiDocument, patch: Patch) {
        if (patch.ops.isEmpty()) return

        val current: DocumentState = stateFlow.value
        val revision: Long = current.revision + 1

        stateFlow.value = current.copy(
            document = document,
            revision = revision,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )

        // `tryEmit` with `DROP_OLDEST` and a non-empty buffer never fails, which is what lets
        // the writer stay synchronous: there is no path here that suspends.
        changeFlow.tryEmit(DocumentChange(revision = revision, patch = patch))
    }

    /**
     * The working document and op list of one transaction block.
     *
     * A private inner class rather than a lambda, and deliberately: it has to hold two pieces
     * of state across the block, and a closure over local `var`s would work but would put the
     * transaction's whole contract in a mutable capture.
     */
    private inner class TransactionRun : TransactionScope {

        /** The document as this block has made it. Local to the block; never published early. */
        var document: UiDocument = stateFlow.value.document

        /** Every op every dispatched command produced, in the order they were applied. */
        val ops: MutableList<PatchOp> = mutableListOf()

        override fun dispatch(command: Command): CommandResult {
            val planned: CommandResult = CommandPlanner.plan(command, document, schema, ids)
            val patch: Patch = when (planned) {
                is CommandResult.Ok -> planned.patch
                is CommandResult.Rejected -> return planned
            }

            document = PatchApplier.apply(patch, document)
            ops.addAll(patch.ops)
            return planned
        }
    }

    private companion object {
        /**
         * How many announcements a slow subscriber may fall behind by before losing the oldest.
         *
         * Enough that a renderer drawing at frame rate never loses one, and small enough that
         * the buffer is not a backlog nobody reads. See [changes] for why dropping is the right
         * failure and not a stall.
         */
        private const val CHANGE_BUFFER: Int = 64
    }
}

/**
 * The receiver a [DocumentController.transaction] block runs against.
 *
 * PLAN §25.2 writes `block: TransactionScope.() -> R` and declares no such type; this is it.
 *
 * ### One method, and what the omission means
 *
 * There is no `undo` here and no document. A block that could undo would be reaching past the
 * transaction it is inside, and a block that could read [DocumentController.state] would read
 * a document that has deliberately not been updated yet — both of which turn "these commands
 * are one edit" into a claim about something else. A block dispatches commands and returns a
 * value; the controller decides what they add up to.
 *
 * ### What a refusal inside a block means
 *
 * The same as outside one: [CommandResult.Rejected] comes back, the block carries on if it
 * chooses, and the refused command contributes no ops to the entry. §26.3's second rule is
 * enforced per command, so it holds inside a transaction too — a gesture in which three of four
 * commands were refused is one undo step covering the one that was not.
 *
 * @see DocumentController.transaction for what the block's dispatches add up to.
 */
public interface TransactionScope {

    /**
     * Plans [command] against the document this block has built so far and applies it there.
     *
     * The result is the ordinary [CommandResult]: a refusal is returned and nothing is applied,
     * and the block is told so it can stop rather than continue into a half-applied gesture.
     */
    public fun dispatch(command: Command): CommandResult
}