package dev.rotalex.lutter.editing.history

import dev.rotalex.lutter.editing.patch.Patch

/**
 * One undo step: what an edit was called, the patch that performs it, and the key it merges
 * under while the user is still typing.
 *
 * PLAN §26.2 writes the entry as `Transaction(label, patch, mergeKey?)` and this is that, with
 * the three fields it names and none of the ones it does not.
 *
 * ### The patch is the step
 *
 * Not the command and not a snapshot. §26.1 chose *"Command -> forward/inverse Patch"* over a
 * snapshot per step, and this is where that choice is paid out: [undo] replays
 * [Patch.inverse] and [redo] replays [patch], with no lookup, no index and no document. The
 * cost is that the record carries both sides of the edit, which §26.1 accepted in exchange for
 * an undo that is exact twenty edits later.
 *
 * ### [label] is the caller's word, and the engine invents none
 *
 * §25.1 makes undo-menu copy an editor concern: the engine owns the document and the mechanism,
 * and selection, viewport, panels and drag state are the editor's. A label is the string an
 * editor puts in a menu next to this entry, so it is the editor's string. A caller that has no
 * name for an edit passes the empty one.
 *
 * **Keeping the first label when two entries merge** follows from that: every keystroke of one
 * typing run arrives with the same label from the same call site, so the question does not
 * arise in practice, and when it does the first is the run's name rather than the last key's.
 *
 * ### [mergeKey] is what the run is keyed by, and it is `null` for everything else
 *
 * `null` means *this entry never merges with anything*, which is the ordinary case: an insert, a
 * move, a transaction. A key means *consecutive entries carrying the same key may collapse into
 * one*, and only if the caller also says the two were close together in time — see
 * [MergeWindow] for why that question cannot be answered here.
 *
 * ### Not persisted
 *
 * No `@Serializable`, deliberately, and this is §26.3's fourth rule rather than an omission:
 * history is an editor session's state and is not written into the document. A `Transaction` is
 * a record of what *this* session did, so giving it a wire format would offer a place for it to
 * be persisted, and §26.3 has already answered that it must not be.
 *
 * @see History for the stacks this record lives in.
 * @see MergeWindow for the half of "is it the same typing run" that only the caller can answer.
 */
public data class Transaction(

    /** What this edit is called in the editor's undo menu. Empty when the caller has no name. */
    public val label: String,

    /** The patch that performs the edit. [Patch.inverse] of it undoes it, with no document. */
    public val patch: Patch,

    /**
     * The key consecutive edits of one typing run share, or `null` when this edit never
     * merges.
     *
     * The key names *what* is being typed into — a node id and a property key spelled by the
     * caller — because only the caller knows which field the caret is in. This class never
     * derives one.
     */
    public val mergeKey: String?,
)

/**
 * How close together two edits of one typing run were — answered by the caller, because the
 * engine has no clock.
 *
 * PLAN §26.2's comment is the whole of the requirement: *"typing in a text field merges by
 * mergeKey within a time window supplied by the editor (no clock inside the engine)"*. Two
 * numbers arrive from outside and this module subtracts them. **It never asks what time it is,
 * and there is no function here that would let it.** That is not fastidiousness: `commonMain` has
 * no clock that means the same thing on every target, and a wall-clock read inside the engine
 * would make §28.3's determinism claim — same script, same document — false on any two runs
 * that straddle a keystroke pause.
 *
 * ### Why the caller supplies two numbers rather than one
 *
 * [atMillis] is a stamp on *this* edit and [toleranceMillis] is how stale a stamp may be and
 * still count as the same run. The editor has both: the platform's monotonic clock for the
 * first, and a product decision for the second — a 300 ms gap is one word and a two second gap
 * is a different thought. The engine cannot have either: the clock is a platform API and the
 * tolerance is a fact about how a person types.
 *
 * ### The comparison, and the direction
 *
 * Two entries merge when the newer stamp is **not before** the older one and is **at most
 * `toleranceMillis` after** it. A stamp that moves backwards does not merge, so a caller that
 * wraps around or resets its counter gets separate entries rather than a silent merge across
 * the wrap.
 *
 * ### What is deliberately absent
 *
 * The merge **key is not here.** It is on [Transaction.mergeKey], because §26.2 puts it on the
 * entry and one place that answers "what is this edit" beats a second one that answers the same
 * question. This record answers only the question the entry cannot: *when*.
 *
 * @see Transaction for the key this window is measured against.
 * @see History.record for the comparison itself.
 */
public data class MergeWindow(

    /**
     * The editor's own stamp for this edit, in milliseconds, from a monotonic clock.
     *
     * Any origin works as long as it does not run backwards; the engine only subtracts one
     * stamp from another and never interprets the value. `0` is a legal stamp and means "no
     * time has passed", which merges with a previous entry only if that entry's window also
     * reaches back to it.
     */
    public val atMillis: Long,

    /**
     * How many milliseconds may separate two edits of one run and still merge them.
     *
     * `0` merges edits stamped at exactly the same instant and nothing else, which is the
     * right answer for a caller batching a paste or an undo manager replaying a log. Negative
     * is refused at construction, because a negative tolerance is a window that can never
     * contain anything and silently turning it into "no merge" would hide the mistake in the
     * caller that wrote it.
     */
    public val toleranceMillis: Long,
) {
    init {
        require(toleranceMillis >= 0) {
            "MergeWindow carries a tolerance of $toleranceMillis ms, which no two edits could " +
                "satisfy; use 0 for edits that merge only when they share an instant"
        }
    }
}