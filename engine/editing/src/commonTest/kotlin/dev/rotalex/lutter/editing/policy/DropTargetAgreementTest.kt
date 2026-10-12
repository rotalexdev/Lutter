package dev.rotalex.lutter.editing.policy

import dev.rotalex.lutter.editing.command.BETA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.CHILDREN
import dev.rotalex.lutter.editing.command.COLUMN
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.command.FOOTER
import dev.rotalex.lutter.editing.command.FOXTROT
import dev.rotalex.lutter.editing.command.HOTEL
import dev.rotalex.lutter.editing.command.INDIA
import dev.rotalex.lutter.editing.command.JULIET
import dev.rotalex.lutter.editing.command.KILO
import dev.rotalex.lutter.editing.command.LIMA
import dev.rotalex.lutter.editing.command.MINTED_ONE
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT
import dev.rotalex.lutter.editing.command.TOP_BAR
import dev.rotalex.lutter.editing.command.UNREGISTERED
import dev.rotalex.lutter.editing.command.freshIds
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.command.plannerSchema
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.overlay.SchemaOverlay
import dev.rotalex.lutter.schema.registry.Registry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN §25.3's promise, tested from both ends.
 *
 * The sentence is: *"Planners check schema policies (`SlotSpec.accepts/cardinality`) so an
 * editor can offer only legal drop targets via `DropTargets.forNode(document, schema,
 * nodeType)`."* Read as a test that is two directions, and this class is both:
 *
 *  * **Everything `DropTargets` reports, the planner accepts.** A target that refuses is the
 *    bug the sentence exists to prevent, and it is invisible until a user drags onto a
 *    highlight the engine drew.
 *  * **Everything `DropTargets` does not report, the planner refuses.** The other direction,
 *    and the one a test that only iterates the reported list would miss: a *missing* target
 *    is a highlight the editor never draws, which reads as a missing feature rather than as a
 *    defect.
 *
 * ### The partition, rather than a sample
 *
 * The first test below enumerates **every** (node, declared slot) pair in each scenario's
 * document, asks the planner about each, and requires that `DropTargets` said the same thing.
 * That is the whole rule, stated as one equality over a finite set, rather than a handful of
 * examples of it.
 *
 * ### The oracle is built independently, and why
 *
 * The candidate list is read out of [SchemaOverlay] applied to the fixture schema, **not**
 * out of whatever `DropTargets` resolved. The overlay is applied unconditionally here, where
 * `CommandPlanner` applies it only when the document declares components of its own; for a
 * document that declares none, the two registries are the same set, so the oracle is not a
 * second copy of that branch — it is a second opinion about *which specs apply to this
 * document*, which is precisely the thing under test. A candidate list read out of the
 * production code would agree with it by construction and prove nothing.
 *
 * ### One index, chosen so the index cannot be the excuse
 *
 * Every candidate is planned at the **end** of its slot, which is the [DropTarget.index] the
 * production code reports and is always in range. So a refusal in this class is about the
 * slot and never about the position, and an acceptance cannot be an accident of a lucky
 * index. The rule under test is about *places*; the index is held constant so that the test
 * tests it.
 */
class DropTargetAgreementTest {

    /**
     * The rule itself, over every candidate in every scenario.
     *
     * The message names the scenario, the candidate and **both** answers, because a
     * disagreement between two answers is what a reader has to adjudicate and neither answer
     * alone is evidence for it.
     */
    @Test
    fun `every reported target plans and every candidate it did not report is refused`() {
        for (scenario in SCENARIOS) {
            val reported: List<DropTarget> = DropTargets.forNode(scenario.document, SCHEMA, scenario.type)

            for (candidate in candidatesIn(scenario.document)) {
                val offered: Boolean = candidate in reported
                val planned: CommandResult = CommandPlanner.plan(
                    command = Command.InsertNode(
                        candidate.parent,
                        candidate.slot,
                        candidate.index,
                        NodeTemplate(listOf(Node(id = MINTED_ONE, type = scenario.type))),
                    ),
                    document = scenario.document,
                    schema = SCHEMA,
                    ids = freshIds(),
                )

                assertEquals(
                    offered,
                    planned is CommandResult.Ok,
                    "${scenario.name}: DropTargets ${if (offered) "offered" else "withheld"} " +
                        "$candidate and the planner said $planned",
                )
            }
        }
    }

    /**
     * The reported targets, written out by hand, so the partition above cannot pass by
     * returning nothing.
     *
     * A partition over an empty set satisfies every equality in it, so a `DropTargets` that
     * answered `emptyList()` to every question would pass the partition test above
     * completely. These rows are what stop that, and they are written from the fixture's specs
     * rather than from the output — the same discipline `CommandPlannerRoundTripTest` uses for
     * its expected ops.
     *
     * Read the first two together: one document, one set of nodes, and answers that differ by
     * nothing but *which node type is being dropped*. `Column.footer` is a `Many` slot with a
     * negative filter and `Card.content` is a `ZeroOrOne` with a positive one, so a suite
     * carrying only unbounded `Any` slots would pass with both checks deleted.
     */
    @Test
    fun `the reported targets are exactly these`() {
        for (row in EXPECTED) {
            assertEquals(
                row.targets,
                DropTargets.forNode(row.document, SCHEMA, row.type),
                "${row.name}: the reported targets",
            )
        }
    }

    /**
     * A `doc.*` instance offers the slots its declaration names, because §8.4's overlay
     * synthesizes its spec.
     *
     * This is the case where two answers genuinely came apart in the design that preceded
     * this one: an instance's type is `doc.Badge`, which no static schema registers, so a
     * resolver that skipped the overlay offered **nothing at all** inside the instance while
     * the planner — which does apply it — accepted an insertion there. The editor would have
     * shown no drop target inside any component a document declares for itself, and §7.5's
     * "instance" would have been a thing nothing could be put into.
     *
     * §7.5's own sentence is the expectation: an instance's slots fill `ComponentDecl.slots`,
     * and the synthesized spec declares exactly those.
     */
    @Test
    fun `a document component instance offers the slots its declaration names`() {
        val targets: List<DropTarget> = DropTargets.forNode(INSTANCE_DOCUMENT, SCHEMA, TEXT)

        assertTrue(
            DropTarget(HOTEL, CONTENT, 0) in targets,
            "the instance's declared content slot is not a drop target: $targets",
        )
    }

    /**
     * A slot the component does not declare is never offered, and an insertion into it is
     * refused.
     *
     * The document is legal enough to exist and wrong in one place: a `Card` holding a child
     * in a `topBar`, which its spec does not declare. §17.1 reports that and D4 keeps it in
     * the document, so an editor has to render around it — and `Node.slots` is a map of what
     * a node *has*, while a drop target is a claim about what its component will *take*.
     * Enumerating the node's own map is the defect this pins shut: it would offer a target
     * the planner answers with `edit.slot_undeclared`.
     */
    @Test
    fun `a slot the component does not declare is neither offered nor accepted`() {
        val document: UiDocument = undeclaredSlotDocument()

        val targets: List<DropTarget> = DropTargets.forNode(document, SCHEMA, TEXT)
        assertTrue(
            targets.none { it.parent == FOXTROT && it.slot == TOP_BAR },
            "a slot Card does not declare was offered: $targets",
        )

        val planned: CommandResult = CommandPlanner.plan(
            Command.InsertNode(
                FOXTROT,
                TOP_BAR,
                1,
                NodeTemplate(listOf(Node(id = MINTED_ONE, type = TEXT))),
            ),
            document,
            SCHEMA,
            freshIds(),
        )
        assertEquals(
            listOf(EditCodes.UndeclaredSlot),
            codesOf(planned),
            "inserting into an undeclared slot was not refused for being one",
        )
    }

    /**
     * A parent whose component type nothing registers is never offered, and is refused.
     *
     * With no spec there is no slot list, no `accepts` and no `cardinality` — nothing to
     * certify an insertion with. §25.3's "legality is asked of the specs" has no other honest
     * answer than "not offered", and it has to match the planner's `edit.unknown_component`
     * or the editor will draw a drop zone inside a component it cannot describe.
     *
     * D4 is not in tension with this. An unregistered **node** is preserved and reported as
     * `component.unknown`; an unregistered node used as the **parent** of an edit is where
     * the spec is needed to answer the question at all.
     */
    @Test
    fun `a parent nothing registered is neither offered nor accepted`() {
        val document: UiDocument = plannerDocument(rootType = UNREGISTERED)

        assertEquals(
            emptyList(),
            DropTargets.forNode(document, SCHEMA, TEXT),
            "a root whose type is not registered was offered as a parent",
        )

        val planned: CommandResult = CommandPlanner.plan(
            Command.InsertNode(ROOT, CHILDREN, 0, NodeTemplate(listOf(Node(id = MINTED_ONE, type = TEXT)))),
            document,
            SCHEMA,
            freshIds(),
        )
        assertEquals(
            listOf(EditCodes.UnknownComponent),
            codesOf(planned),
            "inserting under an unregistered parent was not refused for that",
        )
    }

    /**
     * The same slot, before and after the child that filled it is gone.
     *
     * `Cardinality` is a property of the spec, but whether it has been **reached** is a
     * property of the document, and only the second is this file's to answer. A drop target
     * computed from the spec alone would offer a full card's `content` forever, and refuse it
     * on drop.
     */
    @Test
    fun `a full slot stops being a target when the document changes`() {
        val full: List<DropTarget> = DropTargets.forNode(BASE, SCHEMA, TEXT)
        val emptied: List<DropTarget> = DropTargets.forNode(EMPTY_CARD_DOCUMENT, SCHEMA, TEXT)

        assertTrue(
            DropTarget(FOXTROT, CONTENT, 1) !in full,
            "a full ZeroOrOne slot was offered: $full",
        )
        assertEquals(
            listOf(DropTarget(FOXTROT, CONTENT, 0)),
            emptied.filter { it.parent == FOXTROT },
            "an empty ZeroOrOne slot was not offered",
        )
    }

    /**
     * The same question asked twice gives the same list, in the same order.
     *
     * An editor renders these into a tree, and §6.2's determinism is a claim about what the
     * engine produces from a document: a list that came back in a different order would put a
     * target's highlight somewhere else on every repaint. The order comes from
     * `NodeTable.ids()` ascending and from a spec's own declaration order, both of which are
     * fixed.
     */
    @Test
    fun `the same question twice gives the same list`() {
        for (scenario in SCENARIOS) {
            assertEquals(
                DropTargets.forNode(scenario.document, SCHEMA, scenario.type),
                DropTargets.forNode(scenario.document, SCHEMA, scenario.type),
                "${scenario.name}: two runs disagreed",
            )
        }
    }

    /**
     * Every (node, declared slot) pair in [document], as a candidate at the end of its slot.
     *
     * The candidates come from the overlay applied directly; see the class KDoc for why the
     * production resolver is not the source here.
     */
    private fun candidatesIn(document: UiDocument): List<DropTarget> {
        val components: Registry<ComponentType, ComponentSpec> = SchemaOverlay(SCHEMA, document).components
        val found: MutableList<DropTarget> = mutableListOf()

        for (parentId in document.nodes.ids()) {
            val parent: Node = document.nodes[parentId] ?: continue
            val spec: ComponentSpec = components[parent.type] ?: continue
            for (slot in spec.slots) {
                found += DropTarget(parentId, slot.name, parent.slots[slot.name].orEmpty().size)
            }
        }
        return found
    }

    /** The codes a plan was refused with, or nothing for an acceptance. */
    private fun codesOf(result: CommandResult): List<DiagnosticCode> = when (result) {
        is CommandResult.Ok -> emptyList()
        is CommandResult.Rejected -> result.reasons.map { it.code }
    }
}

/** One document and one component type: the question being asked. */
private class Scenario(val name: String, val document: UiDocument, val type: ComponentType)

/** One row of the expected-targets table. */
private class Expected(
    val name: String,
    val document: UiDocument,
    val type: ComponentType,
    val targets: List<DropTarget>,
)

private val SCHEMA = plannerSchema()

private val BASE: UiDocument = plannerDocument()

/** The `doc.` type an instance of a document component carries (§5.7). */
private val BADGE: ComponentType = ComponentType("doc.Badge")

/** An instance of [BADGE], with nothing in the slot its declaration declares. */
private fun badgeNode(): Node = Node(id = HOTEL, type = BADGE)

/** The body [BADGE]'s declaration renders: a column with no children. */
private fun bodyNode(): Node = Node(id = KILO, type = COLUMN)

/** §7.5: an instance's slots fill `ComponentDecl.slots`, which is the whole override surface. */
private fun badgeDecl(): ComponentDecl = ComponentDecl(
    id = ComponentDeclId("Badge"),
    name = "Badge",
    params = emptyList(),
    slots = listOf(SlotDecl(CONTENT)),
    root = KILO,
)

/**
 * A document with one instance in it.
 *
 * The declaration's root is [KILO] and that row is really in the table, because a fixture
 * that describes a different document from the one under test is the defect family this
 * module has already paid for twice. `DocumentIndex` tolerates a declaration naming a node
 * that is absent; a test has no reason to.
 */
private fun instanceDocument(): UiDocument = plannerDocument(
    rootChildren = listOf(HOTEL),
    rows = listOf(badgeNode(), bodyNode()),
    components = mapOf(ComponentDeclId("Badge") to badgeDecl()),
)

private val INSTANCE_DOCUMENT: UiDocument = instanceDocument()

/** A `Card` with nothing in its `ZeroOrOne` content slot: the same spec, with room. */
private fun emptyCard(): Node = Node(id = FOXTROT, type = CARD)

private val EMPTY_CARD_DOCUMENT: UiDocument =
    plannerDocument(rootChildren = listOf(FOXTROT), rows = listOf(emptyCard()))

/** A `Card` holding a child in a slot its spec does not declare. §17.1 reports this one. */
private fun undeclaredSlotDocument(): UiDocument = plannerDocument(
    rootChildren = listOf(FOXTROT),
    rows = listOf(
        Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(HOTEL), TOP_BAR to listOf(INDIA))),
        Node(id = HOTEL, type = TEXT),
        Node(id = INDIA, type = TEXT),
    ),
)

private val SCENARIOS: List<Scenario> = listOf(
    Scenario("a text node in the base document", BASE, TEXT),
    Scenario("a column in the base document", BASE, COLUMN),
    Scenario("a document component instance", INSTANCE_DOCUMENT, TEXT),
    Scenario("a card with an empty content slot", EMPTY_CARD_DOCUMENT, TEXT),
)

private val EXPECTED: List<Expected> = listOf(
    Expected(
        name = "a text node in the base document",
        document = BASE,
        type = TEXT,
        // `Column.children` is unbounded and admits everything. `Column.footer` is unbounded
        // and refuses `Text`, which is the whole reason the row below exists. `Card.content`
        // and `Banner.topBar` are both full, and `Alpha`, `Gamma` and the leaves have no
        // slots at all. The order is ascending node id: n_b, n_j, n_k, n_l, n_root.
        targets = listOf(
            DropTarget(BETA, CHILDREN, 2),
            DropTarget(JULIET, CHILDREN, 2),
            DropTarget(KILO, CHILDREN, 0),
            DropTarget(LIMA, CHILDREN, 0),
            DropTarget(ROOT, CHILDREN, 5),
        ),
    ),
    Expected(
        name = "a column in the base document",
        document = BASE,
        type = COLUMN,
        // The same document and the same nodes, with five more targets than the row above: the
        // `footer` slots, which refuse `Text` and admit everything else. `Card.content` and
        // `Banner.topBar` are still absent — those are `Only({Text, Image})` and
        // `Only({Text})`, and neither admits a column.
        targets = listOf(
            DropTarget(BETA, CHILDREN, 2),
            DropTarget(BETA, FOOTER, 0),
            DropTarget(JULIET, CHILDREN, 2),
            DropTarget(JULIET, FOOTER, 0),
            DropTarget(KILO, CHILDREN, 0),
            DropTarget(KILO, FOOTER, 0),
            DropTarget(LIMA, CHILDREN, 0),
            DropTarget(LIMA, FOOTER, 0),
            DropTarget(ROOT, CHILDREN, 5),
            DropTarget(ROOT, FOOTER, 0),
        ),
    ),
    Expected(
        name = "a document component instance",
        document = INSTANCE_DOCUMENT,
        type = TEXT,
        // The instance comes first because `n_h` sorts before `n_k` and `n_root`, and its
        // `content` slot exists only because §8.4 synthesized the spec from
        // `ComponentDecl.slots`.
        targets = listOf(
            DropTarget(HOTEL, CONTENT, 0),
            DropTarget(KILO, CHILDREN, 0),
            DropTarget(ROOT, CHILDREN, 1),
            DropTarget(ROOT, FOOTER, 0),
        ),
    ),
)