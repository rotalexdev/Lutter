package dev.rotalex.lutter.editing.history

import dev.rotalex.lutter.editing.patch.Patch

/**
 * The undo and redo stacks, and the one rule about typing that has to live somewhere.
 *
 * PLAN §26.2 declares this class with a `limit` and one comment. Both are implemented here and
 * the whole design is that comment: *"typing in a text field merges by mergeKey within a time
 * window supplied by the editor (no clock inside the engine)"*.
 *
 * ### Two stacks and nothing else
 *
 * Not a list of commands, and not a list of documents. §26.1 rejected a full snapshot per step
 * in favour of *"Command -> forward/inverse Patch"*, and this is where that choice is visible:
 * [undo] pops an entry and returns its patch, and the caller applies `patch.inverse()` to the
 * document it holds. [History] never sees a document and never applies anything, which is what
 * makes it a stack rather than a second writer — `PatchApplier`'s KDoc says there must be exactly
 * one, and this class is not it.
 *
 * ### What [limit] bounds, and what happens at it
 *
 * How many undoable entries are kept. When the next [record] would exceed it, the **oldest**
 * entries are dropped: the newest is the one a person is most likely to want back. That is the
 * only honest option — refusing the edit would mean a long session stops being editable, and
 * dropping the newest would make undo useless exactly when it is needed most.
 *
 * A `Transaction` carries both sides of its edit, so an entry is not one line of history but a
 * small pair of them; 200 is a generous default and §26.2's.
 *
 * ### Merging, and the three things that end a run
 *
 * [record] merges a new entry into the previous one when all three hold: both carry the same
 * [Transaction.mergeKey], the caller supplied a [MergeWindow], and the two stamps are within
 * that window. The merged entry is the old one with the new ops appended — **append, never
 * replace** — because the patches were planned one after another against the document each left
 * behind, and §26.2's property is stated over a whole patch whose ops can see each other's
 * effects. Concatenating them in that order is what makes one entry undo a run of edits at
 * once.
 *
 * A merge run ends on anything that is not a continuation, and there are exactly four ways:
 *
 *  1. **A different key.** Typing in one field and then another is two runs.
 *  2. **No window.** A caller that supplies no [MergeWindow] is saying it cannot answer the
 *     time question, and an entry with no answer must not join a run.
 *  3. **[undo] or [redo].** An undo is a boundary the user drew. Merging across it would put an
 *     edit and the edit that was undone between it and the next one into a single step, so one
 *     press of undo would skip over work the user had already reversed.
 *  4. **[clear].** Which is what clearing means.
 *
 * ### Why the open run is private state rather than a field on the entry
 *
 * §26.2 fixes an entry as `Transaction(label, patch, mergeKey?)`, and adding a stamp to it would
 * mean every undo entry carries a number only the merge logic reads. The stamp of the *last*
 * edit that opened a run is bookkeeping, so it is bookkeeping here — and it is deliberately not
 * recoverable from the stacks, because a run is only open while its key, the top entry and the
 * stamp all agree, and the agreement is the thing that has to be maintained rather than
 * re-derived.
 *
 * ### Not thread-safe, and not trying to be
 *
 * Same contract as the controller that owns one of these: single-writer, callers serialize. See
 * `DocumentController` for why the rule is a contract rather than a lock.
 *
 * @see Transaction for what an entry holds.
 * @see MergeWindow for the half of the merge question only the editor can answer.
 */
public class History(private val limit: Int = 200) {

    init {
        require(limit > 0) {
            "History was given a limit of $limit; a history that keeps nothing is not a history, " +
                "and one that keeps a negative number cannot be built at all"
        }
    }

    /** Oldest first. The entry [undo] would return is the last one. */
    private val undoable: MutableList<Transaction> = mutableListOf()

    /** Oldest first. The entry [redo] would return is the last one. */
    private val redoable: MutableList<Transaction> = mutableListOf()

    /** The key and stamp of the run currently open, or `null` when no run is open. */
    private var openRun: OpenRun? = null

    /** Whether [undo] has an entry to return. */
    public val canUndo: Boolean
        get() = undoable.isNotEmpty()

    /** Whether [redo] has an entry to return. */
    public val canRedo: Boolean
        get() = redoable.isNotEmpty()

    /**
     * The undo stack, oldest first, as it stands.
     *
     * A copy per read rather than the live list, so a caller cannot reach in and rewrite the
     * stack around the merge rules. Reading it is how an editor's undo menu gets its labels.
     */
    public val undoEntries: List<Transaction>
        get() = undoable.toList()

    /** The redo stack, oldest first, as it stands. */
    public val redoEntries: List<Transaction>
        get() = redoable.toList()

    /**
     * Records one completed edit.
     *
     * ### A new command clears the redo stack, and an empty edit does neither
     *
     * §26.3's first rule is this method's first act on anything it stores: once an edit has
     * happened, the edits that were undone and could have been redone no longer describe a
     * future this document can reach. Redoing them onto a document that has moved on would
     * produce a document nobody edited.
     *
     * The exception is the second rule, and it is checked before that one. **An entry with no ops
     * is not an entry.** It would add an undo step that undoes nothing, and it would clear a
     * redo stack over an edit that never happened. §26.2 says an empty patch is legal — it is
     * the outcome of a command that had nothing to do — and this is where that outcome stops
     * being an undo step.
     *
     * ### What [window] does and does not decide
     *
     * [window] carries the stamps; [Transaction.mergeKey] carries the key. This method never
     * reads a clock, and there is nothing on this class that could: §26.2 says the window is
     * supplied by the editor and the engine has none.
     *
     * A caller recording a multi-command edit should pass the composed patch once rather than
     * calling this per command; the controller's `transaction` does exactly that.
     */
    public fun record(transaction: Transaction, window: MergeWindow?) {
        if (transaction.patch.ops.isEmpty()) return

        redoable.clear()

        val key: String? = transaction.mergeKey
        val previous: OpenRun? = openRun
        val top: Transaction? = undoable.lastOrNull()

        val continues = key != null && window != null && previous != null && previous.key == key &&
            window.atMillis >= previous.atMillis &&
            window.atMillis - previous.atMillis <= window.toleranceMillis

        if (top != null && continues) {
            // Appended, never replaced: the two patches were planned one after the other
            // against the document each left behind, and §26.2's property is stated over a
            // whole patch whose ops can see each other's effects.
            undoable[undoable.size - 1] = top.copy(patch = Patch(top.patch.ops + transaction.patch.ops))
        } else {
            undoable.add(transaction)
            dropOldest()
        }

        openRun = if (key != null && window != null) OpenRun(key, window.atMillis) else null
    }

    /**
     * The newest undoable entry, moved to the redo stack, or `null` when there is none.
     *
     * Returns the **forward** patch: the caller undoes it with `patch.inverse()`. This class
     * has no document, so it cannot undo anything itself, and a method that appeared to would
     * be the second writer `PatchApplier` forbids.
     *
     * Ends any open merge run — the third of the four rules on the class KDoc.
     */
    public fun undo(): Transaction? {
        val entry: Transaction = undoable.removeLastOrNull() ?: return null
        redoable.add(entry)
        openRun = null
        return entry
    }

    /**
     * The oldest redoable entry, moved back to the undo stack, or `null` when there is none.
     *
     * Ends any open merge run, for the same reason [undo] does.
     */
    public fun redo(): Transaction? {
        val entry: Transaction = redoable.removeLastOrNull() ?: return null
        undoable.add(entry)
        openRun = null
        return entry
    }

    /**
     * Forgets everything: both stacks and any open run.
     *
     * What an editor calls when a different document is opened into the same controller. The
     * history of the previous document describes edits to a document that is no longer there,
     * and §26.3 puts history in the session rather than the document — so a session change is
     * where it goes.
     */
    public fun clear() {
        undoable.clear()
        redoable.clear()
        openRun = null
    }

    /** Discards the oldest entries until the stack is back within [limit]. */
    private fun dropOldest() {
        val excess: Int = undoable.size - limit
        if (excess > 0) undoable.subList(0, excess).clear()
    }

    /** The key and stamp of the last edit that opened a merge run. */
    private class OpenRun(val key: String, val atMillis: Long)
}