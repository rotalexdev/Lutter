package dev.rotalex.lutter.editing.migration

import dev.rotalex.lutter.editing.assertSameDocument
import dev.rotalex.lutter.editing.command.ALPHA
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
import dev.rotalex.lutter.editing.command.HOTEL
import dev.rotalex.lutter.editing.command.INDIA
import dev.rotalex.lutter.editing.command.JULIET
import dev.rotalex.lutter.editing.command.KILO
import dev.rotalex.lutter.editing.command.LIMA
import dev.rotalex.lutter.editing.command.ON_CLICK
import dev.rotalex.lutter.editing.command.PADDING
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT
import dev.rotalex.lutter.editing.command.TOP_BAR
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.migration.SpecMigration
import dev.rotalex.lutter.schema.migration.SpecMigrationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §19.3's `SpecMigrationPass`, asserted on the claims the section makes for it.
 *
 * ### What is being tested, in one line each
 *
 *  * **Reviewable.** A multi-version gap across several component types comes out as ONE patch,
 *    in a fixed op order, and applying it produces a document whose `componentVersions` are the
 *    registry's own `latest`.
 *  * **Undoable.** `apply(patch.inverse(), apply(patch, d)) == d`. §19.3 says the editor can take
 *    an upgrade back as a single transaction, and that identity is the whole of the claim.
 *  * **Total and honest.** An uncovered gap throws rather than skipping; a current document gets
 *    the empty patch rather than an error.
 *
 * ### Two documents differing inside the node table print identically
 *
 * `NodeTable.toString()` is `NodeTable(size=13)`, so an `assertEquals` over two `UiDocument`s
 * reports a difference inside the table as two byte-identical strings. That has cost this module
 * three debugging rounds already, so every document assertion here goes through
 * [assertSameDocument], which renders every row of every table through the record's own
 * `toString`. **A failure in this file should never print two identical documents.**
 *
 * ### The op order this file pins
 *
 * "component type, then node, then chain step, then the record" is an assertion and not a
 * description. [the multi-version gap across several component types is one patch] holds it up as
 * a list of twenty short strings: a reader of a failed expectation sees exactly which boundary
 * moved, which a round trip alone could never say — a round trip fails the same way whatever
 * order the ops came out in.
 */
class SpecMigrationPassTest {

    // -----------------------------------------------------------------------------------
    // Reviewable
    // -----------------------------------------------------------------------------------

    /**
     * A two-step gap on `m3.Text`, a one-step gap on `core.Column`, and one recorded component
     * that is already current — one document, one patch.
     *
     * The fixture is chosen so that four different facts are on the table at once: two components
     * with **different** gap lengths, one recorded component with **no** gap, and rows of a type
     * the document never recorded at all (`core.Banner`). A fixture with a single component and a
     * single step would pass over every question the KDoc answers.
     *
     * The op list is asserted rather than the document alone, because the document is the
     * *result* and the list is the *patch* — and §19.3's claim is about the second one. Twenty
     * entries, each `"address:tag"`, in the order the pass emits them.
     */
    @Test
    fun `the multi-version gap across several component types is one patch`() {
        val patch = SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS)

        assertEquals(
            listOf(
                // `core.Card` is recorded at v2 and targeted at v2: nothing at all, not a no-op.
                // `core.Column`'s single step, nodes ascending. n_k and n_l hold no slots, so the
                // step changes them by nothing and emits nothing — which is why they are absent
                // from a list that is otherwise one pair per `core.Column` row with children.
                "n_b:removeSubtree", "n_b:putNodes",
                "n_j:removeSubtree", "n_j:putNodes",
                "n_root:removeSubtree", "n_root:putNodes",
                // `m3.Text`'s two steps, nodes ascending, each step's ops before the next step's.
                // Within one step the keys are ascending, which is why the v2-to-v3 pair reads
                // `body` before `text`: "body" sorts before "text", and the two ops are a clear
                // and a set rather than a move, so the order between them does not matter.
                "n_a:setProp", "n_a:setProp", "n_a:setProp", "n_a:setProp",
                "n_d:setProp", "n_d:setProp", "n_d:setProp", "n_d:setProp",
                "n_h:setProp", "n_h:setProp", "n_h:setProp", "n_h:setProp",
                // The record last, ascending by type, after every row it describes.
                "core.Column:setComponentVersion", "m3.Text:setComponentVersion",
            ),
            patch.ops.map { describe(it) },
            "the patch is not the one the KDoc describes",
        )

        val migrated = PatchApplier.apply(patch, BEFORE)

        assertSameDocument(AFTER, migrated, "the upgrade did not produce the migrated document")
        assertEquals(
            mapOf(CARD to 2, COLUMN to 2, TEXT to 3),
            migrated.meta.componentVersions,
            "the document does not record where every component now is",
        )
    }

    /**
     * Undo, which is §19.3's reason for the patch at all.
     *
     * Asserted as the whole identity rather than field by field, because the failure it catches
     * is the one a field-by-field assertion cannot see: two documents differing inside the node
     * table compare unequal and print identically.
     */
    @Test
    fun `the upgrade is undone by the editor applying the patch's inverse`() {
        val patch = SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS)

        val applied = PatchApplier.apply(patch, BEFORE)
        val undone = PatchApplier.apply(patch.inverse(), applied)

        assertSameDocument(BEFORE, undone, "undoing the upgrade did not restore the document")
    }

    /**
     * The record is part of the undo, not a side effect of it.
     *
     * Worth a test of its own because it is the half an upgrade that only rewrote nodes would
     * get right: every row above comes back and the document still claims `m3.Text` is at v3,
     * which is a document that renders and is wrong, and which the next load then declines to
     * migrate.
     */
    @Test
    fun `the inverse moves the recorded version back as well as the rows`() {
        val inverse = SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS).inverse()

        assertEquals(
            // Reversed, because `Patch.inverse` reverses the op list as well as each op — the
            // two version ops do not see each other's effects, so this is the one place in the
            // file where the order is asserted for the mechanism's sake rather than the pass's.
            listOf(
                PatchOp.SetComponentVersion(TEXT, old = 3, new = 1),
                PatchOp.SetComponentVersion(COLUMN, old = 2, new = 1),
            ),
            inverse.ops.filterIsInstance<PatchOp.SetComponentVersion>(),
            "undoing the upgrade left the record where the upgrade put it",
        )
        assertEquals(
            BEFORE.meta.componentVersions,
            PatchApplier.apply(inverse, AFTER).meta.componentVersions,
        )
    }

    /**
     * A step is applied to the nodes of its own component type, and to nothing else.
     *
     * `SpecMigration.apply` is a bare `(Node) -> Node` with no idea which component a row is an
     * instance of — `SpecMigrationRegistry.migrate` relies on its caller for that, and says so in
     * one word. So a `m3.Text` step reaching a `core.Column` row would rename whatever the column
     * happened to have put in a property called `label`. The fixture makes it visible: only three
     * `m3.Text` rows carry one, and the rows that must not move are named rather than counted.
     *
     * The second half asserts the *values* survived, which every count in this file would pass.
     */
    @Test
    fun `a step is applied to the nodes of its own component type only`() {
        val migrated = PatchApplier.apply(SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS), BEFORE)

        for (untouched in listOf(GAMMA, ECHO, INDIA, KILO, LIMA, FOXTROT, GOLF)) {
            assertEquals(
                BEFORE.nodes.require(untouched),
                migrated.nodes.require(untouched),
                "'$untouched' is not an m3.Text row carrying a label, and it moved",
            )
        }

        assertEquals(
            mapOf(BODY to PropertyValue.Const(Value.Str("Alpha"))),
            migrated.nodes.require(ALPHA).props,
            "the renamed property did not carry its value across both steps",
        )
        assertEquals(
            mapOf(BODY to PropertyValue.Const(Value.Str("Hotel"))),
            migrated.nodes.require(HOTEL).props,
        )
    }

    // -----------------------------------------------------------------------------------
    // Idempotence and determinism
    // -----------------------------------------------------------------------------------

    /**
     * A document that is already current gets the empty patch.
     *
     * **The patch, not the document.** §26.2 calls an empty patch legal and `DocumentController`
     * declines to publish one, so "there was nothing to do" has to arrive as an empty patch for
     * a loader that runs the pass on every document it opens and only enters history when it has
     * ops. The second assertion is what earns the first: it is what makes running the pass twice
     * safe even for a step that is not idempotent, because the chain is re-planned from a version
     * that no longer admits it.
     */
    @Test
    fun `a document that is already current produces an empty patch`() {
        val once = PatchApplier.apply(SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS), BEFORE)

        assertEquals(Patch(emptyList()), SpecMigrationPass.plan(once, REGISTRY, TARGETS))
        assertSameDocument(once, PatchApplier.apply(Patch(emptyList()), once), "and it changes nothing")
    }

    /**
     * Two calls, two equal patches.
     *
     * §6.2's determinism is a claim about bytes, and this is the half of it a list can carry. The
     * pass walks two maps and a node table, and all three are sorted by hand rather than trusted:
     * `PersistentHashMap`'s iteration order differs per target and §6.2 does not care which
     * target a document was opened on.
     */
    @Test
    fun `the same document planned twice gives equal patches`() {
        assertEquals(
            SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS),
            SpecMigrationPass.plan(BEFORE, REGISTRY, TARGETS),
        )
    }

    // -----------------------------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------------------------

    /**
     * An uncovered gap throws, out of the registry, naming the hop it stopped at.
     *
     * The registry here holds `m3.Text`'s v1-to-v2 step and not its v2-to-v3, so a document at
     * v1 asked to reach v3 has no honest patch to offer. Asserted on the message as well as on
     * the type, because the message is the whole value of throwing: a caller told "no migration"
     * has nothing to show a person, and one told "m3.Text from v2 to v3" knows which component is
     * behind and which step its plugin is missing.
     */
    @Test
    fun `an uncovered gap throws the registry's own refusal`() {
        val incomplete = SpecMigrationRegistry(listOf(TEXT_LABEL_TO_TEXT, COLUMN_CHILDREN_TO_ITEMS))

        val failure = assertFailsWith<NoSuchElementException> {
            SpecMigrationPass.plan(BEFORE, incomplete, TARGETS)
        }
        val message = failure.message.orEmpty()

        assertTrue(message.contains(TEXT.value), "the message does not name the component: $message")
        assertTrue(message.contains("v2"), "the message does not name the version it stopped at: $message")
    }

    /**
     * A document written against a newer contract than this engine ships is not downgraded.
     *
     * The refusal is the registry's and it is the right one: a migration is not a downgrade, and
     * an engine that rewrote a v3 document back to v2 would produce a document its own runtime
     * cannot read. The message has to say which direction, because "cannot migrate" without the
     * two versions is a sentence nobody can act on.
     */
    @Test
    fun `a document newer than the target throws rather than being downgraded`() {
        val ahead = document(LABEL, CHILDREN, mapOf(CARD to 2, COLUMN to 2, TEXT to 4))

        val failure = assertFailsWith<IllegalArgumentException> {
            SpecMigrationPass.plan(ahead, REGISTRY, TARGETS)
        }

        assertTrue(
            failure.message.orEmpty().contains("backwards"),
            "the message does not say why: ${failure.message}",
        )
    }

    /**
     * A recorded component the caller has not placed a target for is left exactly as recorded.
     *
     * This is the contract that lets a caller pass a map it built from
     * `SpecMigrationRegistry.latest`, where a component with no steps has no `latest` and so no
     * entry, without every such component turning into a refusal. The alternative — inventing a
     * version for a component the document named — is a claim about the document that the
     * document did not make.
     */
    @Test
    fun `a recorded component with no target is left exactly as recorded`() {
        val migrated = PatchApplier.apply(
            SpecMigrationPass.plan(BEFORE, REGISTRY, mapOf(TEXT to 3)),
            BEFORE,
        )

        assertEquals(
            mapOf(CARD to 2, COLUMN to 1, TEXT to 3),
            migrated.meta.componentVersions,
            "a component with no target was moved, or one with a target was dropped",
        )
    }

    /**
     * A migration that changes a node's component type is refused, and nothing is applied.
     *
     * There is no op for `Node.type` — see [PatchOp]'s KDoc — so such a change has no
     * representation, and a scope that dropped it would leave a row whose body was migrated for
     * one component and whose type says another. What matters is that it throws at all: the
     * exception is raised while the patch is being *built*, and this pass applies nothing ever, so
     * there is no half-upgraded document for the throw to have left behind.
     */
    @Test
    fun `a migration that changes a node's component type is refused`() {
        val retyping = SpecMigration(COLUMN, 1, 2) { it.copy(type = TEXT) }

        val failure = assertFailsWith<IllegalStateException> {
            SpecMigrationPass.plan(BEFORE, SpecMigrationRegistry(listOf(retyping)), TARGETS)
        }
        val message = failure.message.orEmpty()

        // The first `core.Column` row in `NodeTable.ids` order, which is the one the walk
        // reached first and the one the message therefore names.
        assertTrue(message.contains(BETA.value), "the message does not name the node: $message")
        assertTrue(message.contains(COLUMN.value), "the message does not name the migration: $message")
        assertTrue(message.contains(TEXT.value), "the message does not name the type it became: $message")
    }

    /**
     * The targets the rest of this file migrates to are the ones the registry reports.
     *
     * Without this the fixture asserts its own literals: `TARGETS` says `m3.Text` is at v3 and
     * the tests would pass whether or not that is where the registry's own steps stop. Asserting
     * `latest` here is what makes "the document ends up at the registry's latest" mean something.
     *
     * `core.Card` is the third case and the reason [TARGETS] can have a literal in it at all: a
     * component with no steps has no `latest`, so there is nothing to read one from.
     */
    @Test
    fun `the targets the fixture migrates to are the ones the registry reports`() {
        assertEquals(2, REGISTRY.latest(COLUMN), "core.Column's steps stop at v2")
        assertEquals(3, REGISTRY.latest(TEXT), "m3.Text's steps stop at v3")
        assertNull(REGISTRY.latest(CARD), "core.Card has no steps, so it has no latest")
    }
}

// ---------------------------------------------------------------------------------------
// The registry
// ---------------------------------------------------------------------------------------

/**
 * The three steps, written as the two kinds §19.1 lists: property renames and a slot rename.
 *
 * `m3.Text`'s two steps chain, which is the point of the fixture — step two reads the `text` that
 * step one wrote, so a pass that planned the whole gap as one transform would produce a different
 * patch from one that emits a step at a time, and the op-list assertion above is what tells those
 * two apart.
 *
 * The slot rename is the other half of §19.1's list. §5.6 puts a node's children in the
 * **parent's** row, so renaming a slot rewrites that one row and touches no child — which is why
 * it becomes a `RemoveSubtree`/`PutNodes` pair rather than a field op.
 */
private val LABEL: PropertyKey = PropertyKey("label")
private val TEXT_KEY: PropertyKey = PropertyKey("text")
private val BODY: PropertyKey = PropertyKey("body")
private val ITEMS: SlotName = SlotName("items")

private val TEXT_LABEL_TO_TEXT: SpecMigration =
    SpecMigration(TEXT, 1, 2) { node ->
        val label = node.props[LABEL] ?: return@SpecMigration node
        node.copy(props = node.props - LABEL + (TEXT_KEY to label))
    }

private val TEXT_TEXT_TO_BODY: SpecMigration =
    SpecMigration(TEXT, 2, 3) { node ->
        val text = node.props[TEXT_KEY] ?: return@SpecMigration node
        node.copy(props = node.props - TEXT_KEY + (BODY to text))
    }

private val COLUMN_CHILDREN_TO_ITEMS: SpecMigration =
    SpecMigration(COLUMN, 1, 2) { node ->
        if (CHILDREN !in node.slots) return@SpecMigration node
        node.copy(slots = node.slots - CHILDREN + (ITEMS to node.slots.getValue(CHILDREN)))
    }

private val REGISTRY: SpecMigrationRegistry =
    SpecMigrationRegistry(listOf(TEXT_LABEL_TO_TEXT, TEXT_TEXT_TO_BODY, COLUMN_CHILDREN_TO_ITEMS))

/**
 * Where each component is now: the registry's `latest` for the two that have steps, and a
 * literal for `core.Card`, which has none — see the test that checks exactly that.
 */
private val TARGETS: Map<ComponentType, Int> = mapOf(
    CARD to 2,
    COLUMN to 2,
    TEXT to 3,
)

// ---------------------------------------------------------------------------------------
// The documents
// ---------------------------------------------------------------------------------------

/** The rows every document below holds, in the order `NodeTable.ids` will walk them. */
private val ROOT_CHILDREN: List<NodeId> = listOf(ALPHA, BETA, GAMMA, FOXTROT, GOLF)

/**
 * The document as its author left it: `m3.Text` at v1 with a `label`, `core.Column` at v1 with a
 * `children` slot, and `core.Card` already at v2 and therefore already current.
 *
 * `core.Banner` rows exist and are **not recorded at all**, which is the case the pass has to
 * leave alone: there is no `from` to plan from, and inferring one would be guessing a component
 * author's version.
 */
private val BEFORE: UiDocument = document(LABEL, CHILDREN, mapOf(CARD to 2, COLUMN to 1, TEXT to 1))

/** The same document after [REGISTRY] has been walked: `body` instead of `label`, `items`
 * instead of `children`, and a record that says so. */
private val AFTER: UiDocument = document(BODY, ITEMS, mapOf(CARD to 2, COLUMN to 2, TEXT to 3))

/**
 * One document, spelled from its two switches rather than as two literal rows lists.
 *
 * [plannerDocument] is the fixture and it is used for everything it can express — `meta`, `app`,
 * `pages` — but its root row is built with `mapOf(CHILDREN to rootChildren)` and the
 * `core.Column` step here renames that very slot. So the root row is the one row this builds
 * itself, and it is the only reason there is a builder at all: two lists of thirteen rows side by
 * side is a wall a reader compares by eye, and the eye finds "which property became which" and
 * "which slot became which" far faster when each is one named argument.
 *
 * [key] is the property a `m3.Text` row carries and [slot] the slot a `core.Column` row carries;
 * the *value* each holds is the same on both sides, because that is what a rename preserves and
 * what an assertion that only counted ops would not notice losing.
 */
private fun document(
    key: PropertyKey,
    slot: SlotName,
    versions: Map<ComponentType, Int>,
): UiDocument {
    val base = plannerDocument(rootChildren = ROOT_CHILDREN, rows = emptyList())

    var table: NodeTable = base.nodes.with(
        Node(id = ROOT, type = COLUMN, slots = mapOf(slot to ROOT_CHILDREN)),
    )
    for (row in rows(key, slot)) table = table.with(row)

    return base.copy(meta = DocumentMeta("Demo", componentVersions = versions), nodes = table)
}

/**
 * The twelve rows that are not the root.
 *
 * Three `m3.Text` rows carry no text at all, which is the case a step has nothing to do on: they
 * must produce no ops rather than a pair that writes the value they already hold. One carries a
 * name, a modifier chain and a handler alongside its property, so that a pass which rewrote the
 * whole row rather than the one field would be caught by the count as well as by the rendering.
 */
private fun rows(key: PropertyKey, slot: SlotName): List<Node> = listOf(
    textRow(ALPHA, name = "Alpha", text = "Alpha", key = key),
    Node(id = BETA, type = COLUMN, slots = mapOf(slot to listOf(DELTA, ECHO))),
    textRow(GAMMA, key = key),
    Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(HOTEL))),
    Node(id = GOLF, type = BANNER, slots = mapOf(TOP_BAR to listOf(INDIA))),
    textRow(DELTA, text = "Delta", key = key),
    textRow(ECHO, key = key),
    textRow(HOTEL, text = "Hotel", key = key),
    textRow(INDIA, key = key),
    Node(id = JULIET, type = COLUMN, slots = mapOf(slot to listOf(KILO, LIMA))),
    Node(id = KILO, type = COLUMN),
    Node(id = LIMA, type = COLUMN),
)

/**
 * One `m3.Text` row, spelled out rather than reusing `alphaNode`, because this fixture needs four
 * shapes the planner fixtures do not have: a row carrying a property under a *named* key, one
 * carrying none, one with a label and a modifier chain and a handler as well, and one bare.
 */
private fun textRow(
    id: NodeId,
    name: String? = null,
    text: String? = null,
    key: PropertyKey,
): Node = Node(
    id = id,
    type = TEXT,
    name = name,
    props = if (text == null) emptyMap() else mapOf(key to PropertyValue.Const(Value.Str(text))),
    modifiers = if (name == null) emptyList() else listOf(PADDING),
    events = if (name == null) emptyMap() else mapOf(ON_CLICK to CLICK),
)

// ---------------------------------------------------------------------------------------
// Describing an op, so a failure reads as a list
// ---------------------------------------------------------------------------------------

/**
 * `"address:tag"` for every op — the node id for the eight that name one, the declaration for the
 * one that names a record, and the component type for the one that names neither.
 *
 * A rendering rather than the ops themselves, because a twenty-entry `assertEquals` over data
 * classes prints twenty multi-line records, and what a reader of a failure needs is *where the
 * sequence diverges* — which is a list of short strings an eye scans line by line and a stack of
 * records does not.
 */
private fun describe(op: PatchOp): String = when (op) {
    is PatchOp.InsertNodes -> "${op.parent}:insertNodes"
    is PatchOp.PutNodes -> "${op.nodes.first().id}:putNodes"
    is PatchOp.RemoveSubtree -> "${op.root}:removeSubtree"
    is PatchOp.MoveNode -> "${op.id}:moveNode"
    is PatchOp.SetProp -> "${op.node}:setProp"
    is PatchOp.SetModifiers -> "${op.node}:setModifiers"
    is PatchOp.SetHandler -> "${op.node}:setHandler"
    is PatchOp.SetNodeName -> "${op.node}:setNodeName"
    is PatchOp.PutRecord -> "${op.ref}:putRecord"
    is PatchOp.SetTheme -> "theme:setTheme"
    is PatchOp.SetComponentVersion -> "${op.type}:setComponentVersion"
}