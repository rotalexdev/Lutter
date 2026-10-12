package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Placement
import dev.rotalex.lutter.editing.patch.Record
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.IdGenerator
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The other half of the pair PLAN §32's Phase 9 asks for: each planner's output, applied.
 *
 * ### Three assertions per row, in this order
 *
 *  1. **The ops are the ones the planner claims to produce.** Written out by hand, because a
 *     row whose expected ops were the patch's own ops would assert that the planner agrees
 *     with itself — and the ordering inside a wrap or an unwrap is the whole subject there.
 *  2. **Applying the patch produces the document the command describes.** The `after` is
 *     written out for the same reason, and the tree is printed before the document so that a
 *     difference inside `UiDocument.nodes` reaches the message as something readable.
 *  3. **The inverse restores the document it started from.** §26.2's property, reached from
 *     the direction that matters: a planner may only produce patches that obey it, because
 *     undo is the applier running `inverse()` over a history, and a non-invertible patch has
 *     quietly broken undo for one command while every forward assertion still passes.
 *
 * ### The discipline is `PatchRoundTripTest`'s, not a second one
 *
 * Same table, same `treeOf`, same "write the expected state by hand" rule. `treeOf` is copied
 * rather than shared because it is `private` in that file, and widening a merged work unit's
 * test visibility is a larger change than duplicating twelve lines.
 *
 * ### The row that needed a new op to exist
 *
 * [Command.AddPage] is the last row and the reason D30 exists. Before it, the planner refused
 * the command as unrepresentable, because §26.2's eight ops place rows only through an op
 * that addresses a parent *node* and a page root has none. The row pins the two-op shape D30
 * makes possible, and — because the inverse is what makes undo work — the second assertion
 * below is the one that would catch the ops being emitted in the wrong order.
 */
class CommandPlannerRoundTripTest {

    /** The whole table, forward and back. */
    @Test
    fun `every planned command applies and its inverse restores`() {
        for (case in CASES) {
            val result: CommandResult = CommandPlanner.plan(case.command, case.before, PLAN_SCHEMA, case.ids())

            val patch: Patch = when (result) {
                is CommandResult.Ok -> result.patch
                is CommandResult.Rejected -> fail("${case.name}: expected a patch, got ${result.reasons}")
            }

            assertEquals(case.ops, patch.ops, "${case.name}: the ops are not the ones named")

            val applied: UiDocument = PatchApplier.apply(patch, case.before)
            val restored: UiDocument = PatchApplier.apply(patch.inverse(), applied)

            assertEquals(treeOf(case.after), treeOf(applied), "${case.name}: node tree after applying")
            assertEquals(case.after, applied, "${case.name}: whole document after applying")
            assertEquals(treeOf(case.before), treeOf(restored), "${case.name}: node tree after undoing")
            assertEquals(case.before, restored, "${case.name}: whole document after undoing")
        }
    }

    /**
     * The table names every command it claims to, and now it is all twelve of them.
     *
     * `CommandLegalityTest` reads this set to assert that the two tables together cover §25.3's
     * closed set, so a command planned by neither would have no test at all while the `when`
     * over the sealed interface still compiled.
     */
    @Test
    fun `the table names every command it claims to`() {
        assertEquals(CASES.map { it.command::class }.toSet(), PLANNED_COMMANDS)
        assertEquals(12, PLANNED_COMMANDS.size, "the table plans ${PLANNED_COMMANDS.size} commands")
    }

    /**
     * Planning is a pure function of its four arguments.
     *
     * Worth its own test because two planners read an `IdGenerator`, and a generator is the one
     * mutable thing in the signature: a planner that consumed one and then wrote a different id
     * into the patch than into a check would still round-trip against its own document and
     * still be wrong for a caller planning two commands with one generator.
     */
    @Test
    fun `planning the same command twice produces the same patch`() {
        for (case in CASES) {
            val first: CommandResult = CommandPlanner.plan(case.command, case.before, PLAN_SCHEMA, case.ids())
            val second: CommandResult = CommandPlanner.plan(case.command, case.before, PLAN_SCHEMA, case.ids())

            assertEquals(first, second, "${case.name}: two plans of the same command disagreed")
        }
    }

    companion object {
        /** The commands this table plans. Read by `CommandLegalityTest`'s coverage assertion. */
        internal val PLANNED_COMMANDS: Set<KClass<out Command>> = CASES.map { it.command::class }.toSet()
    }
}

/** One row: the command, the document it is planned against, the ops, and what they produce. */
private class PlanCase(
    val name: String,
    val before: UiDocument,
    val command: Command,
    val ids: () -> IdGenerator,
    val ops: List<PatchOp>,
    val after: UiDocument,
)

/**
 * The document's node table as `id(type) slot:[children]` lines, one per row, ascending by id.
 *
 * The same helper `PatchRoundTripTest` uses, copied for the reason that file's is `private`.
 * `NodeTable.toString()` is `NodeTable(size=5)` — the size and nothing else — so a difference
 * inside the table would otherwise be reported as two documents printing identically, which is
 * how a broken row once survived two rounds of review.
 */
private fun treeOf(document: UiDocument): String = buildString {
    for (id in document.nodes.ids()) {
        val node: Node = document.nodes.require(id)
        val slots: String = node.slots.entries
            .sortedBy { it.key.value }
            .joinToString(" ") { (slot, children) ->
                "$slot:" + children.joinToString(",", "[", "]") { it.value }
            }
        append(id.value).append('(').append(node.type.value).append(')').append(' ').append(slots).append('\n')
    }
}

private val PLAN_SCHEMA = plannerSchema()

/** The base document, and the only place every row's "before" and "after" come from. */
private val BASE: UiDocument = plannerDocument()

/**
 * The base document's rows, declared before [CASES] on purpose: top-level `val`s in a file are
 * initialised in declaration order, and a table that read `BASE_ROWS` before it existed would
 * see `null` — which is the kind of failure that only shows up on the second run.
 */
private val BASE_ROWS: List<Node> = baseRows()

private val CASES: List<PlanCase> = listOf(
    PlanCase(
        name = "insert one node at the front",
        before = BASE,
        command = Command.InsertNode(ROOT, CHILDREN, 0, NodeTemplate(listOf(freeText()))),
        ids = ::freshIds,
        ops = listOf(PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(freeText()))),
        after = plannerDocument(
            rootChildren = listOf(MINTED_ONE, ALPHA, BETA, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS + freeText(),
        ),
    ),
    PlanCase(
        name = "insert a whole subtree in the middle",
        before = BASE,
        command = Command.InsertNode(ROOT, CHILDREN, 1, NodeTemplate(listOf(nestNode(), nestLeaf()))),
        ids = ::freshIds,
        ops = listOf(PatchOp.InsertNodes(ROOT, CHILDREN, 1, listOf(nestNode(), nestLeaf()))),
        after = plannerDocument(
            rootChildren = listOf(ALPHA, MINTED_ONE, BETA, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS + nestNode() + nestLeaf(),
        ),
    ),
    PlanCase(
        name = "remove a subtree and the position it held",
        before = BASE,
        command = Command.RemoveNode(BETA),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.RemoveSubtree(
                BETA,
                RemovedSubtree(ROOT, CHILDREN, 1, listOf(betaNode(), deltaNode(), echoNode())),
            ),
        ),
        after = plannerDocument(
            rootChildren = listOf(ALPHA, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS - betaNode() - deltaNode() - echoNode(),
        ),
    ),
    PlanCase(
        name = "move within one slot",
        before = BASE,
        command = Command.MoveNode(GAMMA, ROOT, CHILDREN, 0),
        ids = ::freshIds,
        ops = listOf(PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0))),
        after = plannerDocument(rootChildren = listOf(GAMMA, ALPHA, BETA, FOXTROT, GOLF), rows = BASE_ROWS),
    ),
    PlanCase(
        name = "move between two parents",
        before = BASE,
        command = Command.MoveNode(ALPHA, BETA, CHILDREN, 1),
        ids = ::freshIds,
        ops = listOf(PatchOp.MoveNode(ALPHA, Placement(ROOT, CHILDREN, 0), Placement(BETA, CHILDREN, 1))),
        after = plannerDocument(
            rootChildren = listOf(BETA, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS - alphaNode() + Node(
                id = BETA,
                type = COLUMN,
                slots = mapOf(CHILDREN to listOf(DELTA, ALPHA, ECHO)),
            ),
        ),
    ),
    PlanCase(
        name = "duplicate a subtree under fresh ids",
        before = BASE,
        command = Command.DuplicateNode(BETA),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.InsertNodes(
                ROOT,
                CHILDREN,
                2,
                listOf(
                    Node(id = MINTED_ONE, type = COLUMN, slots = mapOf(CHILDREN to listOf(MINTED_TWO, MINTED_THREE))),
                    Node(id = MINTED_TWO, type = TEXT),
                    Node(id = MINTED_THREE, type = TEXT),
                ),
            ),
        ),
        after = plannerDocument(
            rootChildren = listOf(ALPHA, BETA, MINTED_ONE, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS + Node(
                id = MINTED_ONE,
                type = COLUMN,
                slots = mapOf(CHILDREN to listOf(MINTED_TWO, MINTED_THREE)),
            ) + Node(id = MINTED_TWO, type = TEXT) + Node(id = MINTED_THREE, type = TEXT),
        ),
    ),
    PlanCase(
        // The wrapper is inserted *empty* and the node moved into it afterwards. Inserting it
        // already holding the node would leave that node parentless after the inverse.
        name = "wrap a node in a component",
        before = BASE,
        command = Command.WrapNode(GAMMA, CARD, CONTENT),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.InsertNodes(ROOT, CHILDREN, 2, listOf(Node(id = MINTED_ONE, type = CARD))),
            PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 3), Placement(MINTED_ONE, CONTENT, 0)),
        ),
        after = plannerDocument(
            rootChildren = listOf(ALPHA, BETA, MINTED_ONE, FOXTROT, GOLF),
            rows = BASE_ROWS + Node(id = MINTED_ONE, type = CARD, slots = mapOf(CONTENT to listOf(GAMMA))),
        ),
    ),
    PlanCase(
        // The ordering is the subject of this row: children lifted last-first, so the forward
        // result keeps their order and the inverted moves still land at the index each child
        // held. And the capture is empty, because that is what the applier finds once they
        // have all left.
        name = "unwrap a node and lift its children into its place",
        before = BASE,
        command = Command.UnwrapNode(BETA),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.MoveNode(ECHO, Placement(BETA, CHILDREN, 1), Placement(ROOT, CHILDREN, 1)),
            PatchOp.MoveNode(DELTA, Placement(BETA, CHILDREN, 0), Placement(ROOT, CHILDREN, 1)),
            PatchOp.RemoveSubtree(
                BETA,
                RemovedSubtree(ROOT, CHILDREN, 3, listOf(betaNode().copy(slots = emptyMap()))),
            ),
        ),
        after = plannerDocument(
            rootChildren = listOf(ALPHA, DELTA, ECHO, GAMMA, FOXTROT, GOLF),
            rows = BASE_ROWS - betaNode(),
        ),
    ),
    PlanCase(
        // The same discipline with children in two slots, which is where the ordering has to
        // be argued rather than traced: the slots are flattened in name order and the whole
        // flattened list is then lifted back to front.
        name = "unwrap a node whose children are in two slots",
        before = plannerDocument(
            rootChildren = listOf(BETA, GAMMA),
            rows = listOf(
                Node(id = BETA, type = COLUMN, slots = mapOf(CONTENT to listOf(DELTA, ECHO), TOP_BAR to listOf(HOTEL))),
                gammaNode(),
                deltaNode(),
                echoNode(),
                hotelNode(),
            ),
        ),
        command = Command.UnwrapNode(BETA),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.MoveNode(HOTEL, Placement(BETA, TOP_BAR, 0), Placement(ROOT, CHILDREN, 0)),
            PatchOp.MoveNode(ECHO, Placement(BETA, CONTENT, 1), Placement(ROOT, CHILDREN, 0)),
            PatchOp.MoveNode(DELTA, Placement(BETA, CONTENT, 0), Placement(ROOT, CHILDREN, 0)),
            PatchOp.RemoveSubtree(
                BETA,
                RemovedSubtree(ROOT, CHILDREN, 3, listOf(Node(id = BETA, type = COLUMN))),
            ),
        ),
        after = plannerDocument(
            rootChildren = listOf(DELTA, ECHO, HOTEL, GAMMA),
            rows = listOf(gammaNode(), deltaNode(), echoNode(), hotelNode()),
        ),
    ),
    PlanCase(
        name = "set a property",
        before = BASE,
        command = Command.SetProperty(ALPHA, TEXT_KEY, OMEGA_TEXT),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetProp(ALPHA, TEXT_KEY, ALPHA_TEXT, OMEGA_TEXT)),
        after = plannerDocument(rows = BASE_ROWS - alphaNode() + alphaNode(text = OMEGA_TEXT)),
    ),
    PlanCase(
        name = "clear a property",
        before = BASE,
        command = Command.SetProperty(ALPHA, TEXT_KEY, null),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetProp(ALPHA, TEXT_KEY, ALPHA_TEXT, null)),
        after = plannerDocument(rows = BASE_ROWS - alphaNode() + alphaNode(text = null)),
    ),
    PlanCase(
        name = "replace a modifier chain, order and all",
        before = BASE,
        command = Command.SetModifiers(ALPHA, listOf(PADDING, BACKGROUND)),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetModifiers(ALPHA, listOf(PADDING), listOf(PADDING, BACKGROUND))),
        after = plannerDocument(
            rows = BASE_ROWS - alphaNode() + alphaNode(modifiers = listOf(PADDING, BACKGROUND)),
        ),
    ),
    PlanCase(
        name = "clear a handler",
        before = BASE,
        command = Command.SetEventHandler(ALPHA, ON_CLICK, null),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetHandler(ALPHA, ON_CLICK, CLICK, null)),
        after = plannerDocument(rows = BASE_ROWS - alphaNode() + alphaNode(events = emptyMap())),
    ),
    PlanCase(
        name = "rename a node",
        before = BASE,
        command = Command.RenameNode(ALPHA, "Renamed"),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetNodeName(ALPHA, "Alpha", "Renamed")),
        after = plannerDocument(rows = BASE_ROWS - alphaNode() + alphaNode(name = "Renamed")),
    ),
    PlanCase(
        // The two ops, in the only order that works. Swapped, `PutRecord` would name a node
        // the table does not hold; and because `PatchInverter` reverses the list, the wrong
        // order would undo by removing the rows before the page that points at them.
        name = "add a page and the tree it starts at",
        before = BASE,
        command = Command.AddPage(newPage(), NodeTemplate(listOf(Node(id = MINTED_ONE, type = COLUMN)))),
        ids = ::freshIds,
        ops = listOf(
            PatchOp.PutNodes(listOf(Node(id = MINTED_ONE, type = COLUMN))),
            PatchOp.PutRecord(RecordRef.Page(NEW), null, Record.Page(newPage())),
        ),
        after = plannerDocument(
            rows = BASE_ROWS + Node(id = MINTED_ONE, type = COLUMN),
            pages = mapOf(HOME to homePage(), NEW to newPage()),
        ),
    ),
    PlanCase(
        name = "replay a patch the engine did not produce",
        before = BASE,
        command = Command.ApplyPatch(Patch(listOf(PatchOp.SetNodeName(GAMMA, null, "Gamma")))),
        ids = ::freshIds,
        ops = listOf(PatchOp.SetNodeName(GAMMA, null, "Gamma")),
        after = plannerDocument(rows = BASE_ROWS - gammaNode() + gammaNode(name = "Gamma")),
    ),
)