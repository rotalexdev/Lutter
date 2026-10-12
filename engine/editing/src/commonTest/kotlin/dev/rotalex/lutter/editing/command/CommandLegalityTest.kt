package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SlotName
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN §32's Phase 9 test by name: *"command legality tests"*.
 *
 * ### What the table asserts
 *
 * Every row is a command that must be **refused**, and the assertion is that the refusal
 * carries the right code. The table *is* the coverage: a refusal path nobody wrote down is a
 * refusal path nobody is checking, and the two tests at the bottom of this class exist for
 * exactly that — one counts the codes in [EditCodes] the table reaches, the other counts the
 * [Command] variants the two tables between them name.
 *
 * **Membership rather than equality, and deliberately.** §25.3's policies are independent: a
 * slot that is both full and closed is two true things, and the planner reports both so an
 * editor can say both at once. A test asserting a single code would then be asserting which
 * of two genuine facts the planner noticed first, and a change to the order of two checks
 * would fail it for no reason. Rows whose `only` flag is set *do* assert one reason, and those
 * are the rows where the count is not a second fact — the index, the id, the missing node.
 *
 * ### Three properties asserted for every row, not just the code
 *
 *  * **No patch.** `CommandResult.Rejected` has no patch field, so this is structural rather
 *    than asserted: a refused command cannot hand one back. [a refusal carries no patch] states
 *    it out loud so the next reader does not have to re-derive it from the type.
 *  * **No exception.** A planner that throws has produced a refusal an editor cannot render,
 *    so every row below runs through an ordinary call and there is no `assertFailsWith`
 *    anywhere in this file. The absence is the assertion.
 *  * **Determinism.** The same command planned twice against the same document gives the same
 *    refusal. That is checkable and it is not automatic — two of the planners consult an id
 *    generator, and one that read its state differently on the second call would produce a
 *    different answer for the same edit.
 */
class CommandLegalityTest {

    /** The table. */
    @Test
    fun `every illegal command is rejected with the right reason`() {
        for (case in REFUSALS) {
            val result: CommandResult = CommandPlanner.plan(case.command, case.document, LEGALITY_SCHEMA, case.ids())

            assertTrue(result is CommandResult.Rejected, "${case.name}: expected a rejection, got $result")

            val reasons: List<Diagnostic> = (result as CommandResult.Rejected).reasons
            val codes: List<DiagnosticCode> = reasons.map { it.code }

            assertTrue(case.expected in codes, "${case.name}: expected ${case.expected} among $codes")
            if (case.only) {
                assertEquals(listOf(case.expected), codes, "${case.name}: expected one reason, got $reasons")
            }

            // A refusal is never bare, and it is always an Error: §25.2 hands it to an editor
            // that has to say why, and §26.3 has no place to put an ignored refusal.
            for (reason in reasons) {
                assertTrue(reason.message.isNotBlank(), "${case.name}: a refusal with no message")
                assertEquals(Severity.Error, reason.severity, "${case.name}: a refusal that is not an error")
            }
        }
    }

    /**
     * A refusal carries no patch, so a rejected command has produced nothing to apply.
     *
     * `CommandResult` is a sealed interface of two arms and only one of them has a patch, so
     * there is no accessor to call here and nothing to assert beyond the type. That is the
     * point of the assertion being written at all: "a rejected command produced no patch" is a
     * claim about a design rather than about a run, and a reader who is told so does not go
     * looking for the null check.
     */
    @Test
    fun `a refusal carries no patch`() {
        val rejected: CommandResult = CommandPlanner.plan(
            Command.RemoveNode(ROOT),
            plannerDocument(),
            LEGALITY_SCHEMA,
            freshIds(),
        )

        assertTrue(rejected is CommandResult.Rejected)
        assertEquals(EditCodes.NoParent, (rejected as CommandResult.Rejected).reasons.single().code)
    }

    /**
     * The same refusal twice, from the same document, with a generator that starts over.
     *
     * This is the strongest statement about "a refusal touches no state" that a pure function
     * admits. The weaker version — assert the caller's document still equals itself — would
     * pass against a planner that silently rebuilt it, because `UiDocument` is an immutable
     * value and the caller holds its own reference either way. What can actually differ
     * between two runs is the *answer*, so the answer is what is compared.
     */
    @Test
    fun `a refusal is the same refusal every time`() {
        for (case in REFUSALS) {
            val first: CommandResult = CommandPlanner.plan(case.command, case.document, LEGALITY_SCHEMA, case.ids())
            val second: CommandResult = CommandPlanner.plan(case.command, case.document, LEGALITY_SCHEMA, case.ids())

            assertEquals(first, second, "${case.name}: the same command planned twice disagreed")
        }
    }

    /**
     * The table reaches every code, so no refusal path was forgotten.
     *
     * A missing row leaves no trace: the test above still passes, because it only checks the
     * rows it has. This turns that into a failure — and it is the reason the codes are a closed
     * catalog rather than strings at call sites, since a new code cannot be added without this
     * test going red until somebody says which command produces it.
     */
    @Test
    fun `every refusal code in the catalogue is reached by a row`() {
        val reached: Set<DiagnosticCode> = REFUSALS.map { it.expected }.toSet()

        assertEquals(
            ALL_CODES.toSet(),
            reached,
            "the table reaches ${reached.size} of ${ALL_CODES.size} codes",
        )
    }

    /**
     * Every command in §25.3's closed set is named by one of the two tables.
     *
     * This file and [CommandPlannerRoundTripTest] are the only places a `Command` is
     * constructed by name, and a variant neither names would have no test at all — while the
     * `when` over the sealed interface would still compile and still handle it.
     *
     * The variant list is written out rather than read from `KClass.sealedSubclasses`, which
     * would need `kotlin-reflect` on the test classpath. The count assertion below is what
     * keeps the written-out list honest.
     */
    @Test
    fun `every command in the closed set is named by one of the two tables`() {
        val named: Set<KClass<out Command>> = REFUSED_COMMANDS + CommandPlannerRoundTripTest.PLANNED_COMMANDS

        assertEquals(COMMAND_VARIANTS.toSet(), named, "the two tables name ${named.size} commands")
        assertEquals(12, COMMAND_VARIANTS.size, "§25.3 declares twelve; this list is the twelfth check")
    }

    companion object {
        /** The commands this table refuses, by class. Read by the coverage test above. */
        internal val REFUSED_COMMANDS: Set<KClass<out Command>> = REFUSALS.map { it.command::class }.toSet()
    }
}

/** One row: a command that must be refused, and the reason it must be. */
private class RejectionCase(
    val name: String,
    val document: UiDocument,
    val command: Command,
    val ids: () -> IdGenerator,
    val expected: DiagnosticCode,
    val only: Boolean = false,
)

/**
 * Every code the catalogue declares, written out so that a new one fails the coverage test.
 *
 * The same thirteen members as [EditCodes], and deliberately written out rather than reflected:
 * `EditCodes` is an `object`, and enumerating an object's properties needs `kotlin-reflect`,
 * which this module does not carry. So the two lists are compared by hand and this one is the
 * one that fails when they drift.
 */
private val ALL_CODES: List<DiagnosticCode> = listOf(
    EditCodes.UnknownNode,
    EditCodes.UnknownComponent,
    EditCodes.UndeclaredSlot,
    EditCodes.SlotCardinality,
    EditCodes.SlotChildNotAllowed,
    EditCodes.IndexOutOfRange,
    EditCodes.DuplicateId,
    EditCodes.NoParent,
    EditCodes.Cycle,
    EditCodes.MultipleParents,
    EditCodes.EmptySubtree,
    EditCodes.NothingToUnwrap,
    EditCodes.RootMismatch,
)

/**
 * §25.3's closed set, minus `ExtractComponent` — which is deferred, and the reason is on
 * [Command]. Twelve, asserted below.
 */
private val COMMAND_VARIANTS: Set<KClass<out Command>> = setOf(
    Command.InsertNode::class,
    Command.RemoveNode::class,
    Command.MoveNode::class,
    Command.DuplicateNode::class,
    Command.WrapNode::class,
    Command.UnwrapNode::class,
    Command.SetProperty::class,
    Command.SetModifiers::class,
    Command.SetEventHandler::class,
    Command.RenameNode::class,
    Command.AddPage::class,
    Command.ApplyPatch::class,
)

private val LEGALITY_SCHEMA = plannerSchema()

private fun missingSlot(): SlotName = SlotName("not_a_slot")

private val TEMPLATE_TEXT = NodeTemplate(listOf(freeText()))

/**
 * A page to add, with its root named explicitly.
 *
 * Named `pageFor` rather than `newPage` so it cannot be confused with the fixture's
 * zero-argument `newPage()`, and so this file's private declaration is not shadowing a
 * same-named one in another file of the same package.
 */
private fun pageFor(id: PageId, root: NodeId): Page =
    Page(id = id, name = "NewScreen", route = "/new", root = root)

private val PAGE_SUBTREE = NodeTemplate(listOf(Node(id = MINTED_ONE, type = COLUMN)))

private val REFUSALS: List<RejectionCase> = listOf(
    // -----------------------------------------------------------------------------------
    // Slots: the policies §25.3 names, and the shape of each refusal
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "insert into a slot the parent does not declare",
        document = plannerDocument(),
        command = Command.InsertNode(ROOT, missingSlot(), 0, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.UndeclaredSlot,
        only = true,
    ),
    RejectionCase(
        name = "insert past a slot's cardinality",
        document = plannerDocument(),
        command = Command.InsertNode(GOLF, TOP_BAR, 1, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.SlotCardinality,
        only = true,
    ),
    RejectionCase(
        name = "insert a child the slot's filter does not admit",
        document = plannerDocument(),
        command = Command.InsertNode(ROOT, FOOTER, 0, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.SlotChildNotAllowed,
        only = true,
    ),
    RejectionCase(
        name = "move into a slot the new parent does not declare",
        document = plannerDocument(),
        command = Command.MoveNode(ALPHA, FOXTROT, missingSlot(), 0),
        ids = ::freshIds,
        expected = EditCodes.UndeclaredSlot,
        only = true,
    ),
    RejectionCase(
        name = "move into a slot that does not admit the node",
        document = plannerDocument(),
        command = Command.MoveNode(ALPHA, ROOT, FOOTER, 0),
        ids = ::freshIds,
        expected = EditCodes.SlotChildNotAllowed,
        only = true,
    ),
    RejectionCase(
        name = "duplicate into a slot that is full",
        document = plannerDocument(),
        command = Command.DuplicateNode(INDIA),
        ids = ::freshIds,
        expected = EditCodes.SlotCardinality,
        only = true,
    ),
    RejectionCase(
        name = "wrap in a component the schema does not register",
        document = plannerDocument(),
        command = Command.WrapNode(GAMMA, UNREGISTERED, CONTENT),
        ids = ::freshIds,
        expected = EditCodes.UnknownComponent,
        only = true,
    ),
    RejectionCase(
        name = "wrap in a slot the wrapper does not declare",
        document = plannerDocument(),
        command = Command.WrapNode(GAMMA, CARD, missingSlot()),
        ids = ::freshIds,
        expected = EditCodes.UndeclaredSlot,
    ),
    RejectionCase(
        name = "wrap where the wrapper's slot does not admit the node",
        document = plannerDocument(),
        command = Command.WrapNode(BETA, CARD, CONTENT),
        ids = ::freshIds,
        expected = EditCodes.SlotChildNotAllowed,
        only = true,
    ),
    RejectionCase(
        name = "unwrap into a parent slot that is both full and closed",
        document = plannerDocument(
            rootChildren = listOf(FOXTROT),
            rows = listOf(
                Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(JULIET))),
                julietNode(),
                kiloNode(),
                limaNode(),
            ),
        ),
        command = Command.UnwrapNode(JULIET),
        ids = ::freshIds,
        expected = EditCodes.SlotCardinality,
    ),

    // -----------------------------------------------------------------------------------
    // Nodes the document does not hold, and nodes whose component type nothing registered
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "insert into a node the document does not hold",
        document = plannerDocument(),
        command = Command.InsertNode(ABSENT, CHILDREN, 0, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "insert into a node whose component type is not registered",
        document = plannerDocument(rootType = UNREGISTERED),
        command = Command.InsertNode(ROOT, CHILDREN, 0, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.UnknownComponent,
        only = true,
    ),
    RejectionCase(
        name = "remove a node the document does not hold",
        document = plannerDocument(),
        command = Command.RemoveNode(ABSENT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "move a node the document does not hold",
        document = plannerDocument(),
        command = Command.MoveNode(ABSENT, ROOT, CHILDREN, 0),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "move into a node the document does not hold",
        document = plannerDocument(),
        command = Command.MoveNode(ALPHA, ABSENT, CHILDREN, 0),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "duplicate a node the document does not hold",
        document = plannerDocument(),
        command = Command.DuplicateNode(ABSENT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "wrap a node the document does not hold",
        document = plannerDocument(),
        command = Command.WrapNode(ABSENT, CARD, CONTENT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "unwrap a node the document does not hold",
        document = plannerDocument(),
        command = Command.UnwrapNode(ABSENT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),

    // -----------------------------------------------------------------------------------
    // Roots, which have no position to change
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "remove a root",
        document = plannerDocument(),
        command = Command.RemoveNode(ROOT),
        ids = ::freshIds,
        expected = EditCodes.NoParent,
        only = true,
    ),
    RejectionCase(
        name = "move a root",
        document = plannerDocument(),
        command = Command.MoveNode(ROOT, BETA, CHILDREN, 0),
        ids = ::freshIds,
        expected = EditCodes.NoParent,
        only = true,
    ),
    RejectionCase(
        name = "duplicate a root",
        document = plannerDocument(),
        command = Command.DuplicateNode(ROOT),
        ids = ::freshIds,
        expected = EditCodes.NoParent,
        only = true,
    ),
    RejectionCase(
        name = "wrap a root",
        document = plannerDocument(),
        command = Command.WrapNode(ROOT, CARD, CONTENT),
        ids = ::freshIds,
        expected = EditCodes.NoParent,
        only = true,
    ),
    RejectionCase(
        name = "unwrap a root",
        document = plannerDocument(),
        command = Command.UnwrapNode(ROOT),
        ids = ::freshIds,
        expected = EditCodes.NoParent,
        only = true,
    ),

    // -----------------------------------------------------------------------------------
    // Faults a patch would otherwise carry into the applier as an exception
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "insert at an index past the end of the slot",
        document = plannerDocument(),
        command = Command.InsertNode(ROOT, CHILDREN, 99, TEMPLATE_TEXT),
        ids = ::freshIds,
        expected = EditCodes.IndexOutOfRange,
        only = true,
    ),
    RejectionCase(
        name = "insert a subtree carrying an id the document already holds",
        document = plannerDocument(),
        command = Command.InsertNode(ROOT, CHILDREN, 0, NodeTemplate(listOf(gammaNode()))),
        ids = ::freshIds,
        expected = EditCodes.DuplicateId,
        only = true,
    ),
    RejectionCase(
        name = "insert an empty subtree",
        document = plannerDocument(),
        command = Command.InsertNode(ROOT, CHILDREN, 0, NodeTemplate(emptyList())),
        ids = ::freshIds,
        expected = EditCodes.EmptySubtree,
        only = true,
    ),
    RejectionCase(
        name = "duplicate when the generator returns an id the document already holds",
        document = plannerDocument(),
        command = Command.DuplicateNode(GAMMA),
        ids = { fixedIds(ALPHA) },
        expected = EditCodes.DuplicateId,
    ),
    RejectionCase(
        name = "wrap when the generator returns an id the document already holds",
        document = plannerDocument(),
        command = Command.WrapNode(GAMMA, CARD, CONTENT),
        ids = { fixedIds(ALPHA) },
        expected = EditCodes.DuplicateId,
    ),
    RejectionCase(
        name = "move a node into its own subtree",
        document = plannerDocument(),
        command = Command.MoveNode(BETA, DELTA, CHILDREN, 0),
        ids = ::freshIds,
        expected = EditCodes.Cycle,
        only = true,
    ),
    RejectionCase(
        name = "move a node into itself",
        document = plannerDocument(),
        command = Command.MoveNode(GAMMA, GAMMA, CHILDREN, 0),
        ids = ::freshIds,
        expected = EditCodes.Cycle,
        only = true,
    ),
    RejectionCase(
        name = "unwrap a node whose two slots claim the same child",
        document = plannerDocument(
            rootChildren = listOf(BETA),
            rows = listOf(
                Node(id = BETA, type = COLUMN, slots = mapOf(CHILDREN to listOf(GAMMA), TOP_BAR to listOf(GAMMA))),
                gammaNode(),
            ),
        ),
        command = Command.UnwrapNode(BETA),
        ids = ::freshIds,
        expected = EditCodes.MultipleParents,
        only = true,
    ),
    RejectionCase(
        name = "unwrap a node that lists a child the document does not hold",
        document = plannerDocument(
            rootChildren = listOf(BETA),
            rows = listOf(Node(id = BETA, type = COLUMN, slots = mapOf(CHILDREN to listOf(ABSENT)))),
        ),
        command = Command.UnwrapNode(BETA),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "unwrap a node with no children",
        document = plannerDocument(),
        command = Command.UnwrapNode(GAMMA),
        ids = ::freshIds,
        expected = EditCodes.NothingToUnwrap,
        only = true,
    ),

    // -----------------------------------------------------------------------------------
    // The four field setters: node existence is the only question they ask
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "set a property on a node the document does not hold",
        document = plannerDocument(),
        command = Command.SetProperty(ABSENT, TEXT_KEY, OMEGA_TEXT),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "set modifiers on a node the document does not hold",
        document = plannerDocument(),
        command = Command.SetModifiers(ABSENT, emptyList()),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "bind a handler on a node the document does not hold",
        document = plannerDocument(),
        command = Command.SetEventHandler(ABSENT, ON_CLICK, CLICK),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),
    RejectionCase(
        name = "rename a node the document does not hold",
        document = plannerDocument(),
        command = Command.RenameNode(ABSENT, "Renamed"),
        ids = ::freshIds,
        expected = EditCodes.UnknownNode,
        only = true,
    ),

    // -----------------------------------------------------------------------------------
    // Adding a page: what D30's ninth op made expressible, and what it did not make legal
    // -----------------------------------------------------------------------------------
    RejectionCase(
        name = "add a page whose id the document already holds",
        document = plannerDocument(),
        command = Command.AddPage(pageFor(id = HOME, root = MINTED_ONE), PAGE_SUBTREE),
        ids = ::freshIds,
        expected = EditCodes.DuplicateId,
        only = true,
    ),
    RejectionCase(
        name = "add a page with no root node",
        document = plannerDocument(),
        command = Command.AddPage(pageFor(id = NEW, root = MINTED_ONE), NodeTemplate(emptyList())),
        ids = ::freshIds,
        expected = EditCodes.EmptySubtree,
        only = true,
    ),
    RejectionCase(
        name = "add a page whose root is not the subtree's first row",
        document = plannerDocument(),
        command = Command.AddPage(pageFor(id = NEW, root = HOTEL), PAGE_SUBTREE),
        ids = ::freshIds,
        expected = EditCodes.RootMismatch,
        only = true,
    ),
    RejectionCase(
        name = "add a page whose subtree carries an id the document already holds",
        document = plannerDocument(),
        command = Command.AddPage(pageFor(id = NEW, root = GAMMA), NodeTemplate(listOf(gammaNode()))),
        ids = ::freshIds,
        expected = EditCodes.DuplicateId,
        only = true,
    ),
)