package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
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
 * legible, and a helper with seventeen parameters would not be. Five nodes, every declaration
 * container, and one node in each position a move can start or end at.
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
            val restored = PatchApplier.apply(patch.inverse(), applied)

            // The tree is asserted before the document, and both are asserted. `NodeTable`
            // prints as `NodeTable(size=5)`, so a difference in the table reaches the message
            // as nothing at all — two rounds of this table were spent reading two documents
            // that printed identically. [treeOf] names the node instead, and the whole-document
            // assertion is kept because it is the property and the tree is only part of it.
            assertEquals(treeOf(case.after), treeOf(applied), "forward, ${case.name}: node tree")
            assertEquals(case.after, applied, "forward, ${case.name}: whole document")
            assertEquals(treeOf(case.before), treeOf(restored), "inverse, ${case.name}: node tree")
            assertEquals(case.before, restored, "inverse, ${case.name}: whole document")
        }
    }

    /**
     * The table is only worth what it covers, and a row that was forgotten leaves no trace.
     *
     * Eleven distinct op classes is the whole of the set — §26.2's eight, plus D30's `PutNodes`,
     * D31's `SetTheme` and §19.3's `SetComponentVersion` — so anything less than eleven means an
     * op nobody round-tripped and the test above passed anyway.
     *
     * **This count is a floor, not a ceiling, and it did not catch the twelfth op on its own.**
     * `SetComponentVersion` was added to [PatchOp] with no row here, and this test kept passing:
     * `covered.size` was still ten, because the table was still covering ten ops — the same ten.
     * A count answers "is the table as big as the set", never "does the table hold the set", and
     * those are different questions whenever the new op is one the table has not heard of. The
     * rows below are the real coverage; this number is the thing that notices when one is
     * dropped again.
     *
     * Counting classes and not ops is deliberate: [PatchOp.PutRecord] is one class with eight
     * payloads, and a table could satisfy a count of eleven while every one of its eight
     * references went untested. The rows for the two list containers and for the selection are
     * therefore checked by name as well — see the next test.
     */
    @Test
    fun `the table names every op in the closed set`() {
        val covered = CASES.mapTo(mutableSetOf()) { it.op::class }

        assertEquals(11, covered.size, "the table covers ${covered.size} of the set's eleven ops")
    }

    /**
     * Every `RecordRef` variant the table reaches, named rather than counted.
     *
     * [RecordRef] is sealed and `PatchApplier.applyPutRecord` switches on it, so a variant with
     * no row here is a container this table has never written or removed — and the count above
     * cannot see it, because every one of those ops is a `PutRecord` and they all share a class.
     *
     * The list is written out rather than derived from the hierarchy on purpose. A row is the
     * only thing that proves a container round-trips, and a list derived from the hierarchy would
     * grow by itself whenever `RecordRef` did — which is the opposite of what this test is for.
     * [RecordRef.HostFunction] is the variant D31 added, and it is why the table needed more
     * than the count to change: it is the second list container, so it is the second
     * implementation of a position protocol.
     */
    @Test
    fun `the table writes every declaration container, including the two lists`() {
        val covered: Set<RecordRef> = CASES.mapNotNullTo(mutableSetOf<RecordRef>()) { case ->
            (case.op as? PatchOp.PutRecord)?.ref
        }

        assertEquals(
            setOf(
                RecordRef.Page(DETAIL),
                RecordRef.Component(CARD_COMPONENT),
                RecordRef.Theme(BASE_THEME),
                RecordRef.Resource(LOGO),
                RecordRef.DataModel(USER),
                RecordRef.EnumType(ROLE),
                RecordRef.State(USER_NAME.id, 1),
                RecordRef.HostFunction(SUBMIT, 0),
            ),
            covered,
            "the table writes only these containers",
        )
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

        // The tenth op names no node at all, and the rule it follows is the one two lines
        // above rather than a second convention: an op contributes the `NodeId`s it names, and
        // this one names a field of the document. A sentinel id would put a value in this set
        // that names nothing a document holds, and every consumer would then have to know which
        // ids are real.
        assertTrue(
            Patch(listOf(PatchOp.SetTheme(BASE_THEME, DARK_THEME))).touchedNodes.isEmpty(),
            "the theme selection is a field of the document, not a node",
        )

        // The twelfth op follows the same rule and for the same reason, and stating it here is
        // what keeps "an op contributes the ids it names" a single rule rather than one rule per
        // document-level field. It matters more for this one than for `SetTheme`: a §19.3
        // upgrade patch is node ops *plus* these, so an editor asking "which nodes does this
        // change reach?" must still get the right answer, and the answer is not "none".
        assertTrue(
            Patch(listOf(PatchOp.SetComponentVersion(TEXT_TYPE, old = 1, new = 2))).touchedNodes.isEmpty(),
            "a recorded contract version is a field of the document, not a node",
        )

        // `PutNodes` names every row it writes, exactly as `InsertNodes` does, and a removal
        // with no position still names its root.
        assertEquals(
            setOf(DELTA, ECHO),
            Patch(listOf(PatchOp.PutNodes(listOf(deltaRow(), echoRow())))).touchedNodes,
        )
        assertEquals(
            setOf(FALCON),
            Patch(listOf(PatchOp.RemoveSubtree(FALCON, RemovedSubtree.unplaced(listOf(falconRow())))))
                .touchedNodes,
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

        // The same three refusals on the second list container, which is a second implementation
        // of the position protocol and would otherwise be the copy nobody ran.
        val withFunctions = document(hostFunctions = listOf(SUBMIT_DECL))
        assertFailsWith<IllegalArgumentException>(message = "a removal one past the end") {
            appliedTo(PatchOp.PutRecord(RecordRef.HostFunction(SUBMIT, 1), null, null), base = withFunctions)
        }
        assertFailsWith<IllegalArgumentException>(message = "a removal at another declaration's position") {
            appliedTo(
                PatchOp.PutRecord(RecordRef.HostFunction("refresh", 0), null, null),
                base = withFunctions,
            )
        }
        assertFailsWith<IllegalArgumentException>(message = "a reference and a record that disagree") {
            appliedTo(
                PatchOp.PutRecord(
                    RecordRef.HostFunction(SUBMIT, 0),
                    null,
                    Record.HostFunction(HostFunctionDecl("refresh", emptyList(), TypeRef.Str)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException>(message = "a reference and a record of two kinds") {
            appliedTo(PatchOp.PutRecord(RecordRef.HostFunction(SUBMIT, 0), null, Record.State(COUNT)))
        }

        // A selection naming a theme the document does not declare is *not* one of these, and
        // there is a test of its own below rather than an exception here.
        // Writing a row the table already holds, twice over: `NodeTable.with` *replaces*, so
        // neither of these would fail loudly on its own — the first would delete a live node
        // and the second would silently drop a row the op claimed to carry.
        assertFailsWith<IllegalArgumentException>(message = "PutNodes replaces rather than refuses") {
            appliedTo(PatchOp.PutNodes(listOf(alphaRow())))
        }
        assertFailsWith<IllegalArgumentException>(message = "the same id twice in one op") {
            appliedTo(PatchOp.PutNodes(listOf(falconRow(), falconRow())))
        }
        // An empty one cannot be built at all: its inverse is a removal whose root is the first
        // row, and there is no first row.
        assertFailsWith<IllegalArgumentException>(message = "an empty PutNodes has no inverse") {
            appliedTo(PatchOp.PutNodes(emptyList()))
        }

        // A node that is not in the table is `NodeTable.require`'s `IllegalStateException`,
        // deliberately not this file's `IllegalArgumentException`: "no such node" is the
        // table's own failure and rewriting it here would hide which of the two checks fired.
        assertFailsWith<IllegalStateException> {
            appliedTo(PatchOp.SetNodeName(NodeId("n_absent"), null, "x"))
        }
    }

    /**
     * A selection naming a theme the document does not declare is applied, not refused.
     *
     * **The absence of a check, asserted**, because an absent check is the one thing this file
     * cannot leave to a comment: every other op in the set is guarded by something, and a
     * well-meaning "fix" that made this one refuse would look like tightening rather than like a
     * change of meaning. It would break the ordinary case too — a document that adds a theme and
     * selects it in one patch — and it would make whether the patch applied depend on the order
     * the caller chose, which is the one property of a patch this file refuses to give up.
     *
     * §14.2 makes the resolution a total function of the id and `themes` together, and §17's
     * passes are where "this pairing is nonsense" is reported. An applier that reported it would
     * be a validator, and this object is not one.
     */
    @Test
    fun `a selection naming an undeclared theme is applied rather than refused`() {
        val before = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL), theme = BASE_THEME)
        val op = PatchOp.SetTheme(BASE_THEME, ThemeId("t_absent"))
        val applied = PatchApplier.apply(Patch(listOf(op)), before)

        assertEquals(BASE_THEME, before.theme, "the fixture starts on the theme it declares")
        assertEquals(ThemeId("t_absent"), applied.theme, "the selection was not applied")

        // And the inverse restores it, which is the reason `old` is on the op at all: an undo of
        // "I picked a theme this document has not declared" has to put the old selection back
        // rather than leave the document on the theme the user just rejected.
        assertEquals(
            PatchOp.SetTheme(ThemeId("t_absent"), BASE_THEME),
            Patch(listOf(op)).inverse().ops.single(),
            "the inverse did not swap the two selections",
        )
        assertEquals(before.theme, PatchApplier.apply(Patch(listOf(op)).inverse(), applied).theme)
    }

    /**
     * [op] applied to [base], for the refusal assertions above.
     *
     * The parameter is `base` rather than `document` because the default has to name the
     * top-level `document()` fixture, and a parameter called `document` would shadow it — in its
     * own default expression, of all places, where a reader cannot see which one resolved.
     */
    private fun appliedTo(op: PatchOp, base: UiDocument = document()): UiDocument =
        PatchApplier.apply(Patch(listOf(op)), base)
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

/**
 * The document's node table as `id(type) slot:[children]` lines, one per row, ascending by id.
 *
 * `NodeTable.toString()` is `NodeTable(size=5)` — the size and nothing else — so an assertion
 * about a whole [UiDocument] reports a difference inside the table as two documents that print
 * identically. That is not a small annoyance: it is how a broken row here survived two rounds
 * of review, because the message carried no evidence at all.
 *
 * Every row is printed, not only the ones reachable from a slot, because the failures worth
 * catching are the ones where a row came back when it should have gone, or did not come back
 * at all — and both of those are invisible in a reachable-only view.
 *
 * The order is the document's, not this function's: [dev.rotalex.lutter.model.doc.NodeTable.ids]
 * is ascending by id by contract (§18.2), and slots are sorted by name for the same reason, so
 * two runs on two targets produce the same string and a diff of two failures is readable.
 */
private fun treeOf(document: UiDocument): String = buildString {
    for (id in document.nodes.ids()) {
        val node = document.nodes.require(id)
        val slots = node.slots.entries
            .sortedBy { it.key.value }
            .joinToString(" ") { (slot, children) ->
                "$slot:" + children.joinToString(",", "[", "]") { it.value }
            }
        append(id.value).append('(').append(node.type.value).append(')').append(' ').append(slots).append('\n')
    }
}

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

/** The component the twelfth op's rows move a version for. Named so the rows can say `component`. */
private val TEXT_TYPE: ComponentType = ComponentType("m3.Text")

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
 * The base document, and the only place the table's "before" and "after" states come from.
 *
 * Every defaulted parameter is a field one of the eleven ops writes, so a row of the table names
 * the one thing it changes and nothing else has to be repeated. A `null` node removes a row
 * from the table rather than blanking a field, because `RemoveSubtree` is about rows and a row
 * that is present but unreferenced would not be testing the same thing.
 *
 * **The parameter order is load bearing.** `beta`'s default reads `betaContent`, which Kotlin
 * only permits for a parameter declared *earlier* — so `betaContent` comes first here and the
 * two cannot be swapped. They were, and `beta`'s default was a literal instead, which left
 * `betaContent` a parameter that was declared, was passed by a row, and was read by nothing:
 * the "move between two parents" row silently expressed an expectation the fixture then threw
 * away. A fixture parameter that no row can affect is worse than no parameter, because the row
 * reads as though it said something.
 *
 * The declaration containers were added for the same reason in the same order: D31 grew the
 * hierarchy by a container and a selection, and a table that cannot name them would keep
 * reporting eleven ops covered while three of the arms below were never executed.
 */
private fun document(
    alpha: Node? = alphaRow(),
    betaContent: List<NodeId> = listOf(LEAF),
    beta: Node? = betaRow(betaContent),
    gamma: Node? = gammaRow(),
    leaf: Node? = leafRow(),
    rootChildren: List<NodeId> = listOf(ALPHA, BETA, GAMMA),
    extra: List<Node> = emptyList(),
    pages: Map<PageId, Page> = mapOf(HOME to homePage()),
    components: Map<ComponentDeclId, ComponentDecl> = emptyMap(),
    themes: Map<ThemeId, ThemeDecl> = emptyMap(),
    theme: ThemeId? = null,
    resources: Map<ResourceId, ResourceDecl> = emptyMap(),
    dataModels: Map<DataModelId, DataModelDecl> = emptyMap(),
    enums: Map<TypeId, EnumTypeDecl> = emptyMap(),
    appState: List<StateDecl> = listOf(COUNT, USER_NAME),
    hostFunctions: List<HostFunctionDecl> = emptyList(),
    componentVersions: Map<ComponentType, Int> = emptyMap(),
): UiDocument {
    var table = NodeTable.EMPTY.with(
        Node(id = ROOT, type = ComponentType("core.Column"), slots = mapOf(CHILDREN to rootChildren)),
    )
    for (node in listOfNotNull(alpha, beta, gamma, leaf) + extra) {
        table = table.with(node)
    }

    return UiDocument(
        meta = DocumentMeta("Demo", componentVersions = componentVersions),
        app = AppSpec("com.example.demo", HOME),
        pages = pages,
        components = components,
        nodes = table,
        appState = appState,
        dataModels = dataModels,
        enums = enums,
        hostFunctions = hostFunctions,
        themes = themes,
        theme = theme,
        resources = resources,
    )
}

private val BASE_THEME_DECL = ThemeDecl(BASE_THEME, "Base")

/** A second theme, so "select one of two" is a choice and §14.2's single-theme rule is not. */
private val DARK_THEME_DECL = ThemeDecl(DARK_THEME, "Dark")

private val CARD_COMPONENT = ComponentDeclId("c_card")

/** A component whose body starts at [ROOT], which the table already holds. */
private val CARD_COMPONENT_DECL = ComponentDecl(
    id = CARD_COMPONENT,
    name = "Card",
    params = emptyList(),
    slots = emptyList(),
    root = ROOT,
)

private val LOGO = ResourceId("r_logo")
private val LOGO_DECL = ResourceDecl(id = LOGO, name = "Logo", kind = ResourceKind.Image, variants = emptyList())

private val USER = DataModelId("d_user")
private val USER_DECL = DataModelDecl(id = USER, name = "User")

private val ROLE = TypeId("t_role")
private val ROLE_DECL = EnumTypeDecl(id = ROLE, name = "Role", entries = listOf(EnumEntryDecl("Admin")))

/** §11.6's unit, as a table row would declare it: a name, no parameters, and a return. */
private val SUBMIT = "submit"
private val SUBMIT_DECL = HostFunctionDecl(name = SUBMIT, params = emptyList(), returns = TypeRef.Str)

private val CASES: List<PatchCase> = listOf(
    PatchCase(
        name = "insert a leaf at the front",
        before = document(),
        op = PatchOp.InsertNodes(ROOT, CHILDREN, 0, listOf(falconRow())),
        after = document(rootChildren = listOf(FALCON, ALPHA, BETA, GAMMA), extra = listOf(falconRow())),
    ),
    PatchCase(
        // D30's ninth op. No slot index, so there is nothing for `PatchApplier`'s position
        // checks to be wrong about — and the row exists to prove the *inverse* round trips,
        // since an unplaced removal is a form `RemoveSubtree` did not have before.
        name = "put rows in the table with no parent",
        before = document(),
        op = PatchOp.PutNodes(listOf(falconRow())),
        after = document(extra = listOf(falconRow())),
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
        name = "remove rows that were in no slot",
        before = document(extra = listOf(falconRow())),
        op = PatchOp.RemoveSubtree(FALCON, RemovedSubtree.unplaced(listOf(falconRow()))),
        after = document(),
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
    PatchCase(
        name = "declare a document component",
        before = document(),
        op = PatchOp.PutRecord(
            RecordRef.Component(CARD_COMPONENT),
            null,
            Record.Component(CARD_COMPONENT_DECL),
        ),
        after = document(components = mapOf(CARD_COMPONENT to CARD_COMPONENT_DECL)),
    ),
    PatchCase(
        name = "declare a resource",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.Resource(LOGO), null, Record.Resource(LOGO_DECL)),
        after = document(resources = mapOf(LOGO to LOGO_DECL)),
    ),
    PatchCase(
        name = "declare a data model",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.DataModel(USER), null, Record.DataModel(USER_DECL)),
        after = document(dataModels = mapOf(USER to USER_DECL)),
    ),
    PatchCase(
        name = "declare an enum type",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.EnumType(ROLE), null, Record.EnumType(ROLE_DECL)),
        after = document(enums = mapOf(ROLE to ROLE_DECL)),
    ),
    PatchCase(
        // D31's second list container. The row exists for the *position* rather than for the
        // declaration: writing at index 0 over nothing is an append, and its inverse has to
        // remove index 0 again rather than restore the list by appending.
        name = "declare a host function at the front of the list",
        before = document(),
        op = PatchOp.PutRecord(RecordRef.HostFunction(SUBMIT, 0), null, Record.HostFunction(SUBMIT_DECL)),
        after = document(hostFunctions = listOf(SUBMIT_DECL)),
    ),
    // -----------------------------------------------------------------------------------
    // D31's tenth op: the document's own pointer at one of its themes
    // -----------------------------------------------------------------------------------

    PatchCase(
        // §14.2's rule is a *selection*, so a null on either side is a value rather than an
        // absence — which is the whole reason this op exists instead of a `PutRecord`. The two
        // rows are separate rather than one row with both directions because an op with equal
        // values on both sides is a no-op and would prove nothing.
        name = "select one of the document's themes",
        before = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL, DARK_THEME to DARK_THEME_DECL)),
        op = PatchOp.SetTheme(null, DARK_THEME),
        after = document(
            themes = mapOf(BASE_THEME to BASE_THEME_DECL, DARK_THEME to DARK_THEME_DECL),
            theme = DARK_THEME,
        ),
    ),
    PatchCase(
        name = "stop selecting a theme",
        before = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL), theme = BASE_THEME),
        op = PatchOp.SetTheme(BASE_THEME, null),
        after = document(themes = mapOf(BASE_THEME to BASE_THEME_DECL)),
    ),
    // -----------------------------------------------------------------------------------
    // §19.3's twelfth op: the document's own record of what it was written against
    // -----------------------------------------------------------------------------------

    PatchCase(
        // **The node tree and the declarations are identical on both sides, and that is what
        // this row is for.** `SetTheme`'s two rows above are the other document-level ops and
        // they are in the same position: the only thing separating their `before` from their
        // `after` is one field of `UiDocument` that neither `treeOf` nor `NodeTable.toString()`
        // prints. `treeOf` emits ids, types and slots; `NodeTable` emits a size. A difference
        // confined to `meta` is invisible to both, so a failure here prints two identical trees
        // and a whole-document diff pointing at a field no renderer mentions — the exact shape
        // of failure this file's KDoc says cost two rounds of review, reached from a third
        // direction.
        //
        // `assertEquals(case.after, applied)` on the whole document is what actually carries
        // this row, and the `treeOf` assertions on either side are the part that proves the node
        // table really was untouched rather than merely unreported.
        name = "record a component's contract version as advanced",
        before = document(componentVersions = mapOf(TEXT_TYPE to 1)),
        op = PatchOp.SetComponentVersion(TEXT_TYPE, old = 1, new = 2),
        after = document(componentVersions = mapOf(TEXT_TYPE to 2)),
    ),
    PatchCase(
        // The other direction, and a separate row for the reason `SetTheme`'s two are: the
        // `null` here is **the removal of a map entry**, not a version of zero, and the inverse
        // of that removal has to put the entry back at v2 rather than appending it somewhere
        // else. A row with both directions in one op would be a no-op and would prove nothing.
        name = "stop recording a component's contract version",
        before = document(componentVersions = mapOf(TEXT_TYPE to 2)),
        op = PatchOp.SetComponentVersion(TEXT_TYPE, old = 2, new = null),
        after = document(),
    ),
)