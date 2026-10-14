package dev.rotalex.lutter.editing.diff

import dev.rotalex.lutter.editing.assertSameDocument
import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.BACKGROUND
import dev.rotalex.lutter.editing.command.BANNER
import dev.rotalex.lutter.editing.command.BETA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.CHILDREN
import dev.rotalex.lutter.editing.command.CLICK
import dev.rotalex.lutter.editing.command.COLUMN
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.DELTA
import dev.rotalex.lutter.editing.command.ECHO
import dev.rotalex.lutter.editing.command.FOXTROT
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.GOLF
import dev.rotalex.lutter.editing.command.HOME
import dev.rotalex.lutter.editing.command.HOTEL
import dev.rotalex.lutter.editing.command.INDIA
import dev.rotalex.lutter.editing.command.JULIET
import dev.rotalex.lutter.editing.command.KILO
import dev.rotalex.lutter.editing.command.LIMA
import dev.rotalex.lutter.editing.command.MINTED_ONE
import dev.rotalex.lutter.editing.command.MINTED_THREE
import dev.rotalex.lutter.editing.command.MINTED_TWO
import dev.rotalex.lutter.editing.command.ON_CLICK
import dev.rotalex.lutter.editing.command.PADDING
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT
import dev.rotalex.lutter.editing.command.TEXT_KEY
import dev.rotalex.lutter.editing.command.TOP_BAR
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.model.action.ActionSequence
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
import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PluginId
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `DocumentDiff.compute`, as the identity its own KDoc claims: `apply(compute(a, b), a) == b`.
 *
 * ### Why the round trip is the whole subject, and what it is not
 *
 * `PatchApplier` is already tested by `PatchRoundTripTest`, op by op, with a written-out
 * expected document. What is *not* tested anywhere is that the ops a diff **chooses** compose
 * into a patch that produces the second document — and that is a different failure, in a
 * different place. The applier can be right about every op it is given and the diff can still
 * emit the wrong set: a row that is removed from a parent that is also removed, an op that
 * addresses a position an earlier op moved, a declaration written into the wrong container. None
 * of those is an applier bug and every one of them is invisible until the two are composed.
 *
 * ### Two documents differing inside the node table print identically
 *
 * `NodeTable.toString()` is `NodeTable(size=13)`. An `assertEquals` over two `UiDocument`s
 * reports a difference inside the table as two byte-identical strings, and a diagnostic that
 * cannot name the difference is a diagnostic somebody has to re-derive by hand. This module has
 * lost three debugging rounds to exactly that, so every assertion here goes through
 * [assertSameDocument], which renders every row of every table through the record's own
 * `toString`. **A failure in this file should never print two identical documents.**
 *
 * ### What these tests cannot reach, and it is not the same thing as what they do reach
 *
 * Two ops of the ten never appear in a diff's output at all — [PatchOp.InsertNodes] and
 * [PatchOp.MoveNode] — because §5.6's normalization means a node that moved is a change to its
 * *parent*, and a rewritten row is written whole. `PatchRoundTripTest` covers both; the coverage
 * test below asserts their absence so that the gap is stated rather than assumed.
 *
 * And one pair of documents the identity does **not** hold for: a kept row whose `type`
 * changed. There is no op for it, so the last test in this file asserts the failure in the
 * terms the gap deserves rather than leaving it to be discovered.
 */
class DocumentDiffTest {

    // -----------------------------------------------------------------------------------
    // The round trip
    // -----------------------------------------------------------------------------------

    /**
     * One pair, differing in every way the vocabulary can express, and one assertion.
     *
     * The pair is built as "the document" and "the same document after an editing session", so
     * every difference is something a person could have done: a row added under a row, a page
     * and its root removed, a node dragged from one parent to another, a node renamed, a
     * property set and another cleared, a handler removed, a modifier chain extended, a state
     * declaration renamed and another deleted, a host function replaced and another dropped, and
     * the document's theme selection moved to a theme the session also declared.
     *
     * **The coverage test below is what makes this assertion worth anything.** A round trip over
     * a pair that quietly changed nothing is a round trip over two equal documents, and it
     * passes.
     */
    @Test
    fun `the round trip holds for a pair that differs in every way the vocabulary can express`() {
        assertRoundTrip(BEFORE, AFTER)
    }

    /**
     * The pairs that isolate one rule each, because a table's rows are cheaper to read than one
     * large pair's every-field.
     *
     * Each row is a pair of documents and nothing else: the diff is computed and applied and the
     * result compared. The fourth row is the ordering limit `DocumentDiff` documents rather
     * than solves — removing a theme and deselecting it in one patch leaves one intermediate
     * document out of step whichever order it chooses — and the last is the one the
     * implementation got wrong, which has a test of its own below.
     */
    @Test
    fun `the round trip holds for the pairs that isolate one rule each`() {
        for ((name, before, after) in ISOLATED) {
            assertRoundTrip(before, after, name)
        }
    }

    /**
     * A node dragged out of a subtree that is being deleted survives.
     *
     * This is the case the implementation got wrong, and it is wrong *silently*: the removal
     * captures the subtree, the subtree includes the node that was reparented out of it, the
     * applier deletes every row in the capture, and nothing writes the node back — because the
     * node is in the first document, so no group considers it added. The patch applies without
     * complaint and the result is missing a row that the second document still has a parent for,
     * which is the one class of corruption §6.2's structural pass cannot see, because every
     * reference in it resolves.
     *
     * The row is asserted present *and* asserted identical to the second document's row, because
     * "the node came back" is not the claim — "the node came back unchanged, and its parent
     * points at it" is.
     */
    @Test
    fun `a node reparented out of a subtree that is removed is not removed with it`() {
        val patch = DocumentDiff.compute(ISOLATED_BEFORE, ISOLATED_AFTER)
        val applied = PatchApplier.apply(patch, ISOLATED_BEFORE)

        assertSameDocument(ISOLATED_AFTER, applied, "the round trip")
        assertEquals(ISOLATED_AFTER.nodes.require(MOVED_OUT), applied.nodes.require(MOVED_OUT))
        assertEquals(
            1,
            patch.ops.count { it is PatchOp.RemoveSubtree && it.root == GAMMA },
            "the removed parent got more than one op, or its capture reached the survivor",
        )
    }

    /**
     * A kept row whose `type` changed is the one pair this cannot say, asserted as a fact.
     *
     * There is no op for a component type, so [DocumentDiff] emits nothing and the row keeps the
     * type it had. The patch is empty and the round trip does not hold — both asserted, because
     * a test that asserted only the empty patch would stop failing the day someone implemented
     * the eleventh op and had to update this file anyway.
     *
     * The day that happens, this test fails, and that is the point of writing it in the negative:
     * §5.7's component pasting edits a type today, and a diff that cannot see it is a diff that
     * will quietly drop an edit.
     */
    @Test
    fun `a kept row whose type changed is the one pair the diff cannot express`() {
        val before = isolated()
        val after = before.copy(nodes = before.nodes.with(textRow(ALPHA, type = CARD)))
        val patch = DocumentDiff.compute(before, after)

        assertEquals(Patch(emptyList()), patch, "the diff claims the documents are equal")
        assertEquals(
            TEXT,
            PatchApplier.apply(patch, before).nodes.require(ALPHA).type,
            "the type survived, so the identity above does not hold for this pair",
        )
        assertTrue(after != before, "the pair is supposed to differ")
    }

    // -----------------------------------------------------------------------------------
    // Determinism
    // -----------------------------------------------------------------------------------

    /**
     * Two calls, two lists, equal — the weakest half of §6.2's claim and the half a reader can
     * check by looking.
     *
     * Asserted on the op list rather than on a rendering, because `Patch` is a `data class` over
     * a `List` and list equality *is* order equality: a patch that emitted the same ops in a
     * different order on a second call would fail here rather than pass.
     */
    @Test
    fun `the same pair diffed twice gives equal patches`() {
        assertEquals(DocumentDiff.compute(BEFORE, AFTER), DocumentDiff.compute(BEFORE, AFTER))
    }

    /**
     * Two documents with the same content, built in a different insertion order, diff to the
     * same patch.
     *
     * **The order is reversed rather than merely retyped.** `mapOf` with more than one entry
     * builds a `LinkedHashMap` too, so writing the same pairs in the same order would produce a
     * document that *looks* like the test and has the iteration order it already had. The two
     * `assertEquals` calls are what make this a test rather than a tautology: they say the
     * documents really do hold the same content, so the third says something about the diff and
     * not about the fixtures. Without them, a difference in the patch could be explained by a
     * difference in the documents.
     *
     * §6.2's determinism is a claim about bytes, and this is where it is either true of this
     * file or not: a `PersistentHashMap`'s iteration order differs per target, so a diff that
     * walked a document map without sorting would produce different patches on a JVM and on
     * whatever else this module is ever built for.
     */
    @Test
    fun `two documents with the same content in different insertion order diff identically`() {
        // The same two documents as the main pair, written in the opposite order. Only the two
        // maps that hold more than one declaration are worth reversing; `NodeTable` needs nothing
        // because its own KDoc says the backing map's order is unspecified and `ids()` imposes
        // the ascending one, and the two lists are positions rather than orders.
        val beforeReordered = isolated(
            pages = linkedMapOf(NEW to page(NEW, PAGE_ROOT), HOME to page(HOME)),
            themes = linkedMapOf(DARK_THEME to darkTheme()),
            theme = DARK_THEME,
            enums = linkedMapOf(STATUS_TYPE to statusType()),
            appState = listOf(COUNT, USER_NAME, EXTRA),
            hostFunctions = listOf(hostFunction(REFRESH), hostFunction(SUBMIT), hostFunction(PING)),
            rootChildren = listOf(ALPHA, BETA, GAMMA, FOXTROT, GOLF),
            rows = beforeRows(),
            // Reversed for the same reason as the maps above: `componentVersions` is a map, and
            // a two-entry one built in a different order is the group most likely to be walked
            // in hash order by something added later.
            componentVersions = linkedMapOf(COLUMN to 1, TEXT to 1),
        )
        val afterReordered = isolated(
            pages = linkedMapOf(DETAIL to page(DETAIL, DETAIL_ROOT), HOME to page(HOME)),
            components = mapOf(CARD_COMPONENT to cardComponent()),
            themes = linkedMapOf(BASE_THEME to baseTheme(), DARK_THEME to darkTheme()),
            theme = BASE_THEME,
            resources = mapOf(LOGO to logo()),
            dataModels = mapOf(USER_MODEL to DataModelDecl(id = USER_MODEL, name = "User")),
            enums = linkedMapOf(COLOR_TYPE to colorType()),
            appState = listOf(COUNT, DISPLAY_NAME),
            hostFunctions = listOf(hostFunction(SUBMIT), hostFunction(SYNC)),
            rootChildren = listOf(ALPHA, BETA, FOXTROT, GOLF, GAMMA),
            rows = afterRows(),
            componentVersions = linkedMapOf(TEXT to 2, COLUMN to 2),
        )

        assertEquals(BEFORE, beforeReordered, "the two orders did not hold the same content")
        assertEquals(AFTER, afterReordered, "the two orders did not hold the same content")

        assertEquals(
            DocumentDiff.compute(BEFORE, AFTER),
            DocumentDiff.compute(beforeReordered, afterReordered),
            "the diff read the documents' map order",
        )
    }

    // -----------------------------------------------------------------------------------
    // Refusal
    // -----------------------------------------------------------------------------------

    /**
     * A pair that disagrees on `meta`'s identity or on `app` is refused, and the message names
     * both.
     *
     * Both fields in every message, deliberately: the refusal is about the *pair*, and naming
     * only the field that happened to differ would send a reader looking for one difference when
     * there are two more to find. The assertion is on the message rather than on the exception
     * type alone, because a message that does not say which fields is a message that has to be
     * re-derived from the source.
     *
     * `app` is compared on its package and start page rather than on the whole [AppSpec]:
     * `NavigationSpec` has `equals(other) = other is NavigationSpec` and a constant `hashCode`,
     * so two documents built by two separate calls are equal while their `toString` differs, and
     * printing one would put an identity hash in the message and a phantom difference in front
     * of whoever reads it.
     *
     * **`plugins` is a second `meta` case and not an accident of the fixture.** The refusal is
     * now `identityOf(old) == identityOf(new)`, which blanks `componentVersions` and compares
     * everything else, so `name` and `plugins` are what is left — and a rule that had narrowed
     * to `name` alone would have let a pair disagreeing about which plugins a document was
     * authored against through as a patch that reproduces every other field.
     *
     * **`forge.material3` and not a bare `m3`, because `PluginId` validates its own syntax.** A
     * namespaced id is two or more dotted segments (`IdSyntax.NAMESPACED`) — one segment is "a
     * simple id wearing a dot" — so a single-segment plugin id throws from its own constructor,
     * *before* this test's `assertFailsWith` is ever entered, and the failure looks like a broken
     * assertion rather than a fixture that could not be built. It is the same rule that makes
     * `ComponentType("m3.Text")` legal and `ComponentType("m3")` not.
     */
    @Test
    fun `a pair that disagrees on meta's identity or on app is refused and names both`() {
        val base = isolated()
        val diverged = listOf(
            "meta.name" to base.copy(meta = DocumentMeta("Other")),
            "meta.plugins" to base.copy(
                meta = base.meta.copy(plugins = listOf(PluginRequirement(PluginId("forge.material3"), "1.0"))),
            ),
            "app" to base.copy(app = AppSpec("com.example.other", HOME)),
        )

        for ((field, other) in diverged) {
            val failure = assertFailsWith<IllegalArgumentException>(message = "$field was not refused") {
                DocumentDiff.compute(base, other)
            }
            val message = failure.message.orEmpty()

            assertTrue(message.contains("meta"), "$field: the message does not name meta: $message")
            assertTrue(message.contains("app"), "$field: the message does not name app: $message")
        }

        // And the pair that agrees is not refused, which is the other half: a refusal that
        // fired on every pair would pass the loop above.
        assertEquals(Patch(emptyList()), DocumentDiff.compute(base, base))
    }

    /**
     * A pair that differs only in `componentVersions` is no longer refused, and is one op per
     * moved entry.
     *
     * This is the half of D31's rule that §19.3's op narrowed. The record of what a document was
     * written against is not that document's identity — it is stale the moment a plugin ships —
     * so a diff that refused to compare it would refuse to describe the difference between a
     * document and the same document after an upgrade, which is the one pair a collaboration
     * round trip has to be able to describe.
     *
     * All four directions are in the fixture because `componentVersions` is a `Map` and the two
     * nulls are two different claims: an entry that moved, one that was added, one that was
     * dropped, and one that did not move at all.
     *
     * **The order is `core.Column`, `m3.Banner`, `m3.Text`, and that is the type's string form
     * rather than anything about its role.** `BANNER` is `m3.Banner`, not `core.Banner`: the
     * planner fixtures give it an `m3` namespace like every other Material component, so `c` of
     * `core` sorts before `m` of `m3` and the column comes first. Writing the expectation from
     * the fixture's *role* rather than its *value* is how this row got written backwards the
     * first time, with a comment to match that named a `core.Banner` no fixture declares.
     */
    @Test
    fun `a pair that differs only in componentVersions is one op per entry, in both null directions`() {
        val before = isolated(componentVersions = mapOf(TEXT to 1, COLUMN to 1))
        val after = isolated(componentVersions = mapOf(TEXT to 2, BANNER to 1))

        val patch = DocumentDiff.compute(before, after)

        assertEquals(
            listOf(
                // Ascending by the type's string form: "core.Column" < "m3.Banner" < "m3.Text".
                PatchOp.SetComponentVersion(COLUMN, old = 1, new = null),
                PatchOp.SetComponentVersion(BANNER, old = null, new = 1),
                PatchOp.SetComponentVersion(TEXT, old = 1, new = 2),
            ),
            patch.ops,
            "the recorded versions did not come out as one op per moved entry",
        )
        assertSameDocument(after, PatchApplier.apply(patch, before), "the round trip for the record")
        assertSameDocument(before, PatchApplier.apply(patch.inverse(), after), "and its inverse")
    }

    // -----------------------------------------------------------------------------------
    // The ordering rule, pinned by watching something fail
    // -----------------------------------------------------------------------------------

    /**
     * A rewritten row is removed before it is replaced, and the other order round does not
     * merely differ — it is refused.
     *
     * This is the one ordering constraint in `DocumentDiff` that a test can pin by watching an
     * exception, and the reason it is worth a test of its own is that the constraint lives in
     * the applier's guard rather than in any rule this file could state. `applyPutNodes` refuses
     * an id the table already holds, so writing the replacement first is not a silent
     * replacement of a live node — it is an `IllegalArgumentException` naming the id.
     *
     * **Everything else about the order is free, and that is why this test is narrow.** The five
     * group boundaries touch disjoint ids, containers and positions: removals and additions
     * cannot collide because §6.2 never reuses an id, a rewrite and a field edit cannot collide
     * because a rewritten row is skipped by the field pass, and two record containers cannot see
     * each other at all. The next test pins the sequence itself, as an assertion about the ops
     * rather than about the applier.
     */
    @Test
    fun `a rewritten row is removed before it is replaced, and the other order is refused`() {
        val before = isolated(
            rootChildren = listOf(ALPHA, BETA),
            rows = listOf(textRow(ALPHA), textRow(BETA)),
        )
        val after = before.copy(
            nodes = before.nodes.with(
                Node(id = ROOT, type = COLUMN, slots = mapOf(CHILDREN to listOf(BETA, ALPHA))),
            ),
        )

        val ops = DocumentDiff.compute(before, after).ops
        assertEquals(2, ops.size, "a pure reorder is one rewrite pair and nothing else")

        val removal = assertNotNull(ops[0] as? PatchOp.RemoveSubtree)
        val replacement = assertNotNull(ops[1] as? PatchOp.PutNodes)

        assertEquals(ROOT, removal.root)
        assertEquals(ROOT, replacement.nodes.single().id)

        // The chosen order produces the second document.
        assertEquals(after, PatchApplier.apply(Patch(listOf(removal, replacement)), before))

        // And the same two ops, the other way round, are refused rather than applied.
        assertFailsWith<IllegalArgumentException>(
            message = "replacing a live row was not refused",
        ) {
            PatchApplier.apply(Patch(listOf(replacement, removal)), before)
        }
    }

    /**
     * The six groups run in the documented order, checked on the ops themselves.
     *
     * One representative per group, and their positions must increase. Each representative is
     * picked to be unambiguous — the `PutNodes` of the additions group is the one whose first
     * row is the added page's root, not any of the rewrites — because a representative that
     * could be one of two ops would assert nothing about the boundary between them.
     *
     * The last position is asserted against the whole list rather than against the next group,
     * because "the theme selection is last" is a stronger claim than "it comes after the
     * records" and it is the one `DocumentDiff`'s KDoc makes.
     */
    @Test
    fun `the op groups run in the order the diff documents`() {
        val ops = DocumentDiff.compute(BEFORE, AFTER).ops

        val positions = listOf(
            "removals" to ops.indexOfFirst { it is PatchOp.RemoveSubtree && it.root == ECHO },
            "additions" to ops.indexOfFirst { it is PatchOp.PutNodes && it.nodes.first().id == DETAIL_ROOT },
            "rewrites" to ops.indexOfFirst { it is PatchOp.RemoveSubtree && it.root == ROOT },
            "field edits" to ops.indexOfFirst { it is PatchOp.SetProp && it.node == ALPHA },
            "records" to ops.indexOfFirst { it is PatchOp.PutRecord && it.ref is RecordRef.Component },
            "component versions" to ops.indexOfFirst { it is PatchOp.SetComponentVersion },
            "the theme selection" to ops.indexOfFirst { it is PatchOp.SetTheme },
        )

        for ((group, at) in positions) {
            assertTrue(at >= 0, "no op stands for the $group group: $positions")
        }

        assertEquals(
            positions.map { it.second },
            positions.map { it.second }.sorted(),
            "the groups ran out of order: $positions",
        )
        assertEquals(
            positions.last().second,
            ops.lastIndex,
            "the theme selection is emitted last: $positions",
        )
    }

    // -----------------------------------------------------------------------------------
    // What the pair covers, asserted so the round trip cannot quietly shrink
    // -----------------------------------------------------------------------------------

    /**
     * The pair reaches every op a diff can emit, every container, and nothing it cannot.
     *
     * **This is the test that keeps the round trip honest.** Without it, a fixture that stopped
     * exercising, say, `SetModifiers` would leave the round-trip test passing over a smaller
     * pair — the failure mode this repository has already paid for twice in this area.
     *
     * The absence assertions are the other half and they are load-bearing too: `InsertNodes` and
     * `MoveNode` are not missing from this file by accident, they are structurally unreachable
     * from a diff, and an assertion is what stops somebody reading "eight of ten ops covered"
     * as a gap to close. `SetComponentVersion` is the twelfth and it is here for the opposite
     * reason: §19.3's upgrade writes one, so a diff that could not see it would report two
     * documents that are the same tree and a different answer to "what was this written
     * against" — and the *same tree* is what makes it worth asserting rather than assuming.
     */
    @Test
    fun `the pair exercises every op a diff can emit and every declaration container`() {
        val ops = DocumentDiff.compute(BEFORE, AFTER).ops
        val classes = ops.mapTo(mutableSetOf()) { it::class }

        for (emitted in listOf(
            PatchOp.RemoveSubtree::class,
            PatchOp.PutNodes::class,
            PatchOp.SetNodeName::class,
            PatchOp.SetProp::class,
            PatchOp.SetModifiers::class,
            PatchOp.SetHandler::class,
            PatchOp.PutRecord::class,
            PatchOp.SetTheme::class,
            PatchOp.SetComponentVersion::class,
        )) {
            assertTrue(emitted in classes, "the pair never emits a ${emitted.simpleName}")
        }

        for (unreachable in listOf(PatchOp.InsertNodes::class, PatchOp.MoveNode::class)) {
            assertFalse(
                unreachable in classes,
                "a diff never emits ${unreachable.simpleName}: §5.6 puts a node's position in " +
                    "its parent's slot, so a move is a change to the parent",
            )
        }

        val containers = ops.filterIsInstance<PatchOp.PutRecord>().mapTo(mutableSetOf()) { it.ref }

        assertEquals(
            setOf(
                RecordRef.Page(DETAIL),
                RecordRef.Page(NEW),
                RecordRef.Component(CARD_COMPONENT),
                RecordRef.Theme(BASE_THEME),
                RecordRef.Resource(LOGO),
                RecordRef.DataModel(USER_MODEL),
                RecordRef.EnumType(COLOR_TYPE),
                // `t_status` is in `old` and not in `new`, so it is written with `new = null`.
                // A removal is a write like any other: without this the round trip would leave
                // the enum in place, and this row is the only thing that would have noticed.
                RecordRef.EnumType(STATUS_TYPE),
                RecordRef.State(DISPLAY_NAME.id, 1),
                RecordRef.State(EXTRA.id, 2),
                RecordRef.HostFunction(SUBMIT, 0),
                RecordRef.HostFunction(SYNC, 1),
                RecordRef.HostFunction(PING, 2),
            ),
            containers,
            "the pair does not write every container",
        )
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    /** `apply(compute(before, after), before) == after`, reported the way this module reports. */
    private fun assertRoundTrip(before: UiDocument, after: UiDocument, name: String = "the pair") {
        assertSameDocument(
            after,
            PatchApplier.apply(DocumentDiff.compute(before, after), before),
            "compute/apply did not reproduce the second document for $name",
        )
    }
}

// ---------------------------------------------------------------------------------------
// The documents
// ---------------------------------------------------------------------------------------

/**
 * The shared document builder, and the reason it takes eleven defaulted arguments.
 *
 * A builder is the wrong shape for a pair the reader is meant to see — the second document of
 * the main pair would be a wall of named arguments that says nothing about what changed. So the
 * differences are written out at the call site (see [BEFORE] and [AFTER], which are literal
 * documents rather than copies of one another) and this builder exists only for the rows.
 *
 * Every default is empty, so a caller that forgets an argument gets a document with nothing in
 * that container rather than a document silently resembling another case.
 */
private fun isolated(
    pages: Map<PageId, Page> = mapOf(HOME to page(HOME)),
    components: Map<ComponentDeclId, ComponentDecl> = emptyMap(),
    themes: Map<ThemeId, ThemeDecl> = emptyMap(),
    theme: ThemeId? = null,
    resources: Map<ResourceId, ResourceDecl> = emptyMap(),
    dataModels: Map<DataModelId, DataModelDecl> = emptyMap(),
    enums: Map<TypeId, EnumTypeDecl> = emptyMap(),
    appState: List<StateDecl> = emptyList(),
    hostFunctions: List<HostFunctionDecl> = emptyList(),
    rootChildren: List<NodeId> = listOf(ALPHA, BETA),
    rows: List<Node> = listOf(textRow(ALPHA), textRow(BETA)),
    componentVersions: Map<ComponentType, Int> = emptyMap(),
): UiDocument {
    var table: NodeTable = NodeTable.EMPTY.with(
        Node(id = ROOT, type = COLUMN, slots = mapOf(CHILDREN to rootChildren)),
    )
    for (row in rows) {
        table = table.with(row)
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

/**
 * The vocabulary this test adds on top of `PlannerFixtures.kt`.
 *
 * Only what the planner fixtures do not already declare: an event and a property key for the
 * field edits, and the ids of the declarations the session adds. Component types, slot names and
 * node ids come from `PlannerFixtures` and are imported, because a second `Column` in this
 * module is a thing a reviewer would have to notice is not the same one.
 */
private val ON_TAP = EventKey("onTap")
private val COLOR_KEY = PropertyKey("color")
private val RENAMED_TEXT = PropertyValue.Const(Value.Str("Zeta"))
private val ADDED_COLOR = PropertyValue.Const(Value.Str("blue"))
private val CLEARED_COLOR = PropertyValue.Const(Value.Str("red"))

private val NEW = PageId("p_new")
private val DETAIL = PageId("p_detail")
private val CARD_COMPONENT = ComponentDeclId("c_card")
private val DARK_THEME = ThemeId("t_dark")
private val BASE_THEME = ThemeId("t_base")
private val LOGO = ResourceId("r_logo")
private val USER_MODEL = DataModelId("d_user")
private val STATUS_TYPE = TypeId("t_status")
private val COLOR_TYPE = TypeId("t_color")

/** `MINTED_ONE` is `GAMMA`'s child in both documents, so `GAMMA`'s only change is an addition. */
private val GAMMA_CHILD = MINTED_ONE
private val ADDED_CHILD = NodeId("n_5")
private val PAGE_ROOT = MINTED_TWO
private val DETAIL_ROOT = MINTED_THREE
private val COMPONENT_ROOT = NodeId("n_6")
private val MOVED_OUT = NodeId("n_7")

private fun page(id: PageId, root: NodeId = ROOT): Page = Page(
    id = id,
    name = if (id == HOME) "HomeScreen" else "SecondScreen",
    route = if (id == HOME) "/home" else "/second",
    root = root,
)

private fun darkTheme(): ThemeDecl = ThemeDecl(DARK_THEME, "Dark")
private fun baseTheme(): ThemeDecl = ThemeDecl(BASE_THEME, "Base")

private fun statusType(): EnumTypeDecl = EnumTypeDecl(id = STATUS_TYPE, name = "Status")

private fun colorType(): EnumTypeDecl =
    EnumTypeDecl(id = COLOR_TYPE, name = "Color", entries = listOf(EnumEntryDecl("Red")))

private fun logo(): ResourceDecl = ResourceDecl(
    id = LOGO,
    name = "Logo",
    kind = ResourceKind.Image,
    variants = emptyList(),
)

private fun cardComponent(): ComponentDecl = ComponentDecl(
    id = CARD_COMPONENT,
    name = "Card",
    params = emptyList(),
    slots = emptyList(),
    root = COMPONENT_ROOT,
)

private val COUNT = StateDecl(id = StateId("s_count"), name = "count", type = TypeRef.Str)
private val USER_NAME = StateDecl(id = StateId("s_name"), name = "userName", type = TypeRef.Str)
private val DISPLAY_NAME = StateDecl(id = StateId("s_name"), name = "displayName", type = TypeRef.Str)
private val EXTRA = StateDecl(id = StateId("s_extra"), name = "extra", type = TypeRef.Str)

private const val REFRESH = "refresh"
private const val SUBMIT = "submit"
private const val SYNC = "sync"
private const val PING = "ping"

private fun hostFunction(name: String): HostFunctionDecl =
    HostFunctionDecl(name = name, params = emptyList(), returns = TypeRef.Str)

private fun textRow(
    id: NodeId,
    type: ComponentType = TEXT,
    name: String? = null,
    props: Map<PropertyKey, PropertyValue> = emptyMap(),
    modifiers: List<ModifierEntry> = emptyList(),
    slots: Map<SlotName, List<NodeId>> = emptyMap(),
    events: Map<EventKey, ActionSequence> = emptyMap(),
): Node = Node(
    id = id,
    type = type,
    name = name,
    props = props,
    modifiers = modifiers,
    slots = slots,
    events = events,
)

private fun columnRow(id: NodeId, children: List<NodeId>): Node =
    Node(id = id, type = COLUMN, slots = mapOf(CHILDREN to children))

/**
 * The rows the main pair starts from, one per line, each line naming the difference its
 * counterpart makes on the other side.
 */
private fun beforeRows(): List<Node> = listOf(
    textRow(ALPHA, name = "Alpha", props = mapOf(COLOR_KEY to CLEARED_COLOR), modifiers = listOf(PADDING)),
    columnRow(BETA, listOf(DELTA, ECHO)),
    columnRow(GAMMA, listOf(GAMMA_CHILD)),
    Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(HOTEL)), events = mapOf(ON_TAP to CLICK)),
    Node(id = GOLF, type = BANNER, slots = mapOf(TOP_BAR to listOf(INDIA))),
    textRow(DELTA),
    textRow(ECHO),
    textRow(HOTEL, modifiers = listOf(PADDING)),
    textRow(INDIA, props = mapOf(COLOR_KEY to CLEARED_COLOR)),
    columnRow(JULIET, listOf(KILO, LIMA)),
    textRow(KILO),
    textRow(LIMA, name = "Spare"),
    textRow(GAMMA_CHILD),
    textRow(PAGE_ROOT),
)

/** The same rows after the editing session: a reader comparing the two lists sees the diff. */
private fun afterRows(): List<Node> = listOf(
    textRow(
        ALPHA,
        name = "Renamed",
        props = mapOf(COLOR_KEY to ADDED_COLOR, TEXT_KEY to RENAMED_TEXT),
        modifiers = listOf(BACKGROUND),
        events = mapOf(ON_CLICK to CLICK),
    ),
    columnRow(BETA, listOf(DELTA)),
    columnRow(GAMMA, listOf(GAMMA_CHILD, ADDED_CHILD)),
    Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(HOTEL))),
    // The emptied slot is spelled as an empty list on purpose: a rewrite writes `new`'s row
    // verbatim, so no op here is asked whether the key existed, and the round trip depends on
    // that rather than on the applier having an opinion about it.
    Node(id = GOLF, type = BANNER, slots = mapOf(TOP_BAR to emptyList())),
    textRow(DELTA),
    textRow(HOTEL, modifiers = listOf(PADDING, BACKGROUND)),
    textRow(INDIA),
    columnRow(JULIET, listOf(KILO, LIMA, INDIA)),
    // `FOXTROT`'s handler is cleared and this one is added, because the two null directions of
    // `SetHandler` are two different claims: "there was nothing here" and "there was something
    // and it is gone" are not the same edit, and only one of them is exercised by clearing.
    textRow(KILO, events = mapOf(ON_TAP to CLICK)),
    textRow(LIMA),
    textRow(GAMMA_CHILD),
    textRow(ADDED_CHILD),
    textRow(DETAIL_ROOT),
    textRow(COMPONENT_ROOT),
)

private val BEFORE: UiDocument = isolated(
    pages = mapOf(HOME to page(HOME), NEW to page(NEW, PAGE_ROOT)),
    themes = mapOf(DARK_THEME to darkTheme()),
    theme = DARK_THEME,
    enums = mapOf(STATUS_TYPE to statusType()),
    appState = listOf(COUNT, USER_NAME, EXTRA),
    hostFunctions = listOf(hostFunction(REFRESH), hostFunction(SUBMIT), hostFunction(PING)),
    rootChildren = listOf(ALPHA, BETA, GAMMA, FOXTROT, GOLF),
    rows = beforeRows(),
    // The one pair of documents in this file that differ on `meta`, and they differ on exactly
    // the one field of it a `PatchOp` can edit — which is what §19.3's upgrade produces and what
    // `SpecMigrationPassTest` pins the patch for.
    componentVersions = mapOf(TEXT to 1, COLUMN to 1),
)

private val AFTER: UiDocument = isolated(
    pages = mapOf(HOME to page(HOME), DETAIL to page(DETAIL, DETAIL_ROOT)),
    components = mapOf(CARD_COMPONENT to cardComponent()),
    themes = mapOf(DARK_THEME to darkTheme(), BASE_THEME to baseTheme()),
    theme = BASE_THEME,
    resources = mapOf(LOGO to logo()),
    dataModels = mapOf(USER_MODEL to DataModelDecl(id = USER_MODEL, name = "User")),
    enums = mapOf(COLOR_TYPE to colorType()),
    appState = listOf(COUNT, DISPLAY_NAME),
    hostFunctions = listOf(hostFunction(SUBMIT), hostFunction(SYNC)),
    rootChildren = listOf(ALPHA, BETA, FOXTROT, GOLF, GAMMA),
    rows = afterRows(),
    componentVersions = mapOf(COLUMN to 2, TEXT to 2),
)

/** The reparent pair, named once because two tests and the table below all need it. */
private val ISOLATED_BEFORE: UiDocument = isolated(
    rootChildren = listOf(ALPHA, BETA, GAMMA),
    rows = listOf(
        textRow(ALPHA),
        columnRow(BETA, listOf(DELTA)),
        columnRow(GAMMA, listOf(MOVED_OUT)),
        textRow(DELTA),
        textRow(MOVED_OUT),
    ),
)

private val ISOLATED_AFTER: UiDocument = isolated(
    rootChildren = listOf(ALPHA, BETA),
    rows = listOf(
        textRow(ALPHA),
        columnRow(BETA, listOf(DELTA, MOVED_OUT)),
        textRow(DELTA),
        textRow(MOVED_OUT),
    ),
)

/**
 * The pairs that isolate one rule, each a different reason the diff could get it wrong.
 *
 * A row is three values because that is all a pair is, and the names are the claims: the first
 * three are the directions §14.2's selection can take, the fourth is the ordering limit the
 * implementation documents and the last is the capture that is a subtree walk and not a removal.
 */
private val ISOLATED: List<Triple<String, UiDocument, UiDocument>> = listOf(
    Triple(
        "a theme selected",
        isolated(themes = mapOf(DARK_THEME to darkTheme())),
        isolated(themes = mapOf(DARK_THEME to darkTheme()), theme = DARK_THEME),
    ),
    Triple(
        "a theme deselected",
        isolated(themes = mapOf(DARK_THEME to darkTheme()), theme = DARK_THEME),
        isolated(themes = mapOf(DARK_THEME to darkTheme())),
    ),
    Triple(
        "a theme declared and selected in the same patch",
        isolated(),
        isolated(themes = mapOf(DARK_THEME to darkTheme()), theme = DARK_THEME),
    ),
    Triple(
        "the selected theme removed and deselected at once",
        isolated(themes = mapOf(DARK_THEME to darkTheme()), theme = DARK_THEME),
        isolated(),
    ),
    Triple("a node reparented out of a removed subtree", ISOLATED_BEFORE, ISOLATED_AFTER),
)
