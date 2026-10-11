package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The table form of PLAN §26.2's property.
 *
 * The property, verbatim: *for any valid document `d` and patch `p` produced by a command,
 * applying `p`'s inverse to the result of applying `p` to `d` gives `d` back.* Plus the second
 * half of the same sentence — applying `p` to `d` is deterministic.
 *
 * ### Why this is a table and not a generator, and what would replace it
 *
 * **No property-testing library is wired into this repository.** `gradle/libs.versions.toml`
 * carries JUnit and `kotlinx-collections-immutable` and nothing else, `settings.gradle.kts`
 * takes every other coordinate from the org-wide `rootLibs` catalog, and no module's build
 * file mentions kotest, jqwik or quickcheck. PLAN §32's testing row for this module says
 * "patch/inverse property tests" without naming a library, and adding one would be a
 * dependency the whole build would carry for eight cases that can be written out in full.
 *
 * So this is the table: **one row per op, in both directions where an op has two**, and each
 * row states the document before, the op, and the document after. The rows do not go through
 * the applier to produce the "after" — they are written out — because a property test whose
 * oracle is the code under test is a test of nothing. Writing the expected document by hand
 * is also what makes the table legible as a specification of what each op *means*, which is
 * worth more here than a few hundred generated samples would be.
 *
 * **What would replace it:** a generator producing random documents and random well-formed op
 * sequences over them, asserting the same identity, with the generators shrunk to a failing
 * case. The two properties the table cannot reach are the ones that need breadth rather than
 * depth — long op sequences where the round trip depends on an interaction nobody thought of,
 * and documents large enough for `NodeTable`'s structural sharing or the position rules in
 * `PatchApplier.applyMove` to matter. The third test in this file is the small version of the
 * first: three ops that interact on one tree, undone in reverse order, where every position
 * has to come back. A generator would generate that case; this file writes it down so that a
 * reviewer can see why it is there.
 *
 * ### What the fixtures are
 *
 * Hand-built, for the reason `UiDocumentTest` builds its own rather than using the DSL: an
 * expected document that is derived from the same helpers the base document came from is
 * legible, and a helper with seventeen parameters would not be. Five nodes, two pages, two
 * app-scoped state declarations, and one node in each position a move can start or end at.
 */
class PatchRoundTripTest {

    // -----------------------------------------------------------------------------------
    // The table
    // -----------------------------------------------------------------------------------

    @Test
    fun `every op applies forwards and its inverse applies backwards`() {
        for (case in CASES) {
            val patch = Patch(listOf(case.op))

            val applied = PatchApplier.apply(patch, case.before)

            assertEquals(case.after, applied, "forward, ${case.name}")
            assertEquals(
                case.before,
                PatchApplier.apply(patch.inverse(), applied),
                "inverse, ${case.name}",
            )
        }
    }

    /**
     * The table is only worth what it covers, and a row that was forgotten leaves no trace.
     *
     * Eight distinct op classes is the whole of §26.2's set, so anything less than eight means
     * an op nobody round-tripped and the test above passed anyway.
     */
    @Test
    fun `the table names every op in the closed set`() {
        val covered = CASES.mapTo(mutableSetOf()) { it.op::class }

        assertEquals(8, covered.size, "the table covers ${covered.size} of §26.2's eight ops")
    }

    // -----------------------------------------------------------------------------------
    // Ops that interact: the reason the op order is reversed
    // -----------------------------------------------------------------------------------

    /**
     * Insert, then remove, then move, on one tree — undone in reverse.
     *
     * Every position here is a position in the document *as the op before it left it*, and that
     * is the only reason this works. `n_f` goes in at index 0, which pushes `n_a` from 0 to 1 —
     * and the removal says 1, and is only correct because the insert ran first. Removing `n_a`
     * then pushes `n_c` from 3 to 2, and the move says 2. Nothing recomputes anything, and
     * nothing could: every index was fixed when its op was planned, against a document state
     * that exists only as the result of the ops before it.
     *
     * The trace is asserted step by step because the end state alone cannot say *why* the
     * removal says 1 or the move says 2, and those two numbers are the whole subject.
     */
    @Test
    fun `the ops interact on one tree and the inverse undoes them in reverse order`() {
        val before = document()

        val insert = PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow()))
        val remove = PatchOp.RemoveSubtree(ALPHA, RemovedSubtree(ROOT, CHILDREN, 1, listOf(alphaRow())))
        val move = PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0))
        val patch = Patch(listOf(insert, remove, move))

        assertEquals(
            listOf(FALCON, ALPHA, BETA, GAMMA),
            PatchApplier.apply(Patch(listOf(insert)), before).nodes.require(ROOT).slots[CHILDREN],
            "the insert did not push n_a to the index the removal names",
        )
        assertEquals(
            listOf(FALCON, BETA, GAMMA),
            PatchApplier.apply(Patch(listOf(insert, remove)), before).nodes.require(ROOT).slots[CHILDREN],
            "the removal did not close the gap that moved n_c to index 2",
        )

        val after = PatchApplier.apply(patch, before)

        assertEquals(listOf(GAMMA, FALCON, BETA), after.nodes.require(ROOT).slots.getValue(CHILDREN))
        assertNull(after.nodes[ALPHA], "the removed node is still in the table")

        assertEquals(before, PatchApplier.apply(patch.inverse(), after), "the inverse did not restore it")
    }

    /**
     * Inverting each op is not enough — the list has to be reversed as well.
     *
     * The two lists below hold the same three ops and differ by nothing but their order. One is
     * what [Patch.inverse] returns and it restores the document. The other is what a plausible
     * implementation produces by inverting op by op and leaving the list alone, and the
     * applier refuses its very first op: the insertion's inverse says `n_f` was at index 0 of
     * `n_root`'s `children`, and index 0 now holds `n_c`, which the move put there.
     *
     * That refusal is the point of [PatchApplier]'s position checks. §5.6's normalized
     * document is built so that a wrong-order result is *invisible* to every existence check in
     * the engine — every row is still present and every slot still names real nodes — so the
     * applier comparing the id it expects against the id it finds is the only place in the
     * engine that can catch a patch applied to the wrong moment.
     *
     * The in-place list is written out by hand rather than derived from `patch.inverse().ops`,
     * and that is deliberate. `inverse().ops` is *already* reversed, so reading both lists off
     * the same call is how an earlier draft of this file came to assert that the correct order
     * fails. The two lists here are the claim: they differ only by order, and only one of them
     * applies.
     */
    @Test
    fun `inverting each op without reversing the list is refused`() {
        val before = document()
        val patch = Patch(
            listOf(
                PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow())),
                PatchOp.RemoveSubtree(ALPHA, RemovedSubtree(ROOT, CHILDREN, 1, listOf(alphaRow()))),
                PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0)),
            ),
        )
        val after = PatchApplier.apply(patch, before)

        val reversed = patch.inverse().ops
        val inPlace = listOf(
            PatchOp.RemoveSubtree(FALCON, RemovedSubtree(ROOT, CHILDREN, 0, listOf(falconRow()))),
            PatchOp.InsertNodes(ROOT, CHILDREN, 1, listOf(alphaRow())),
            PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 0), Placement(ROOT, CHILDREN, 2)),
        )

        assertEquals(reversed.toSet(), inPlace.toSet(), "the two lists hold different ops")
        assertEquals(listOf(GAMMA, FALCON, BETA), after.nodes.require(ROOT).slots.getValue(CHILDREN))

        assertEquals(before, PatchApplier.apply(Patch(reversed), after))

        assertFailsWith<IllegalArgumentException>(message = "order is part of the inverse, not a detail") {
            PatchApplier.apply(Patch(inPlace), after)
        }
    }

    /** The reversal itself, op by op, written out rather than derived from the patch. */
    @Test
    fun `the inverse of each op is its own negation and the list is reversed`() {
        val patch = Patch(
            listOf(
                PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow())),
                PatchOp.RemoveSubtree(ALPHA, RemovedSubtree(ROOT, CHILDREN, 1, listOf(alphaRow()))),
                PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0)),
            ),
        )

        assertEquals(
            listOf(
                // The move swaps its two placements, and nothing else.
                PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 0), Placement(ROOT, CHILDREN, 2)),
                // The removal becomes an insertion of the captured subtree at the captured index.
                PatchOp.InsertNodes(ROOT, CHILDREN, 1, listOf(alphaRow())),
                // The insertion becomes a removal whose root is the subtree's first element.
                PatchOp.RemoveSubtree(FALCON, RemovedSubtree(ROOT, CHILDREN, 0, listOf(falconRow()))),
            ),
            patch.inverse().ops,
        )

        // Double inversion is identity, which is what a redo stack depends on and what makes
        // the property above hold when undo and redo are the same code.
        assertEquals(patch, patch.inverse().inverse())
    }

    // -----------------------------------------------------------------------------------
    // touchedNodes
    // -----------------------------------------------------------------------------------

    /**
     * The set is the ids the ops name, in op order, and nothing else.
     *
     * Three claims, and each of them can be wrong in a way that matters. The **order** is
     * asserted as a list as well as a set, because two equal patches must iterate in the same
     * order on every target — that is §6.2's determinism reaching a collection whose equality
     * ignores order. The **absence of the parents** is asserted too: `n_root`'s slot changed
     * three times and `n_f` went into it, and the parent is still not in the set, because this
     * is the set of nodes an op names and an op names a parent only when it is writing to it.
     * And [PatchOp.PutRecord] contributes nothing, because it addresses a declaration and a
     * declaration is not a `NodeId`.
     */
    @Test
    fun `touched nodes are the ids the ops name, in op order`() {
        val patch = Patch(
            listOf(
                PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow())),
                PatchOp.RemoveSubtree(ALPHA, RemovedSubtree(ROOT, CHILDREN, 1, listOf(alphaRow()))),
                PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0)),
            ),
        )

        val touched = patch.touchedNodes

        assertEquals(setOf(FALCON, ALPHA, GAMMA), touched)
        assertEquals(listOf(FALCON, ALPHA, GAMMA), touched.toList(), "iteration is not deterministic")
        assertFalse(touched.contains(ROOT), "a parent whose slot changed is not a node an op names")
        assertFalse(touched.contains(BETA), "nor is one an op merely left alone")

        // A subtree touches every row it carries, not only its root — the descendants are
        // written into the table and are as new as the root is.
        assertEquals(
            setOf(DELTA, ECHO),
            Patch(listOf(PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(deltaRow(), echoRow()))))
                .touchedNodes,
        )

        assertTrue(Patch(emptyList()).touchedNodes.isEmpty(), "an empty patch touches nothing")
        assertTrue(
            Patch(listOf(PatchOp.PutRecord(RecordRef.Theme(BASE_THEME), null, null))).touchedNodes.isEmpty(),
            "a record op addresses a declaration, not a node",
        )
    }

    // -----------------------------------------------------------------------------------
    // The one canonicalisation, pinned rather than discovered
    // -----------------------------------------------------------------------------------

    /**
     * Undoing an insert into a slot the node did not have comes back to *no slot*, not to an
     * empty one — and a document that spelled the empty slot comes back without it.
     *
     * This is `PatchApplier`'s documented canonicalisation, asserted here because a limit that
     * is only prose is a limit the next reader re-discovers as a bug. See the applier's class
     * KDoc for why there is no alternative: §26.2 fixes `InsertNodes(parent, slot, index,
     * nodes)`, so no op can record that the slot key was absent, and the applier has to pick
     * one spelling of "this slot holds nothing".
     *
     * It picks the one that round-trips, because a node whose only child was just deleted is
     * the common case and it must come back exactly as it was. The price is that
     * `{"content":[]}` and `{}` converge — they mean the same thing to every reader, and they
     * differ only in bytes, so the two documents were already interchangeable and now they
     * *are* interchangeable rather than merely equivalent.
     */
    @Test
    fun `an emptied slot is dropped from the parent rather than left holding an empty list`() {
        val absent = document()
        val spelled = document(
            gamma = Node(id = GAMMA, type = ComponentType("m3.Text"), slots = mapOf(CONTENT to emptyList())),
        )
        val patch = Patch(listOf(PatchOp.InsertNodes(GAMMA, CONTENT, 0, listOf(falconRow()))))

        // The round trip that §26.2's property requires: the slot was absent, and it is
        // absent again rather than present-and-empty.
        val undone = PatchApplier.apply(patch.inverse(), PatchApplier.apply(patch, absent))

        assertEquals(absent, undone, "undoing an insert into an absent slot did not restore the document")
        assertEquals(emptyMap(), undone.nodes.require(GAMMA).slots, "an emptied slot key survived")

        // And the limit, stated as a fact rather than as a hope: the two spellings converge.
        val undoneSpelled = PatchApplier.apply(patch.inverse(), PatchApplier.apply(patch, spelled))

        assertEquals(absent, undoneSpelled, "the canonicalisation does not converge as documented")
    }

    // -----------------------------------------------------------------------------------
    // Refusal: a patch built for another document
    // -----------------------------------------------------------------------------------

    /**
     * The guards, one at a time.
     *
     * These are not defensive programming. Each of them is a patch that was planned against a
     * document this one is not, and applying it "successfully" would leave a document whose
     * nodes are all still present and whose *order* is wrong — which §5.6's normalization makes
     * invisible to every existence check in the engine. The order check is the interesting one:
     * nothing is missing, so only the applier can catch it.
     */
    @Test
    fun `an op that does not fit the document is refused`() {
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.InsertNodes(ROOT, CHILDREN, 9, listOf(falconRow())))
        }
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.InsertNodes(ROOT, CHILDREN, 0, emptyList()))
        }

        // The capture says `n_b` was at index 0. It is at index 1.
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.RemoveSubtree(BETA, RemovedSubtree(ROOT, CHILDREN, 0, listOf(betaRow(emptyList())))))
        }

        // The move says `n_c` is at index 0. It is at index 2.
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 0), Placement(ROOT, CHILDREN, 1)))
        }

        // A reference that names one declaration while carrying another.
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.PutRecord(RecordRef.Theme(DARK_THEME), null, Record.Theme(ThemeDecl(BASE_THEME, "Base"))))
        }

        // A reference and a record of two different kinds.
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.PutRecord(RecordRef.Page(DETAIL), null, Record.Theme(ThemeDecl(BASE_THEME, "Base"))))
        }

        // A removal naming a position that holds something else.
        assertFailsWith<IllegalArgumentException> {
            appliedTo(PatchOp.PutRecord(RecordRef.State(StateId("s_count"), 1), null, null))
        }

        // A node that is not in the table is `NodeTable.require`'s `IllegalStateException`,
        // deliberately not this file's `IllegalArgumentException`: "no such node" is the
        // table's own failure and rewriting it here would hide which of the two checks fired.
        assertFailsWith<IllegalStateException> {
            appliedTo(PatchOp.SetNodeName(NodeId("n_absent"), null, "x"))
        }
    }

    /** [op] applied to a fresh base document, for the refusal assertions above. */
    private fun appliedTo(op: PatchOp): UiDocument = PatchApplier.apply(Patch(listOf(op)), document())
}

/**
 * One row of the table: the document, the edit, and the document the edit produces.
 *
 * `after` is written out rather than computed, which is the whole discipline of the table. A
 * row whose `after` was `apply(op, before)` would assert that the applier agrees with itself.
 */
private class PatchCase(
    val name: String,
    val before: UiDocument,
    val op: PatchOp,
    val after: UiDocument,
)

// ---------------------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------------------

private val ROOT = NodeId("n_root")
private val ALPHA = NodeId("n_a")
private val BETA = NodeId("n_b")
private val GAMMA = NodeId("n_c")
private val LEAF = NodeId("n_leaf")
private val FALCON = NodeId("n_f")
private val DELTA = NodeId("n_d")
private val ECHO = NodeId("n_e")

private val CHILDREN = SlotName("children")
private val CONTENT = SlotName("content")

private val TEXT = PropertyKey("text")
private val ON_CLICK = EventKey("onClick")

private val PADDING = ModifierEntry(ModifierType("layout.padding"))
private val BACKGROUND = ModifierEntry(ModifierType("layout.background"))
private val CLICK = ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
private val ALPHA_TEXT = PropertyValue.Const(Value.Str("Alpha"))

private val HOME = PageId("p_home")
private val DETAIL = PageId("p_detail")
private val BASE_THEME = ThemeId("t_base")
private val DARK_THEME = ThemeId("t_dark")

private val COUNT = StateDecl(id = StateId("s_count"), name = "count", type = TypeRef.Str)
private val USER_NAME = StateDecl(id = StateId("s_name"), name = "userName", type = TypeRef.Str)

/** The same declaration, renamed. A rename is not an id change (§6.2), which is the point. */
private val DISPLAY_NAME = StateDecl(id = StateId("s_name"), name = "displayName", type = TypeRef.Str)

private fun alphaRow(
    text: String? = "Alpha",
    name: String? = "Alpha",
    modifiers: List<ModifierEntry> = listOf(PADDING),
    events: Map<EventKey, ActionSequence> = mapOf(ON_CLICK to CLICK),
): Node = Node(
    id = ALPHA,
    type = ComponentType("m3.Text"),
    name = name,
    props = if (text == null) emptyMap() else mapOf(TEXT to PropertyValue.Const(Value.Str(text))),
    modifiers = modifiers,
    events = events,
)

private fun betaRow(content: List<NodeId>): Node = Node(
    id = BETA,
    type = ComponentType("m3.Text"),
    slots = mapOf(CONTENT to content),
)

private fun gammaRow(): Node = Node(id = GAMMA, type = ComponentType("m3.Text"))
private fun leafRow(): Node = Node(id = LEAF, type = ComponentType("m3.Text"))
private fun falconRow(): Node = Node(id = FALCON, type = ComponentType("m3.Text"))

/** A two-node subtree, so "whole subtree, root first" is exercised rather than asserted. */
private fun deltaRow(): Node = Node(
    id = DELTA,
    type = ComponentType("core.Column"),
    slots = mapOf(CHILDREN to listOf(ECHO)),
)

private fun echoRow(): Node = Node(id = ECHO, type = ComponentType("m3.Text"))

private fun homePage(): Page = Page(id = HOME, name = "HomeScreen", route = "/home", root = ROOT)
private fun detailPage(): Page = Page(id = DETAIL, name = "DetailScreen", route = "/detail", root = ROOT)

/**
 * The base document, and the only place the table's "before" states come from.
 *
 * Every defaulted parameter is a field one of the eight ops writes, so a row of the table names
 * the one thing it changes and nothing else has to be repeated. A `null` node removes a row
 * from the table rather than blanking a field, because `RemoveSubtree` is about rows and a row
 * that is present but unreferenced would not be testing the same thing.
 */
private fun document(
    alpha: Node? = alphaRow(),
    beta: Node? = betaRow(listOf(LEAF)),
    betaContent: List<NodeId> = listOf(LEAF),
    gamma: Node? = gammaRow(),
    leaf: Node? = leafRow(),
    rootChildren: List<NodeId> = listOf(ALPHA, BETA, GAMMA),
    extra: List<Node> = emptyList(),
    pages: Map<PageId, Page> = mapOf(HOME to homePage()),
    appState: List<StateDecl> = listOf(COUNT, USER_NAME),
    themes: Map<ThemeId, ThemeDecl> = emptyMap(),
): UiDocument {
    var table = NodeTable.EMPTY.with(
        Node(id = ROOT, type = ComponentType("core.Column"), slots = mapOf(CHILDREN to rootChildren)),
    )
    for (node in listOfNotNull(alpha, beta, gamma, leaf) + extra) {
        table = table.with(node)
    }

    return UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", HOME),
        pages = pages,
        components = emptyMap(),
        nodes = table,
        appState = appState,
        themes = themes,
    )
}

private val BASE_THEME_DECL = ThemeDecl(BASE_THEME, "Base")

private val CASES: List<PatchCase> = listOf(
    PatchCase(
        name = "insert a leaf at the front",
        before = document(),
        op = PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow())),
        after = document(rootChildren = listOf(FALCON, ALPHA, BETA, GAMMA), extra = listOf(falconRow())),
    ),
    PatchCase(
        name = "insert a whole subtree, root first",
        before = document(),
        op = PatchOp.InsertNodes(ROOT, CHILDREN, 1, listOf(deltaRow(), echoRow())),
        after = document(
            rootChildren = listOf(ALPHA, DELTA, BETA, GAMMA),
            extra = listOf(deltaRow(), echoRow()),
        ),
    ),
    PatchCase(
        name = "insert into a slot that does not exist yet",
        before = document(),
        op = PatchOp.InsertNodes(GAMMA, CONTENT, 0, listOf(falconRow())),
        after = document(
            gamma = Node(
                id = GAMMA,
                type = ComponentType("m3.Text"),
                slots = mapOf(CONTENT to listOf(FALCON)),
            ),
            extra = listOf(falconRow()),
        ),
    ),
    PatchCase(
        name = "remove a subtree and the position it held",
        before = document(),
        op = PatchOp.RemoveSubtree(
            BETA,
            RemovedSubtree(ROOT, CHILDREN, 1, listOf(betaRow(listOf(LEAF)), leafRow())),
        ),
        after = document(beta = null, leaf = null, rootChildren = listOf(ALPHA, GAMMA)),
    ),
    PatchCase(
        name = "move within one slot",
        before = document(),
        op = PatchOp.MoveNode(GAMMA, Placement(ROOT, CHILDREN, 2), Placement(ROOT, CHILDREN, 0)),
        after = document(rootChildren = listOf(GAMMA, ALPHA, BETA)),
    ),
    PatchCase(
        name = "move between two parents",
        before = document(),
        op = PatchOp.MoveNode(ALPHA, Placement(ROOT, CHILDREN, 0), Placement(BETA, CONTENT, 1)),
        after = document(rootChildren = listOf(BETA, GAMMA), betaContent = listOf(LEAF, ALPHA)),
    ),
    PatchCase(
        name = "set a property",
        before = document(),
        op = PatchOp.SetProp(ALPHA, TEXT, ALPHA_TEXT, PropertyValue.Const(Value.Str("Zeta"))),
        after = document(alpha = alphaRow(text = "Zeta")),
    ),
    PatchCase(
        name = "clear a property",
        before = document(),
        op = PatchOp.SetProp(ALPHA, TEXT, ALPHA_TEXT, null),
        after = document(alpha = alphaRow(text = null)),
    ),
    PatchCase(
        name = "replace a modifier chain, order and all",
        before = document(),
        op = PatchOp.SetModifiers(ALPHA, listOf(PADDING), listOf(PADDING, BACKGROUND)),
        after = document(alpha = alphaRow(modifiers = listOf(PADDING, BACKGROUND))),
    ),
    PatchCase(
        name = "clear a handler",
        before = document(),
        op = PatchOp.SetHandler(ALPHA, ON_CLICK, CLICK, null),
        after = document(alpha = alphaRow(events = emptyMap())),
    ),
    PatchCase(
        name = "rename a node",
        before = document(),
        op = PatchOp.SetNodeName(ALPHA, "Alpha", "Renamed"),
        after = document(alpha = alphaRow(name = "Renamed")),
    ),
    PatchCase(
        name = "declare a theme",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.Theme(BASE_THEME), null, Record.Theme(BASE_THEME_DECL)),
        after = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL)),
    ),
    PatchCase(
        name = "remove a theme",
        before = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL)),
        op = PatchOp.PutRecord(RecordRef.Theme(BASE_THEME), Record.Theme(BASE_THEME_DECL), null),
        after = document(),
    ),
    PatchCase(
        name = "rename a state declaration in place",
        before = document(),
        op = PatchOp.PutRecord(
            RecordRef.State(USER_NAME.id, 1),
            Record.State(USER_NAME),
            Record.State(DISPLAY_NAME),
        ),
        after = document(appState = listOf(COUNT, DISPLAY_NAME)),
    ),
    PatchCase(
        name = "add a page",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.Page(DETAIL), null, Record.Page(detailPage())),
        after = document(pages = mapOf(HOME to homePage(), DETAIL to detailPage())),
    ),
)