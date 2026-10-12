package dev.rotalex.lutter.editing.template

import dev.rotalex.lutter.editing.command.ABSENT
import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.BETA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.CHILDREN
import dev.rotalex.lutter.editing.command.COLUMN
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.MINTED_ONE
import dev.rotalex.lutter.editing.command.MINTED_THREE
import dev.rotalex.lutter.editing.command.MINTED_TWO
import dev.rotalex.lutter.editing.command.TEXT
import dev.rotalex.lutter.editing.command.betaNode
import dev.rotalex.lutter.editing.command.deltaNode
import dev.rotalex.lutter.editing.command.echoNode
import dev.rotalex.lutter.editing.command.fixedIds
import dev.rotalex.lutter.editing.command.freshIds
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.command.plannerSchema
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IdRemapper], and the two commands that mint ids through it.
 *
 * ### Why a duplicate is in a test about the clipboard
 *
 * `Command.DuplicateNode` is a paste of one node into itself, and before this unit it carried
 * its own copy of the rewrite that lives here now. The two questions are the same question —
 * *what ids does this copy get, and who notices when they are wrong?* — and the answer is only
 * written down once. §33.6 files `IdRemapper` under `template` because the clipboard needs it
 * more; §6.2's row says *"Duplicate/paste generates new ids"* in one sentence for the same
 * reason, and this class is where that sentence is checked from both ends.
 */
class IdRemapperTest {

    /**
     * A copy's ids are the generator's, in order, and neither they nor the references between
     * them are the original's.
     *
     * Both halves are asserted, and the second is the one that is easy to leave out: renaming
     * the rows without rewriting the child references gives a copy whose children point at the
     * *original's* rows, so a paste would move nothing and the copied subtree would disappear
     * into orphans. The reverse gives two rows with one id, which `NodeTable.with` resolves by
     * keeping the last.
     */
    @Test
    fun `a remap renumbers the rows and the references between them`() {
        val copy: NodeTemplate = IdRemapper(BASE, freshIds()).remap(
            listOf(betaNode(), deltaNode(), echoNode()),
        )

        assertEquals(listOf(MINTED_ONE, MINTED_TWO, MINTED_THREE), copy.nodes.map { it.id })
        assertEquals(listOf(MINTED_TWO, MINTED_THREE), copy.root?.slots?.get(CHILDREN))
        assertEquals(COLUMN, copy.root?.type)
    }

    /**
     * Nothing else about a row changes.
     *
     * `Alpha` is the fixture's fully-loaded node — a label, a property, a modifier chain and
     * a handler — so equality against a copy with one field replaced covers all seven fields
     * at once and fails with a diff that names the field that moved.
     */
    @Test
    fun `every other field of a row survives the remap`() {
        val alpha: Node = BASE.nodes.require(ALPHA)
        val copy: NodeTemplate = IdRemapper(BASE, freshIds()).remap(listOf(alpha))

        assertEquals(alpha.copy(id = MINTED_ONE), copy.root)
    }

    /**
     * Two remaps with one instance are disjoint, which is the property a bulk paste needs.
     *
     * §26.3's `transaction {}` exists so that a gesture is one undo step, and a bulk paste is
     * the gesture that inserts the same subtree twice. A remapper that kept nothing between
     * calls would have nothing to make those two sets disjoint, and the second paste would be
     * a `NodeTable.with` that replaces the first.
     */
    @Test
    fun `two remaps from one instance never collide`() {
        val remapper = IdRemapper(BASE, freshIds())
        val rows: List<Node> = listOf(betaNode(), deltaNode())

        val first: NodeTemplate = remapper.remap(rows)
        val second: NodeTemplate = remapper.remap(rows)

        val firstIds: List<NodeId> = first.nodes.map { it.id }
        val secondIds: List<NodeId> = second.nodes.map { it.id }

        assertEquals(listOf(MINTED_ONE, MINTED_TWO), firstIds)
        assertEquals(listOf(MINTED_THREE, NodeId("n_4")), secondIds)
        assertEquals(emptyList(), firstIds.intersect(secondIds.toSet()).toList())
    }

    /**
     * A generator that breaks its promise gets neither an exception nor a hang — and the
     * refusal lands somewhere it can be rendered.
     *
     * A `SequentialIdGenerator` restarted against a document that already holds `n_1` breaks
     * [dev.rotalex.lutter.model.ids.IdGenerator]'s promise, and the consequence of honouring
     * it is not a wrong answer but a wrong *document*: `NodeTable.with` replaces.
     *
     * **The remapper does not throw and does not retry forever.** It asks again a bounded
     * number of times, and then hands back the id it has, because the caller that can turn a
     * repeated id into a report is `Command.DuplicateNode`'s planner — which refuses with
     * `edit.duplicate_id`, and that refusal is asserted two tests below. A remapper that threw
     * would put an exception between "the user pressed duplicate" and the message explaining
     * why it did not work, which §25.3's `CommandResult` is precisely the alternative to.
     */
    @Test
    fun `a generator that repeats an id neither throws nor hangs`() {
        assertEquals(ALPHA, IdRemapper(BASE, fixedIds(ALPHA)).fresh())

        val copy: NodeTemplate = IdRemapper(BASE, fixedIds(MINTED_ONE)).remap(listOf(betaNode(), deltaNode()))
        assertEquals(listOf(MINTED_ONE, MINTED_ONE), copy.nodes.map { it.id })
    }

    /**
     * A reference the rows do not resolve is left exactly as it is.
     *
     * A captured template closes over itself and a subtree walk returns whole subtrees, so on a
     * well-formed document this branch is not taken at all. It is taken on a document with a
     * dangling child reference, and inventing an id for the missing end would create a node
     * nobody asked for; leaving it points the copy at the same gap the original has, which
     * §17.1's structural pass reports on the original too.
     */
    @Test
    fun `a reference the rows cannot resolve is untouched`() {
        val holed: Node = Node(id = MINTED_ONE, type = COLUMN, slots = mapOf(CHILDREN to listOf(ABSENT)))

        val copy: NodeTemplate = IdRemapper(BASE, freshIds()).remap(listOf(holed))

        assertEquals(listOf(ABSENT), copy.root?.slots?.get(CHILDREN))
    }

    /**
     * `Command.DuplicateNode` goes through this class, so a duplicate and a paste mint the
     * same way.
     *
     * The expected rows are written out rather than recomputed: a duplicate of `Beta` is three
     * rows under `n_1`, `n_2` and `n_3`, immediately after the original in the root's slot —
     * the same shape `CommandPlannerRoundTripTest`'s duplicate row pins, reached through the
     * remapper this unit introduced.
     */
    @Test
    fun `duplicate copies a subtree under ids the document is not using`() {
        val planned: CommandResult = CommandPlanner.plan(
            Command.DuplicateNode(BETA),
            BASE,
            SCHEMA,
            freshIds(),
        )
        val ok = planned as? CommandResult.Ok ?: throw AssertionError("the duplicate was refused: $planned")
        val inserted: PatchOp.InsertNodes = ok.patch.ops.single() as PatchOp.InsertNodes

        assertEquals(
            listOf(
                Node(id = MINTED_ONE, type = COLUMN, slots = mapOf(CHILDREN to listOf(MINTED_TWO, MINTED_THREE))),
                Node(id = MINTED_TWO, type = TEXT),
                Node(id = MINTED_THREE, type = TEXT),
            ),
            inserted.nodes,
        )
    }

    /**
     * A generator that repeats an id is refused, not applied.
     *
     * §25.3's contract is that a planner answers with a value: this is where [IdRemapper]'s
     * "return the id as it stands" behaviour turns into a refusal a user can be shown, and the
     * message is that the paste did not happen rather than that the engine stopped.
     */
    @Test
    fun `a duplicate whose ids collide is refused with a reason the editor can show`() {
        val codes: List<DiagnosticCode> = when (val result = CommandPlanner.plan(
            Command.DuplicateNode(GAMMA),
            BASE,
            SCHEMA,
            fixedIds(ALPHA),
        )) {
            is CommandResult.Ok -> emptyList()
            is CommandResult.Rejected -> result.reasons.map { it.code }
        }

        assertEquals(listOf(EditCodes.DuplicateId), codes)
    }

    /**
     * Duplicating a node whose *grandchild* is dangling plans instead of throwing.
     *
     * `planDuplicateNode` checks the dangling children of the node it was given, and the
     * rewrite used to look every id up in a map built from the rows it had — so a gap one
     * level deeper than the check reached was a `NoSuchElementException` from inside a
     * planner, which is the one thing §25.3 says a planner never does. The remapper leaves a
     * reference it cannot resolve alone, so the plan is produced and the hole travels with the
     * copy; §17.1 is where a dangling reference is reported, on the original as well.
     */
    @Test
    fun `a duplicate of a subtree with a hole deeper down still plans`() {
        val document: UiDocument = plannerDocument(
            rootChildren = listOf(BETA),
            rows = listOf(
                Node(id = BETA, type = COLUMN, slots = mapOf(CHILDREN to listOf(GAMMA))),
                Node(id = GAMMA, type = COLUMN, slots = mapOf(CHILDREN to listOf(ABSENT))),
            ),
        )

        val planned: CommandResult = CommandPlanner.plan(
            Command.DuplicateNode(BETA),
            document,
            SCHEMA,
            freshIds(),
        )

        assertTrue(planned is CommandResult.Ok, "the duplicate was refused: $planned")
    }

    /**
     * `Command.WrapNode` mints its one id through the same class.
     *
     * A wrapper is a row created out of nothing, which is the smallest thing `IdRemapper`
     * does, and it is here so that "what id does a new node get" has one answer in the module
     * rather than two.
     */
    @Test
    fun `a wrap mints its id through the remapper`() {
        val planned: CommandResult = CommandPlanner.plan(
            Command.WrapNode(GAMMA, CARD, CONTENT),
            BASE,
            SCHEMA,
            freshIds(),
        )
        val ok = planned as? CommandResult.Ok ?: throw AssertionError("the wrap was refused: $planned")

        assertEquals(MINTED_ONE, ok.patch.touchedNodes.first { it != GAMMA })
    }
}

private val SCHEMA = plannerSchema()

private val BASE: UiDocument = plannerDocument()