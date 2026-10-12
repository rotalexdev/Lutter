package dev.rotalex.lutter.editing.template

import dev.rotalex.lutter.editing.command.ABSENT
import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.BETA
import dev.rotalex.lutter.editing.command.CHILDREN
import dev.rotalex.lutter.editing.command.COLUMN
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.DELTA
import dev.rotalex.lutter.editing.command.ECHO
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.command.FOXTROT
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.GOLF
import dev.rotalex.lutter.editing.command.JULIET
import dev.rotalex.lutter.editing.command.MINTED_ONE
import dev.rotalex.lutter.editing.command.MINTED_THREE
import dev.rotalex.lutter.editing.command.MINTED_TWO
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.betaNode
import dev.rotalex.lutter.editing.command.deltaNode
import dev.rotalex.lutter.editing.command.echoNode
import dev.rotalex.lutter.editing.command.freshIds
import dev.rotalex.lutter.editing.command.julietNode
import dev.rotalex.lutter.editing.command.kiloNode
import dev.rotalex.lutter.editing.command.limaNode
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.command.plannerSchema
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `NodeTemplate.capture` — §25.4's clipboard row, read and written down.
 *
 * The shape was declared by W2 because `Command.InsertNode` and `Command.AddPage` cannot be
 * written without a type to carry a subtree; this is the half that fills it, and the question
 * it answers is narrow: **which rows come out of a document, in what order, and what does it
 * refuse.**
 *
 * Everything about *inserting* a template — the ids, the legality of the place, whether the
 * paste collides with anything — belongs to [IdRemapper] and
 * [dev.rotalex.lutter.editing.policy.DropTargets] and is tested there. What is left here is
 * the read, and the two refusals below.
 */
class NodeTemplateTest {

    /**
     * A capture is the whole subtree, root first, in the walk's fixed order.
     *
     * Root first is not decoration. `PatchApplier.applyInsert` places `nodes.first()` in the
     * parent's slot and writes the rest into the table, so a capture that handed back the
     * children first would place a `Text` where the `Column` belongs and leave the column
     * unreferenced. The descendants come in the walk's slot-name order, which is what
     * `PatchOp.InsertNodes` means by "root first" and what makes two runs over one document
     * produce the same list.
     */
    @Test
    fun `a capture is the whole subtree, root first`() {
        val captured: NodeTemplate = NodeTemplate.capture(BASE, listOf(BETA))

        assertEquals(listOf(betaNode(), deltaNode(), echoNode()), captured.nodes)
        assertEquals(betaNode(), captured.root)
    }

    /**
     * Several roots in one capture, and an overlapping root counted once.
     *
     * The middle pair is the case: naming `Delta` after `Beta` asks for a subtree that has
     * already been collected. Collecting it again would put `n_d` in the template twice, and
     * `PatchApplier` would write the row twice — the second write replacing the first through
     * `NodeTable.with`, which is §6.2's collision failure one step removed from the capture
     * that is supposed to prevent it. Skipping it also keeps the result a set of *complete*
     * subtrees rather than one subtree with a hole in the middle.
     */
    @Test
    fun `several roots are collected whole and an overlapping root once`() {
        val captured: NodeTemplate = NodeTemplate.capture(BASE, listOf(BETA, JULIET, DELTA))

        assertEquals(
            listOf(betaNode(), deltaNode(), echoNode(), julietNode(), kiloNode(), limaNode()),
            captured.nodes,
        )
    }

    /**
     * A capture of nothing is an empty template, not a refusal.
     *
     * The emptiness question belongs to the planner, which answers it as `edit.empty_subtree`
     * — a value an editor can show. `NodeTemplate` is total by construction and this is where
     * that shows: there is no "a capture must hold something" rule for a caller holding an
     * empty selection.
     */
    @Test
    fun `a capture of nothing is an empty template`() {
        val captured: NodeTemplate = NodeTemplate.capture(BASE, emptyList())

        assertEquals(emptyList(), captured.nodes)
        assertNull(captured.root)
    }

    /**
     * An id the table does not hold is refused, loudly.
     *
     * There is no subtree there, so returning an empty template would report a *caller*
     * mistake as `edit.empty_subtree` — a refusal about an edit, when the truth is that
     * nothing was ever captured and the editor's selection is stale.
     */
    @Test
    fun `a capture refuses an id the document does not hold`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            NodeTemplate.capture(BASE, listOf(BETA, ABSENT))
        }

        assertTrue(ABSENT.value in failure.message.orEmpty(), "the failure does not name the id: $failure")
    }

    /**
     * A subtree that does not close is refused.
     *
     * The row here lists a child the document does not hold, so the rows that come back would
     * carry a reference to a node outside themselves. Inserting that writes a row pointing at
     * nothing, and §17.1's structural pass is what reports it — several layers away from the
     * paste that caused it, attached to a document the user did not break by hand. The
     * planners cannot be the ones to catch it: their id checks are about the document, and a
     * row that is not in the document yet is exactly the one they cannot see.
     */
    @Test
    fun `a capture refuses a subtree that does not close`() {
        val document: UiDocument = plannerDocument(
            rootChildren = listOf(BETA),
            rows = listOf(Node(id = BETA, type = COLUMN, slots = mapOf(CHILDREN to listOf(ABSENT)))),
        )

        val failure = assertFailsWith<IllegalArgumentException> { NodeTemplate.capture(document, listOf(BETA)) }

        assertTrue(ABSENT.value in failure.message.orEmpty(), "the failure does not name the gap: $failure")
    }

    /**
     * The whole clipboard row, end to end: capture, renumber, insert, apply, undo.
     *
     * §25.4 says a clipboard is `capture` plus an `InsertNode` "with `IdRemapper`", and this
     * is those three calls in order. The last two assertions are the ones that matter: the
     * original subtree is still in the document untouched (§6.2's "never reused"), and the
     * inverse restores the document exactly — §26.2's property, reached from the direction
     * that matters, because undo is the applier running `inverse()` over a history and a paste
     * that is not invertible has broken undo for the gesture everybody uses.
     */
    @Test
    fun `capture, renumber, insert and undo`() {
        val captured: NodeTemplate = NodeTemplate.capture(BASE, listOf(BETA))
        val copy: NodeTemplate = IdRemapper(BASE, freshIds()).remap(captured.nodes)

        val planned: CommandResult = CommandPlanner.plan(
            command = Command.InsertNode(ROOT, CHILDREN, 2, copy),
            document = BASE,
            schema = SCHEMA,
            ids = freshIds(),
        )
        val ok = planned as? CommandResult.Ok ?: fail("the paste was refused: $planned")
        val pasted: UiDocument = PatchApplier.apply(ok.patch, BASE)

        // Three new rows, the copy at index 2 of the root's slot, and the copy's own children
        // with it. **The whole slot list, not a prefix of it**: the first version of this
        // assertion named the four children up to and including `Gamma` and called that the
        // list, so it described a root with two fewer children than the document has. The card
        // and the banner are in that slot and the paste does not touch them, which is the
        // point of writing them down.
        assertEquals(BASE.nodes.size + 3, pasted.nodes.size)
        assertEquals(
            listOf(ALPHA, BETA, MINTED_ONE, GAMMA, FOXTROT, GOLF),
            pasted.nodes.require(ROOT).slots[CHILDREN],
            "the copy did not land at index 2 of the root's slot",
        )
        assertEquals(listOf(MINTED_TWO, MINTED_THREE), pasted.nodes.require(MINTED_ONE).slots[CHILDREN])
        assertEquals(
            listOf(DELTA, ECHO),
            pasted.nodes.require(BETA).slots[CHILDREN],
            "the original subtree was disturbed by the paste",
        )

        assertEquals(BASE, PatchApplier.apply(ok.patch.inverse(), pasted), "undo did not restore the document")
    }

    /**
     * A paste into a document that already holds the source ids is refused — until the rows are
     * renumbered against **that** document.
     *
     * This is §6.2's Stable IDs row, and it is the reason [IdRemapper] takes a document rather
     * than a list of the ids to avoid: *"Ids are globally unique in the document (across
     * pages/components)."* The target here holds the very nodes the capture was taken from —
     * a copy pasted back into the document it came from, which is what a clipboard is for —
     * so the unrenumbered paste is refused with `edit.duplicate_id` rather than replacing the
     * original with its own copy.
     *
     * The second half is the half that is easy to get wrong: the remapper has to be built
     * against the document the rows are *going into*. Built against the source, it would be
     * reserving the ids that source already holds and would have nothing to say about the
     * collision that actually matters.
     */
    @Test
    fun `a paste collides with the whole target document, not with the captured rows`() {
        val captured: NodeTemplate = NodeTemplate.capture(BASE, listOf(BETA))
        val target: UiDocument = plannerDocument(
            rootChildren = listOf(BETA),
            rows = listOf(betaNode(), deltaNode(), echoNode()),
        )

        val unrenumbered: CommandResult = CommandPlanner.plan(
            Command.InsertNode(ROOT, CHILDREN, 1, captured),
            target,
            SCHEMA,
            freshIds(),
        )
        assertEquals(
            listOf(EditCodes.DuplicateId),
            codesOf(unrenumbered),
            "a paste that reused the source ids was not refused for it",
        )

        val renumbered: CommandResult = CommandPlanner.plan(
            Command.InsertNode(ROOT, CHILDREN, 1, IdRemapper(target, freshIds()).remap(captured.nodes)),
            target,
            SCHEMA,
            freshIds(),
        )
        assertTrue(renumbered is CommandResult.Ok, "a renumbered paste was refused: $renumbered")
    }

    /** The codes a plan was refused with, or nothing for an acceptance. */
    private fun codesOf(result: CommandResult): List<DiagnosticCode> = when (result) {
        is CommandResult.Ok -> emptyList()
        is CommandResult.Rejected -> result.reasons.map { it.code }
    }
}

private val SCHEMA = plannerSchema()

private val BASE: UiDocument = plannerDocument()