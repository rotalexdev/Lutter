package dev.rotalex.lutter.editing.history

import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.labelOf
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [History] on its own: the merge rules, the limit, and §26.3's rule one at the level that owns
 * it.
 *
 * ### Why the window is a parameter and not a clock
 *
 * Every stamp below is a literal the test wrote. If this class held a clock the assertions would
 * still pass and §28.3's determinism claim about the module would be false — a scripted session
 * that produced one document on a fast machine and another on a slow one is not a test that can
 * catch a defect, it is a coin. §26.2's comment forbids the clock, and this file is what that
 * costs: to exercise a merge, the test has to decide the timestamps, because there is nothing
 * here that would decide them for it.
 *
 * ### The boundary is asserted, not just the two sides
 *
 * A 300 ms window merging edits 100 ms apart and not merging edits 2 s apart is a rule with two
 * cases. The exact edge — 300 merges, 301 does not — is a third, and it is the one an off-by-one
 * in the comparison would pass. It has its own test.
 */
class HistoryTest {

    /**
     * §26.2's merge in the shape the plan states it: one key, inside the window, one entry.
     *
     * The label and the key are asserted as well as the count, because a merge that produced the
     * right number of entries from the wrong two would otherwise look identical. The entry has
     * to be the *first* one — it is the run's name — with both patches inside it.
     */
    @Test
    fun `two edits with one key inside the window are one entry`() {
        val history = History()

        history.record(typing("Typing", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Typing", "Alph", "Alphb"), window(atMillis = 1_100L))

        val entry: Transaction = history.undoEntries.single()
        assertEquals("Typing", entry.label)
        assertEquals(KEY, entry.mergeKey)
        assertEquals(2, entry.patch.ops.size, "the merged patch holds both edits")
    }

    /**
     * The same two edits, a second apart. Two entries, because the editor said they were not
     * one run and the engine has no opinion of its own.
     *
     * The identities of the two entries are asserted as well as the count, so a history that
     * pushed a third entry and dropped one would not pass.
     */
    @Test
    fun `two edits with one key outside the window are two entries`() {
        val history = History()

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 2_000L))

        assertEquals(2, history.undoEntries.size)
        assertEquals(listOf("Alph", "Alphb"), history.undoEntries.map { typedValue(it) })
    }

    /**
     * The comparison includes its own edge, and both sides of that are asserted.
     *
     * An edit exactly [TOLERANCE] after the last one is inside the window; one millisecond later
     * is outside. §26.2 does not say which way the boundary falls, so this test pins the choice
     * rather than leaving it to whichever operator was typed first — and it is a millisecond of
     * slack, which is exactly the size of an error nobody notices by hand.
     */
    @Test
    fun `the window includes its own edge and excludes one more`() {
        val atEdge = History()
        atEdge.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        atEdge.record(typing("Second", "Alph", "Alphb"), window(atMillis = 1_000L + TOLERANCE))

        val pastEdge = History()
        pastEdge.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        pastEdge.record(typing("Second", "Alph", "Alphb"), window(atMillis = 1_000L + TOLERANCE + 1))

        assertEquals(1, atEdge.undoEntries.size, "the edge of the window is inside it")
        assertEquals(2, pastEdge.undoEntries.size, "one millisecond past the edge is outside it")
    }

    /**
     * A stamp that moves backwards does not merge, so a caller whose clock wraps or resets gets
     * separate entries instead of one edit silently joined to another from across the wrap.
     */
    @Test
    fun `a stamp that moves backwards does not merge`() {
        val history = History()

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 900L))

        assertEquals(2, history.undoEntries.size)
    }

    /** Two fields are two runs, whatever the timing says. */
    @Test
    fun `two edits with different keys are two entries`() {
        val history = History()

        history.record(typing("Typing", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Typing", "Alph", "Alphb", key = OTHER_KEY), window(atMillis = 1_010L))

        assertEquals(2, history.undoEntries.size)
    }

    /**
     * A key with no window is not a merge.
     *
     * The caller grouped the edits by field and then could not answer the timing question, and
     * this class refuses to answer it on their behalf: a window of "however long ago that was"
     * is not a window. The run also *closes*, so the third edit cannot join the second.
     */
    @Test
    fun `an edit with no window never joins a run and closes it`() {
        val history = History()

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window = null)
        history.record(typing("Third", "Alph", "Alphb"), window(atMillis = 1_010L))

        assertEquals(3, history.undoEntries.size, "the third edit re-opened a run the second closed")
    }

    /**
     * An undo is a boundary the user drew, so it ends the run.
     *
     * **The shape matters and is easy to get wrong.** Two entries, undo the second, and the one
     * left on the stack is the first — which is the one a surviving run would merge into. With
     * only one entry before the undo there is nothing left to merge into and the rule would look
     * like it worked no matter what this class did.
     */
    @Test
    fun `an undo ends a merge run`() {
        val history = History()

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 5_000L))
        assertNotNull(history.undo())

        history.record(typing("Third", "Alpha", "Alphb"), window(atMillis = 5_010L))

        assertEquals(2, history.undoEntries.size, "an edit merged across an undo")
        assertEquals(listOf("First", "Third"), history.undoEntries.map { it.label })
    }

    /** The same boundary from the other direction, which is the one nobody remembers to test. */
    @Test
    fun `a redo ends a merge run`() {
        val history = History()

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 5_000L))
        assertNotNull(history.undo())
        assertNotNull(history.redo())

        history.record(typing("Third", "Alph", "Alphc"), window(atMillis = 5_010L))

        assertEquals(3, history.undoEntries.size, "an edit merged across a redo")
        assertEquals(listOf("First", "Second", "Third"), history.undoEntries.map { it.label })
    }

    /**
     * §26.3's first rule, at the class that owns it.
     *
     * The stack itself is asserted empty rather than only the boolean: [canRedo] could be a
     * flag somebody forgot to compute, and [redoEntries] is what an editor's redo menu would read.
     */
    @Test
    fun `the next recorded edit clears the redo stack`() {
        val history = History()
        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 5_000L))

        assertNotNull(history.undo())
        assertTrue(history.canRedo)

        history.record(typing("Third", "Alpha", "Alph"), window(atMillis = 9_000L))

        assertFalse(history.canRedo)
        assertEquals(emptyList<Transaction>(), history.redoEntries)
        assertNull(history.redo())
    }

    /**
     * An entry with no ops is not an entry, and it changes nothing.
     *
     * §26.2 says an empty patch is legal — it is what a command with nothing to do produces — so
     * this is the interesting boundary rather than a degenerate one. Both halves are asserted:
     * the stack is the size it was, and the redo stack survived, because nothing happened.
     */
    @Test
    fun `an edit with no ops is neither stored nor a reason to forget a redo`() {
        val history = History()
        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 5_000L))
        assertNotNull(history.undo())

        history.record(Transaction("Nothing", Patch(emptyList()), mergeKey = null), window = null)

        assertEquals(listOf("First"), history.undoEntries.map { it.label }, "the empty edit was stored")
        assertTrue(history.canRedo, "an edit that did nothing cleared a redo stack")
        assertEquals(listOf("Second"), history.redoEntries.map { it.label })
    }

    /**
     * The merged patch is still a patch, and §26.2's property still holds for it.
     *
     * This is the assertion the whole merge mechanism rests on and the one nothing above
     * reaches: the ops were planned one after another against the document each left behind, so
     * undoing the single merged entry has to restore the document the run started from — not the
     * state halfway through the first keystroke.
     */
    @Test
    fun `a merged entry applies and inverts as one`() {
        val document: UiDocument = plannerDocument()
        val history = History()

        history.record(typing("Typing", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Typing", "Alph", "Alphb"), window(atMillis = 1_010L))

        val entry: Transaction = history.undoEntries.single()
        val typed: UiDocument = PatchApplier.apply(entry.patch, document)

        assertEquals("Alphb", labelOf(typed, ALPHA))
        assertEquals(document, PatchApplier.apply(entry.patch.inverse(), typed))
    }

    /** [History]'s limit keeps the newest entries, because those are the ones somebody wants back. */
    @Test
    fun `the oldest entries are dropped at the limit`() {
        val history = History(limit = 2)

        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 2_000L))
        history.record(typing("Third", "Alph", "Alphc"), window(atMillis = 3_000L))

        assertEquals(listOf("Second", "Third"), history.undoEntries.map { it.label })
    }

    /**
     * The stacks a caller reads are copies.
     *
     * Without this a caller could take [History.undoEntries], hold it, and watch it grow
     * underneath itself — or hold a reference to a list the stack is still writing to.
     */
    @Test
    fun `the entry list a caller read does not change underneath it`() {
        val history = History()
        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))

        val read: List<Transaction> = history.undoEntries
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 2_000L))

        assertEquals(1, read.size, "a snapshot changed after it was taken")
        assertEquals(2, history.undoEntries.size)
    }

    /** Both refusals are at construction, where a caller can see them, rather than at use. */
    @Test
    fun `a history that can hold nothing and a window nothing fits are both refused`() {
        assertFailsWith<IllegalArgumentException> { History(limit = 0) }
        assertFailsWith<IllegalArgumentException> { History(limit = -1) }
        assertFailsWith<IllegalArgumentException> { MergeWindow(atMillis = 0L, toleranceMillis = -1L) }
    }

    /** Clearing is what an editor does when a different document is opened into it. */
    @Test
    fun `clearing forgets both stacks and any open run`() {
        val history = History()
        history.record(typing("First", "Alpha", "Alph"), window(atMillis = 1_000L))
        history.record(typing("Second", "Alph", "Alphb"), window(atMillis = 1_010L))
        assertNotNull(history.undo())

        history.clear()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(emptyList<Transaction>(), history.undoEntries)

        // The run is closed too, so the next edit does not merge into an entry that is gone.
        history.record(typing("Third", "Alph", "Alphc"), window(atMillis = 1_020L))
        assertEquals(listOf("Third"), history.undoEntries.map { it.label })
    }

    /**
     * One rename op, written as a patch.
     *
     * Every merge test needs a patch with no document in hand, and a node rename is the smallest
     * op that carries both sides — an `old` and a `new` — so a merged entry built from these
     * inverts without a fixture and without a row that has to exist anywhere.
     */
    private fun typing(
        label: String,
        old: String,
        new: String,
        key: String = KEY,
    ): Transaction = Transaction(
        label = label,
        patch = Patch(listOf(PatchOp.SetNodeName(ALPHA, old = old, new = new))),
        mergeKey = key,
    )

    /** The value a typing transaction's single op writes, which is the node's new label. */
    private fun typedValue(entry: Transaction): String? =
        (entry.patch.ops.single() as PatchOp.SetNodeName).new

    /** A window carrying this class's tolerance, so a test only has to name the instant. */
    private fun window(atMillis: Long): MergeWindow = MergeWindow(atMillis = atMillis, toleranceMillis = TOLERANCE)

    private companion object {
        /** What the caller is typing into. The engine is told and never works it out. */
        const val KEY: String = "n_a:text"

        /** A second field, for the test that needs two runs rather than one. */
        const val OTHER_KEY: String = "n_b:text"

        /** Long enough that ordinary typing merges, short enough to be worth testing. */
        const val TOLERANCE: Long = 300L
    }
}