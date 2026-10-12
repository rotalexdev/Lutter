package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT_KEY
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.history.MergeWindow
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PLAN §32's Phase 9 acceptance: *"scripted editing sessions run headless and yield
 * deterministic documents and analyses."*
 *
 * ### What "deterministic" is asserted against
 *
 * Two runs of the same script, on two controllers built from the same fixture with their own
 * counting id generators, have to agree on **the document, the revision, the node table read out
 * row by row, and the number of undo steps**. The last two are why this is not a single
 * `assertEquals` on a document: an implementation that reached the same tree while merging edits
 * differently, or that recorded six entries where another recorded five, would pass a document
 * comparison and is exactly the defect §28.3 is about.
 *
 * ### "And analyses", without an analyzer
 *
 * `:engine:analysis` is out of this module's reach — `ModuleGraphRules` allows `:engine:editing`
 * the model and the schema and nothing else — so nothing here runs an analyzer and this file
 * does not pretend otherwise. What it asserts instead is that **the document a session leaves
 * behind satisfies §6.2's invariants**, checked locally: every id a slot names exists, every
 * node has at most one parent, every node is reachable from a root, and no node is its own
 * ancestor. Those are the facts §17.1's structural pass reads, so a session that broke one would
 * produce a document that pass reports. Whether a given structural fact yields a *diagnostic* is
 * the analyzer's business; that the fact holds is this module's.
 *
 * ### The script is deliberately mixed
 *
 * A named rename, a run of typing that merges, a pause that ends the run, a bulk paste, a
 * "Wrap in Card" gesture, a command the schema refuses, and an undo followed by its redo. Every
 * §26.3 rule is load-bearing somewhere in it, and a session that only ever renamed things would
 * prove determinism about a fraction of what a controller does.
 */
class ScriptedEditingSessionTest {

    /**
     * The same script twice. §28.3's claim, about a whole session rather than about one patch.
     *
     * The four comparisons are not redundant. A document comparison alone would pass an
     * implementation that reached the same tree by recording history differently, and a revision
     * comparison alone would pass one that counted correctly while merging wrongly.
     */
    @Test
    fun `a scripted session is the same session every time it runs`() {
        val first = runScript()
        val second = runScript()

        assertEquals(first.revision, second.revision, "the two runs applied different numbers of patches")
        assertSameDocument(first.document, second.document, "the two runs left different documents")
        assertEquals(first.tree, second.tree, "the two runs left different node tables")
        assertEquals(first.undoSteps, second.undoSteps, "the two runs produced different undo stacks")
    }

    /**
     * Undoing the whole session is the acceptance criterion in its most literal form: the
     * document the controller was built with comes back.
     *
     * The count is asserted as well as the document, because "undo everything" and "undo
     * everything except the last thing" both end with an `undo()` that returns false.
     */
    @Test
    fun `undoing the whole session restores the document it started from`() {
        val controller = controller()
        script(controller)

        var steps: Int = 0
        while (controller.undo()) steps++

        assertEquals(5, steps, "five gestures, five undo steps")
        assertSameDocument(plannerDocument(), controller.state.value.document, "the whole session, undone")
        assertEquals(emptyList(), structuralFindings(controller.state.value.document))
    }

    /**
     * And the other half of the round trip.
     *
     * An undo stack that cannot be replayed forward is one that lost work, and it is the failure
     * mode `PatchInverter`'s KDoc warns about: per-op inverses that hold while the stack is walked
     * one way and stop holding when it is walked back over several entries.
     */
    @Test
    fun `redoing the whole session reproduces it exactly`() {
        val controller = controller()
        script(controller)
        val after: UiDocument = controller.state.value.document

        var undone: Int = 0
        while (controller.undo()) undone++

        var redone: Int = 0
        while (controller.redo()) redone++

        assertEquals(5, undone)
        assertEquals(undone, redone, "some entries could be undone but not redone")
        assertSameDocument(after, controller.state.value.document, "every entry redone")
    }

    /**
     * What a §17.1 structural pass would find in the document a session leaves behind: nothing.
     *
     * The pristine document goes through the same function, so a finding means the session broke
     * something rather than that the fixture was never sound to begin with.
     */
    @Test
    fun `the document a session leaves behind is structurally sound`() {
        val controller = controller()
        script(controller)

        assertEquals(emptyList(), structuralFindings(controller.state.value.document))
        assertEquals(emptyList(), structuralFindings(plannerDocument()))
    }

    /** One session's reproducible results. See [runScript]. */
    private class Session(
        val document: UiDocument,
        val revision: Long,
        val tree: String,
        val undoSteps: Int,
    )

    /**
     * Runs [script] on a fresh controller and reports everything about the outcome that has to
     * be reproducible.
     *
     * `undoSteps` is measured by draining the stack rather than written down, so it counts the
     * entries the session actually recorded — which is the difference between asserting the
     * design and asserting that the design happened.
     */
    private fun runScript(): Session {
        val controller = controller()
        script(controller)

        val document: UiDocument = controller.state.value.document
        val revision: Long = controller.state.value.revision

        var steps: Int = 0
        while (controller.undo()) steps++

        return Session(document = document, revision = revision, tree = treeOf(document), undoSteps = steps)
    }

    /**
     * The session, end to end.
     *
     * Every step here is something §26.3 or §25.2 has a rule about, and none of them is here for
     * decoration. In order: a named rename, a run of typing that merges into one entry, a pause
     * that ends that run, a bulk paste as one gesture, a wrap as one gesture, a command the
     * schema refuses, and an undo followed by its redo.
     *
     * The paste runs before the wrap on purpose. Wrapping moves the node into a `Card`, whose
     * `content` slot takes at most one child, so a duplicate after the wrap would be refused and
     * the paste would silently become two gestures — the kind of arrangement that makes a test
     * pass for the wrong reason.
     */
    private fun script(controller: DocumentController<String, String, String>) {
        controller.dispatch(Command.RenameNode(ALPHA, "Header"), label = "Rename")

        // Three keystrokes inside one window: one undo step, three applied patches.
        controller.dispatch(Command.SetProperty(ALPHA, TEXT_KEY, typed("H")), label = "Type", mergeKey = KEY, window = window(0L))
        controller.dispatch(Command.SetProperty(ALPHA, TEXT_KEY, typed("He")), label = "Type", mergeKey = KEY, window = window(80L))
        controller.dispatch(Command.SetProperty(ALPHA, TEXT_KEY, typed("Hello")), label = "Type", mergeKey = KEY, window = window(160L))

        // A pause longer than the window: a second undo step in the same field.
        controller.dispatch(Command.SetProperty(ALPHA, TEXT_KEY, typed("Hello world")), label = "Type", mergeKey = KEY, window = window(4_000L))

        controller.transaction("Paste three") {
            dispatch(Command.DuplicateNode(GAMMA))
            dispatch(Command.DuplicateNode(GAMMA))
            dispatch(Command.DuplicateNode(GAMMA))
        }

        controller.transaction("Wrap in Card") {
            dispatch(Command.WrapNode(GAMMA, CARD, CONTENT))
            dispatch(Command.RenameNode(GAMMA, "Card label"))
        }

        // Refused with `EditCodes.NoParent`, and asserted by the undo count staying at five.
        controller.dispatch(Command.RemoveNode(ROOT))

        controller.undo()
        controller.redo()
    }

    /**
     * The document's node table as `id(type) slot:[children]` lines, one per row, ascending by
     * id.
     *
     * The same helper `CommandPlannerRoundTripTest` uses, copied for the reason that file gives:
     * it is `private` there, and widening a merged work unit's test visibility is a larger change
     * than duplicating twelve lines. `NodeTable.toString()` is the size and nothing else, so a
     * difference inside the table would otherwise be reported as two documents that print
     * identically.
     */
    private fun treeOf(document: UiDocument): String = buildString {
        for (id in document.nodes.ids()) {
            val node: Node = document.nodes.require(id)
            val slots: String = node.slots.entries
                .sortedBy { it.key.value }
                .joinToString(" ") { (slot, children) ->
                    "$slot:" + children.joinToString(",", "[", "]") { child -> child.value }
                }
            append(id.value).append('(').append(node.type.value).append(')').append(' ').append(slots).append('\n')
        }
    }

    /**
     * Everything §6.2 requires of a document, as a list of sentences.
     *
     * Returns an empty list rather than asserting, so one call reports every problem instead of
     * only the first. The rules, each naming the pair it failed on:
     *
     *  * **Existence** — an id a slot names is in the table.
     *  * **Uniqueness** — an id is claimed by at most one slot in the whole document.
     *  * **Reachability** — every row is reachable from a root, and a root is a row no slot
     *    claims, so a row nothing points at and no page names is an orphan.
     *  * **Acyclicity** — no node is its own ancestor. Checked while walking, so a cycle is
     *    reported as the cycle it is rather than as every node in it being unreachable.
     */
    private fun structuralFindings(document: UiDocument): List<String> {
        val table: NodeTable = document.nodes
        val findings = mutableListOf<String>()
        val claimedBy = mutableMapOf<NodeId, String>()

        for (id in table.ids()) {
            val node: Node = table.require(id)
            for ((slot, children) in node.slots) {
                for ((index, child) in children.withIndex()) {
                    val address = "$node.$slot[$index]"
                    if (child !in table) {
                        findings += "$address names '$child', which the table does not hold"
                        continue
                    }
                    val previous: String? = claimedBy.put(child, address)
                    if (previous != null) findings += "'$child' is claimed by $previous and by $address"
                }
            }
        }

        for (page in document.pages.values) {
            if (page.root !in table) findings += "page '${page.id}' names root '${page.root}', which is gone"
        }

        val reached = mutableSetOf<NodeId>()
        for (root in table.ids().filter { it !in claimedBy }) {
            walk(root, emptySet(), reached, findings, table)
        }
        for (id in table.ids()) {
            if (id !in reached) findings += "'$id' is unreachable from any root"
        }

        return findings
    }

    /** Depth-first from [from], reporting a node met twice on one path as a cycle. */
    private fun walk(
        from: NodeId,
        path: Set<NodeId>,
        reached: MutableSet<NodeId>,
        findings: MutableList<String>,
        table: NodeTable,
    ) {
        if (from in path) {
            findings += "'$from' is its own ancestor"
            return
        }
        if (from !in table || !reached.add(from)) return

        for (child in table.require(from).slots.values.flatten()) {
            walk(child, path + from, reached, findings, table)
        }
    }

    /** A string-valued property, built the way the shared fixture builds its own. */
    private fun typed(value: String): PropertyValue = PropertyValue.Const(Value.Str(value))

    /** The window the editor supplies, in the editor's own milliseconds. */
    private fun window(atMillis: Long): MergeWindow = MergeWindow(atMillis = atMillis, toleranceMillis = TOLERANCE)

    private companion object {
        /** What the caller is typing into. Only the caller knows; the engine is told. */
        const val KEY: String = "n_a:text"

        /** Long enough that ordinary typing merges, short enough that a pause does not. */
        const val TOLERANCE: Long = 300L
    }
}