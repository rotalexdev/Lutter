@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, which
// kotlinx.serialization still marks experimental. Everything else in this file is stable API.
// The annotation is not optional decoration - it is what puts the discriminator on the wire
// under the name this project chose rather than the library default - so opting in at file
// scope is narrower and more honest than suppressing use by use.
//
// It has to be a file annotation and not a per-use `@OptIn` because Kotlin requires file
// annotations to precede the `package` declaration, and putting it after the imports is a
// syntax error rather than a warning.

package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The closed vocabulary of edits a command may produce, and the whole of it.
 *
 * PLAN §26.2 declares eight, and this file declares those eight with the plan's names and the
 * plan's field names, in the plan's order, plus the ninth that D30 added, the tenth that D31
 * added and the twelfth that §19.3's migration pass added. Nothing else is added, because
 * every addition here is paid for twice: `PatchApplier` grows a branch and `PatchInverter`
 * grows a branch, and an op that only one of the two remembered is a patch whose inverse is
 * silently wrong.
 *
 * **The eleventh is still missing and is still [PatchOp]'s to lose.** D31 left the gap that a
 * kept row's component type has no op, and [SetComponentVersion] arrived at twelve rather than
 * filling it — see that op's KDoc, which says why §19.3's need and `Node.type`'s are two
 * different holes and why the one that was filled is the one that was smaller.
 *
 * ### Why these twelve, and what the set is for
 *
 * They are not "every change the engine can make". They are the changes §26.1 chose for a
 * command to produce — *"Command → forward/inverse Patch"*, chosen over a snapshot per step
 * because it gives exact semantics and a mechanism that diffing, syncing and undo all reuse.
 * §33.6's remaining editing files (`DocumentDiff`, `SpecMigrationPass`, the planners) are
 * consumers of this vocabulary, not extensions of it: the migration pass is explicitly "apply
 * component migrations as a patch", and the diff is a patch between two documents.
 *
 * The ninth is [PutNodes], and it exists because the first eight could not answer a question
 * the document asks: a page's root is a row that no slot references (§5.5 makes `Page.root` a
 * pointer into the table precisely so a page holds no nodes), and nothing in the original eight
 * could put a row there. See its own KDoc for what it does and does not check.
 *
 * The tenth is [SetTheme], and it exists for the same kind of reason with the opposite shape:
 * the gap was **on the addressing side** rather than in the op set. [RecordRef.Theme] names a
 * `ThemeDecl`, so the document's *pointer at one* — a nullable id, where `null` is §14.2's
 * rule and not an absence — had nothing that could name it. D31 settled that by adding the op
 * rather than by bending a reference that already had a job, and its KDoc says why [RecordRef]
 * is the wrong place to fix it.
 *
 * ### Every op carries its own `old`, and that is the property being bought
 *
 * Seven of the twelve have a field literally named `old`; [InsertNodes], [PutNodes] and
 * [RemoveSubtree] carry the whole subtree they wrote or captured instead, which is the same
 * information with a different shape. The consequence is the one §26.2 draws out immediately
 * after the declaration: **inverting a patch needs no document.** There is no lookup, no index, no
 * "where was this node before", and therefore no way for an inverse to be computed against a
 * document that has moved on since the command ran. `PatchInverter` is the file that spells
 * this out.
 *
 * The cost is duplication, and it is worth naming: a patch holds both sides of every edit, so
 * an undo stack holds two descriptions of a change rather than one. §26.1 accepted that in
 * exchange for a mechanism that is small, exact and testable, and the alternative — carrying
 * the document into the inverter — is what would make undo depend on the document's history
 * rather than on its current state.
 *
 * ### Indices are absolute, never relative
 *
 * [InsertNodes.index], [Placement.index] and [RemovedSubtree.index] are positions, not
 * offsets. Nothing says "two places after". This is what makes applying a reversed op list
 * correct: the last op undone is the first one undone and every position it names is a
 * position in the document as that op left it, which is the document the earlier ops will
 * have already restored. A relative offset would have to be recomputed against a document
 * that no longer exists. `PatchApplier.applyMove` states the consequence for the one case
 * where it is easy to get wrong.
 *
 * **[PutNodes] has no position at all** and neither does its inverse, because rows written
 * into the table are in no slot until something references them. `RemovedSubtree` spells that
 * absence as three nulls rather than as an index of minus one; see its KDoc for why.
 *
 * ### Serializable, and why every tag is spelled
 *
 * @Serializable is not decoration: §26.1's rejected "full snapshot per step" is what this set
 * replaces, and a patch that cannot cross a process boundary cannot be logged, shipped to a
 * collaborating editor, or replayed in a test. Each variant carries an explicit `@SerialName`
 * for the reason `Value`'s does — a rename without one repoints every stored patch at a tag
 * nothing answers to, and the failure arrives as an unreadable file rather than as an error a
 * person reads. `NoImplicitSerialNameTest` in `:tools:architecture-tests` enforces it over
 * every engine source file, which is why this hierarchy is written the way `PropertyValue` is.
 *
 * The discriminator is `type`, spelled through [JsonClassDiscriminator] for the same reason
 * every other persisted union in this repository spells it: a library default that can be
 * renamed is not a format contract.
 *
 * ### What this vocabulary does not cover, stated rather than left to be discovered
 *
 * D31 closed three of the four gaps the first nine carried. What is left is worth as much
 * prose as what was closed, because a caller has to know which set they are in.
 *
 *  * **`UiDocument.meta`'s identity and `UiDocument.app` have no op, and that is the design
 *    rather than an omission.** They are a document's identity and its
 *    [dev.rotalex.lutter.model.doc.AppSpec], fixed for the life of the document: there is no
 *    edit that changes a document's name, its plugin requirements, its package or the page it
 *    opens on, and a patch that could express one would describe a document that is not the one
 *    that was edited. A diff refuses a pair that disagrees on them rather than emitting a patch
 *    that reproduces every other field and leaves these two behind — see
 *    [dev.rotalex.lutter.editing.diff.DocumentDiff.compute].
 *  * **`DocumentMeta.componentVersions` is the one field of `meta` that moves, and
 *    [SetComponentVersion] is why the bullet above is narrower than "no op edits `meta`".** The
 *    distinction is not cosmetic: a document's *name* is what it is, and a document's
 *    *recorded contract versions* are what an older engine believes it was written against —
 *    a record that is stale by definition the moment a plugin ships, and that §19.3 requires to
 *    move in the same patch that moves the nodes it describes. One is identity and one is a
 *    note about identity, and conflating them is what would have made §19.3 unimplementable.
 *  * **`Node.type` has no op.** A row's component type is not one of the four per-field ops
 *    and is not addressed by any reference in this file, so a diff that meets two documents
 *    whose rows differ only in type has nothing to say. The same argument as `meta` and `app`
 *    does not apply — a component type *is* edited, by pasting from a palette — and so the
 *    refusal is the honest answer for now and the op that fixes it is the real answer. It is
 *    still unwritten, and §19.3 did not need it: a component contract migration rewrites the
 *    *body* of a node of that type, and §19.1's "type changes" is a column's `TypeRef` rather
 *    than `Node.type`. See [dev.rotalex.lutter.editing.diff.DocumentDiff.compute].
 *  * **Page- and component-scoped state is not addressed by [RecordRef.State].** §12.1 puts
 *    state at three scopes and two of them live *inside* a `Page` or a `ComponentDecl`, so
 *    changing one is a `PutRecord` of the record that owns the list. See that type's KDoc.
 *
 * @see Patch for the list these ops travel in, and for the two members built from them.
 * @see PatchApplier for the only code that turns a patch into a document.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface PatchOp {

    // -----------------------------------------------------------------------------------
    // The node table
    // -----------------------------------------------------------------------------------

    /**
     * A whole subtree, placed into a parent's slot.
     *
     * [nodes] is **the whole subtree, flattened, root first** — §26.2's comment says so and
     * the flat shape makes it load-bearing. §5.6 normalized the document precisely so that a
     * child is a reference rather than a nested value, and this op keeps that: every node in
     * [nodes] becomes a row in `UiDocument.nodes`, and exactly one id — [nodes]' first — is
     * written into the parent's slot. Writing the whole subtree into the slot would put the
     * nested tree §5.6 rejected one operation away.
     *
     * "Root first" is the order a consumer needs in order to build the tree in a single pass
     * without sorting, and it is the order the inverse depends on: [PatchOp.RemoveSubtree]'s
     * capture is read back as an [InsertNodes] with the same list.
     *
     * There is no `root` field, as there is on [RemoveSubtree], and that asymmetry is
     * deliberate rather than an oversight. A removal has to name what it removed because the
     * removal happened before this record was written and the id is not otherwise recoverable
     * from a subtree that has left the table; an insertion is holding the subtree in the op
     * itself, so its first element *is* its root.
     *
     * @throws IllegalArgumentException at apply time, never here: an empty [nodes] list has
     *   no root to place. Construction stays total so a patch can be built and inspected
     *   before anybody has a document to apply it to. [PutNodes] is the exception and says why
     *   it cannot be: an empty one would have no inverse.
     */
    @Serializable
    @SerialName("insertNodes")
    public data class InsertNodes(
        /** The node whose slot receives the subtree. Must exist when the op is applied. */
        public val parent: NodeId,

        /** Which named child area receives it (§5.1: `children`, `content`, `topBar`). */
        public val slot: SlotName,

        /** Where in that slot, counting from zero, in the list as it is when the op runs. */
        public val index: Int,

        /** The whole subtree, root first. Never empty. */
        public val nodes: List<Node>,
    ) : PatchOp

    /**
     * Rows put into the table with no parent: the table-level counterpart to [InsertNodes].
     *
     * [InsertNodes] answers *"make these rows reachable from here"* — it needs a parent node,
     * a slot name and a position, and it writes the rows **and** the reference that makes them
     * reachable. This op answers the other half — *"these rows exist"* — and is what a page
     * root is, because `Page.root` names a `NodeId` that no node's slot lists.
     *
     * **The split is not a convenience.** `AddPage` is two facts — a `Page` record and the rows
     * its root names — and emitting only the record would produce a document whose page points
     * at a node the table does not hold. That is not a rejected command; it is a document that
     * fails later, as `NodeTable.require`'s `IllegalStateException`, from whichever component
     * walks the page first, naming a missing node rather than the edit that made it missing.
     * The planner therefore emits this op and then the `PutRecord` that makes the first one
     * reachable, and the second op is what makes the pair undoable as a whole.
     *
     * ### A row nothing points at is a legal intermediate state
     *
     * Applying this op alone leaves the document in a state no §17 pass would accept — §17.1's
     * structural pass would report `struct.missing_root` for the row nothing reaches — and that
     * is deliberate. §6.2's invariants are properties of a *document*, and a patch is not a
     * document; every op here may be applied in an order that is transiently illegal, which is
     * why `PatchInverter` reverses the list as well as inverting each op.
     *
     * ### No slot index, and therefore none of [PatchApplier]'s position checks
     *
     * There is no `parent`, no `slot` and no `index` here, and there is deliberately no
     * `from`. [PatchApplier]'s strictest check — that the node an op claims at a position is
     * the node actually there — exists because §5.6's normalization makes a wrong-order
     * document invisible to every existence check in the engine. **That failure mode needs a
     * position, and this op has none to be wrong about.** What it does check is ids: writing a
     * row whose id the table already holds would, through `NodeTable.with`, *replace* a live
     * node rather than fail, so the applier refuses it.
     *
     * ### Never empty, which is a different rule from [InsertNodes]'s
     *
     * [InsertNodes] accepts an empty list and lets the applier refuse it, because a patch is
     * often built before anybody holds the document to check it against. This one refuses at
     * construction, and the reason is its inverse: [RemoveSubtree] names a root, that root is
     * `nodes.first()`, and there is no first element of nothing. An empty `PutNodes` would be
     * a patch that cannot be inverted at all, which is the one property §26.2's whole set is
     * chosen to buy.
     */
    @Serializable
    @SerialName("putNodes")
    public data class PutNodes(
        /** The rows to write, root first. Never empty. */
        public val nodes: List<Node>,
    ) : PatchOp {
        init {
            require(nodes.isNotEmpty()) {
                "PatchOp.PutNodes carries no nodes, so it cannot be inverted: its inverse is a " +
                    "RemoveSubtree whose root is the first of them"
            }
        }
    }

    /**
     * A whole subtree, lifted out of the document, together with where it came from.
     *
     * The [captured] field is the whole reason this op is invertible and the reason it is
     * heavier than [InsertNodes]: a removal that carried only its `root` could not be undone,
     * because the node it names is gone and §6.2's "ids are never reused" means nothing else
     * can stand in for it. The capture is the subtree, flattened and root-first in the same
     * shape an insertion takes, so the inverse is a field-for-field copy rather than a
     * reconstruction.
     *
     * The capture also records the position, which is the part that is easy to forget. Removing
     * a node without remembering which slot held it, and at what index, leaves a document that
     * differs from the original in a way no property check can see — the nodes are all still
     * there, just in the wrong order, which for a `Column` is a different layout rather than a
     * smaller one.
     *
     * **All three position fields may be `null`**, and all three together, which is the form
     * [PutNodes]'s inverse takes. See [RemovedSubtree] for why absence is spelled as null
     * rather than as a reserved id.
     */
    @Serializable
    @SerialName("removeSubtree")
    public data class RemoveSubtree(
        /** The root of the removed subtree, which is the id the parent held. */
        public val root: NodeId,

        /** The removed rows and the position they were lifted from, for the inverse. */
        public val captured: RemovedSubtree,
    ) : PatchOp

    /**
     * One node moved from one position to another, within a slot or between slots.
     *
     * A move edits two parents' `slots` maps and touches **no row in the table**: the node is
     * the same node. That is §5.6's normalized form paying out, and it is why a move is two
     * record rewrites rather than a tree splice. Both placements are absolute positions, and
     * `PatchApplier` documents the one rule a caller has to know: [to]'s index is read against
     * the list *after* [from] has been taken out of it, which is what makes this op its own
     * inverse.
     *
     * A move of a node into its own subtree is not refused by this type. It is refused by the
     * document's structure, and §17.1's structural pass is where a cycle is reported — the
     * same division of labour as everywhere else in this engine, where a record checks what it
     * can see and a pass checks what it cannot.
     */
    @Serializable
    @SerialName("moveNode")
    public data class MoveNode(
        /** The node being moved. Its record is unchanged; only its position is. */
        public val id: NodeId,

        /** Where it is now, at the moment the op runs. */
        public val from: Placement,

        /** Where it goes, counted against the slot as it is once [from] has been vacated. */
        public val to: Placement,
    ) : PatchOp

    // -----------------------------------------------------------------------------------
    // One node's own fields
    // -----------------------------------------------------------------------------------

    /**
     * One entry of a node's `props` map set or cleared.
     *
     * `old` is the entry's value before the edit and `new` the value after, and **either may
     * be `null`**: `null` means the entry is absent, so `old = null, new = v` sets a property
     * that had none and `old = v, new = null` clears one. A `PropertyValue?` rather than a
     * nullable wrapper value is what makes both directions expressible without a third
     * "unset" case — and `Node.props` is a map, not a field, so an absent key is genuinely
     * different from a key holding null.
     */
    @Serializable
    @SerialName("setProp")
    public data class SetProp(
        /** The node whose `props` changes. */
        public val node: NodeId,

        /** Which entry (§5.3's map is keyed by a checked `PropertyKey`). */
        public val key: PropertyKey,

        /** The entry before, or `null` when the key was absent. */
        public val old: PropertyValue?,

        /** The entry after, or `null` to clear the key. */
        public val new: PropertyValue?,
    ) : PatchOp

    /**
     * A node's whole modifier chain replaced at once.
     *
     * The entire [List] and not one entry, for the reason `Node.modifiers` is a list: order is
     * the meaning (§5.1, §5.3, §7.3). An op that added or removed a single modifier would have
     * to say *where*, which is a placement problem dressed as a field problem, and `padding`
     * before `background` is not the same node as `background` before `padding`. Replacing the
     * list is also the only shape whose inverse is exact when the edit was a reorder, which is
     * a thing a drag handle in a design tool produces constantly.
     */
    @Serializable
    @SerialName("setModifiers")
    public data class SetModifiers(
        /** The node whose `modifiers` changes. */
        public val node: NodeId,

        /** The chain as it was, in application order. */
        public val old: List<ModifierEntry>,

        /** The chain as it is after. */
        public val new: List<ModifierEntry>,
    ) : PatchOp

    /**
     * One event handler on a node set or cleared.
     *
     * One [EventKey] and not the whole `events` map, for the same reason [SetProp] is one key:
     * the two share a shape deliberately. `Node.events` is a map keyed by a checked name, and
     * an absent entry and an entry holding null are different facts — "nothing happens on
     * click" is a statement about the node, not a handler with no steps.
     */
    @Serializable
    @SerialName("setHandler")
    public data class SetHandler(
        /** The node whose `events` changes. */
        public val node: NodeId,

        /** Which handler (§5.1's `EventKey → ActionSequence`). */
        public val event: EventKey,

        /** The handler before, or `null` when there was none. */
        public val old: ActionSequence?,

        /** The handler after, or `null` to clear it. */
        public val new: ActionSequence?,
    ) : PatchOp

    /**
     * A node's human-facing label changed or cleared.
     *
     * The smallest op in the set and the only one that touches no collection: `Node.name` is a
     * nullable label, validated by nothing and resolved by nothing (§5.3's own comment says so
     * in three words). That is precisely why it is worth an op of its own — because a rename
     * of a *page* changes generated identifiers and is not this, while a rename of a node
     * changes one field and is. Conflating the two is the mistake this type prevents.
     */
    @Serializable
    @SerialName("setNodeName")
    public data class SetNodeName(
        /** The node whose `name` changes. */
        public val node: NodeId,

        /** The label before, or `null`. */
        public val old: String?,

        /** The label after, or `null` to clear it. */
        public val new: String?,
    ) : PatchOp

    // -----------------------------------------------------------------------------------
    // The declarations
    // -----------------------------------------------------------------------------------

    /**
     * One document declaration — a page, a component, a theme, a resource, a state, a model —
     * written or removed.
     *
     * The only op that does not address a node, and the reason the node ops do not have to
     * cover the rest of `UiDocument`: §26.2's comment names what this reaches — *"pages,
     * decls, themes, resources, state, models"* — and those are exactly the containers
     * [RecordRef] can address.
     *
     * `old` and `new` are whole records rather than fields, because a declaration has no
     * per-field op and inventing six more to match the node side would double the size of the
     * closed set for no gain: a theme's nine fields are edited together or not at all. The
     * cost is the inverse carrying both versions of a possibly large record, which is the same
     * trade every other op in this file makes and for the same reason.
     *
     * `new = null` removes the declaration the [ref] names; `old = null` means it was not
     * there, which is how an insertion is distinguished from a replacement without a flag.
     */
    @Serializable
    @SerialName("putRecord")
    public data class PutRecord(
        /** Which declaration, in which container. */
        public val ref: RecordRef,

        /** The declaration before, or `null` when the container had no entry at that key. */
        public val old: Record?,

        /** The declaration after, or `null` to remove it. */
        public val new: Record?,
    ) : PatchOp

    // -----------------------------------------------------------------------------------
    // The document's own fields
    // -----------------------------------------------------------------------------------

    /**
     * Which of `UiDocument.themes` the document selects.
     *
     * The tenth op, added by D31, and the only one that edits a document rather than anything
     * *in* it. Every other op names a row, a declaration, or a field of one of the two; this
     * one names the document's own pointer at one of its declarations.
     *
     * ### Why it exists rather than a [RecordRef], because the same hole had two answers
     *
     * [RecordRef.Theme] addresses a `ThemeDecl` by id, and a diff was asked to say "this
     * document selects `t_dark` now" and could not: it has nowhere to put a *nullable* id,
     * because every reference in this hierarchy names something that exists. **The null is the
     * whole difficulty** — §14.2 makes `null` a real selection (one theme and no selection is
     * that theme; several themes and no selection falls back to Material's base), so it is a
     * value rather than an absence, and `new = null` on a `PutRecord` would mean *remove this
     * theme declaration* rather than *stop selecting one*.
     *
     * The alternatives were rejected for different reasons and are named here so the choice
     * reads as a choice:
     *
     *  * **Abuse [RecordRef.Theme].** It would have to mean two things by whether the record
     *    was null, and `PutRecord`'s own contract already gives null a meaning — a removal.
     *    One op would then carry two unrelated edits, and [PatchInverter] would have to know
     *    which.
     *  * **A `PutRecord` against a synthetic "selection" declaration.** A declaration that is
     *    not in `UiDocument.themes` has no key, and §26.2's whole addressing scheme assumes a
     *    reference names something a map holds.
     *
     * ### The shape is [SetProp]'s, and it is invertible for the same reason
     *
     * `old` and `new`, both nullable, both present. `old` is what makes the inverse a field
     * copy, which is what makes undo work without a document — the property the whole set is
     * chosen to buy. An op carrying only `new` would be invertible only against the document,
     * and §26.1's rejected snapshot-per-step would be back through the side door.
     *
     * **A patch that sets the same id on both sides is legal and is a no-op**, exactly as
     * [SetProp] with equal values is. Nothing here validates that the selection names a
     * declared theme: §14.2's resolution is a total function of this id plus `themes`, and a
     * document that selects a theme it does not declare is a document §17 reports, not a patch
     * this file refuses.
     */
    @Serializable
    @SerialName("setTheme")
    public data class SetTheme(
        /** The selection before, or `null` when §14.2's rule was in force. */
        public val old: ThemeId?,

        /** The selection after, or `null` to fall back to §14.2's rule. */
        public val new: ThemeId?,
    ) : PatchOp

    /**
     * One entry of `DocumentMeta.componentVersions` set or cleared.
     *
     * The twelfth op, and the one that narrows what this file used to say about `meta` — from
     * *no op edits it* to *no op edits its identity*. See the class KDoc's second bullet for
     * the distinction this rests on: a document's **name** is its identity and is fixed for its
     * life, while its **recorded contract versions** are a note about what it was written
     * against, which is stale the moment a plugin ships.
     *
     * ### Why §19.3 could not have been done without it
     *
     * §19.3 says the migration pass produces a patch so that the upgrade *"can be undone by the
     * editor as a single 'upgrade' transaction"*. A pass that rewrote the nodes and left
     * `componentVersions` where it found them would satisfy the first half of that sentence and
     * break the second in a way nothing reports: undo would restore every node's old shape while
     * the document still claimed to be written against the new contract, so the next load would
     * skip the migration and render a document whose rows speak two versions of the same
     * component. **The record and the nodes it describes are one edit or the undo is a lie.**
     *
     * And it cannot be waved through as a smaller deal than `Node.type`'s op. That one rewrites
     * the content of a row an editor changed on purpose; this one rewrites a number no user ever
     * typed, and there is exactly one caller for it. The asymmetry is why §19.3's need was filled
     * and `Node.type`'s is still open.
     *
     * ### The shape is [SetProp]'s, one entry at a time
     *
     * One [dev.rotalex.lutter.model.ids.ComponentType] and one nullable `Int` on each side, which
     * is [SetProp] over `Node.props` rather than [SetTheme] over the selection. The reason is
     * that this field is a **map**: `DocumentMeta.componentVersions` is
     * `Map<ComponentType, Int>`, an absent key and a key holding `null` are different facts, and
     * `old = null` therefore means "this component was not recorded at all" — which is a real
     * shape a real document has, since §5.5 gives the field an empty-map default — rather than a
     * version of zero.
     *
     * **A patch that sets the same version on both sides is legal and is a no-op**, exactly as
     * [SetProp] with equal values is. Nothing here validates that the version names a released
     * component: §19.1 makes the versions the component author's business, and a patch that
     * refuses one would be refusing to describe a document rather than describing it.
     */
    @Serializable
    @SerialName("setComponentVersion")
    public data class SetComponentVersion(
        /** Which component's recorded contract version changes. */
        public val type: ComponentType,

        /** The version recorded before, or `null` when the map had no entry for it. */
        public val old: Int?,

        /** The version recorded after, or `null` to drop the entry. */
        public val new: Int?,
    ) : PatchOp
}

/**
 * Where a node sits inside its parent: the slot, and the position in that slot.
 *
 * §26.2 writes `Placement` without declaring it, and the same file writes [InsertNodes] with
 * exactly these three fields spread across itself. One type rather than three, because the
 * three are the same fact written once: a position is a slot and an index, and a record that
 * carried only the index would have to say which slot somewhere else.
 *
 * It is not a value class over a pair, and it is not a `NodeId` plus an `Int`. Both of those
 * were rejected for the reason the model rejects untyped maps: the interesting failure is a
 * placement that names a slot its parent does not have, and that failure is worth catching in
 * a constructor signature rather than in an applier's `require`.
 */
@Serializable
public data class Placement(

    /** The parent whose slot holds the node. */
    public val parent: NodeId,

    /** Which named child area of that parent. */
    public val slot: SlotName,

    /** The position in that slot, counting from zero. */
    public val index: Int,
)

/**
 * A subtree lifted out of a document, kept whole so it can be put back where it was.
 *
 * PLAN §26.2 names `RemovedSubtree` in [PatchOp.RemoveSubtree] and declares it nowhere. The
 * first three fields are not a design so much as the observation that its placed inverse,
 * [PatchOp.InsertNodes], takes exactly those three; writing them as two records with the same
 * shape is what lets `PatchInverter` invert a removal with a field copy, and what lets a
 * command capture a subtree once and build either op from it.
 *
 * [nodes] follows [PatchOp.InsertNodes]'s convention: the whole subtree, flattened, root
 * first. The root is spelled separately because §26.2's `RemoveSubtree` names it, and because
 * the id is what the parent's slot held and therefore what the applier has to check before it
 * removes anything from that slot.
 *
 * ### Why the position is three nullables and not a reserved id
 *
 * **[PatchOp.PutNodes]'s inverse is a removal with no position**, because rows it wrote were
 * in no slot to begin with — a page root is the ordinary case. That needs a way to say *"these
 * rows existed, and nothing pointed at them"*, and the three ways to spell it were:
 *
 *  * **A reserved `NodeId`** such as a parent that cannot exist. Then an unplaced removal is
 *    indistinguishable from a removal whose parent row has been deleted, and the applier has
 *    to choose between "this capture was corrupt" and "this capture was unplaced" — and it
 *    cannot, because both look the same on a document that is mid-patch.
 *  * **A negative index.** Also a magic value, and a worse one: it is a valid `Int`, so it
 *    survives serialization and round-trips, and the only thing distinguishing it from a
 *    position is that a negative one can never match anything in a slot.
 *  * **Three nullable fields**, which is what this is. Absent is absent, on the wire and in
 *    memory, and [placement] is the one place a caller has to look to find out whether there
 *    was one. It also keeps the wire keys exactly where they were, so a `RemoveSubtree` written
 *    before this change still decodes.
 *
 * The cost is the thing `DiagnosticLocation` already pays in `:engine:model` and accepts: a state
 * that is representable and wrong, here "one null out of three", is checkable in one place.
 * [placement] is that place — it returns `null` unless all three agree, so no caller ever has
 * to combine them itself.
 *
 * `RemovedSubtree.unplaced` is the constructor the inverter uses, and it exists so that "no
 * position" is one named thing at the call site rather than three `null`s spelled out.
 */
@Serializable
public data class RemovedSubtree(

    /** The parent the subtree was lifted from, or `null` when it was not in a slot. */
    public val parent: NodeId?,

    /** The slot it was lifted from, or `null` along with [parent]. */
    public val slot: SlotName?,

    /** The position it held in that slot, or `null` along with [parent]. */
    public val index: Int?,

    /** The rows themselves, root first. Never empty. */
    public val nodes: List<Node>,
) {

    /**
     * Where these rows were lifted from, or `null` when they were not in a slot.
     *
     * A getter and not a stored field, for the reason [Patch.touchedNodes] is one: this class
     * is `@Serializable`, and a member with a backing field would be written to the wire as a
     * second, derived copy of three fields already there. It has no backing field, so the
     * encoding of a `RemoveSubtree` is unchanged by this property existing.
     *
     * `null` unless **all three** agree — two of them set is not a position, and answering
     * with half of one would hand a caller an address that names a node and a slot but no
     * place in it.
     */
    public val placement: Placement?
        get() = if (parent == null || slot == null || index == null) {
            null
        } else {
            Placement(parent, slot, index)
        }

    public companion object {

        /**
         * A capture for rows that were in no slot: [PatchOp.PutNodes]'s inverse.
         *
         * The three position fields are `null`, and [placement] answers `null` for the same
         * capture. Named rather than spelled out at the call site so that "unplaced" is one
         * thing a reader can search for.
         */
        public fun unplaced(nodes: List<Node>): RemovedSubtree = RemovedSubtree(null, null, null, nodes)
    }
}

/**
 * Where a [Record] lives in a `UiDocument`: which container, under which id.
 *
 * PLAN §26.2's `PutRecord` names this type and declares it nowhere. What it has to be is
 * fixed by the applier's shape rather than chosen: `UiDocument` holds its declarations in six
 * maps and one list, each keyed by a different typed identifier, and a reference that could
 * name any of them has to say which — otherwise "which declaration?" is a string comparison
 * and the six maps are six `when` branches with no exhaustiveness check on any of them.
 *
 * ### Why a sealed interface and not a string
 *
 * The alternative is one address string per declaration and a `when` over its first character.
 * It would be shorter and it would lose the two things this type exists to keep: the id is a
 * checked `PageId` or a checked `StateId` rather than a string nothing validates, and the
 * compiler refuses an applier that has forgotten a container. Nine arms, one per declaration
 * container, and a ninth container added later is a compile error at the two places that switch
 * on it — which is the same argument `TypeRef` makes for keeping its variants abstract on the
 * interface.
 *
 * ### Six of the eight carry nothing but an id, and the other two carry a position too
 *
 * `UiDocument.appState` and `UiDocument.hostFunctions` are `List`s and every other declaration
 * container is a map keyed by id. For a map the id *is* the address; for a list it is not,
 * because an id says which declaration and a position says where — and a document that removed
 * its third state declaration and appended a new one at the end is not the document it started
 * as, even though it holds the same declarations. [State] and [HostFunction] therefore carry
 * an index, for the same reason [Placement] does.
 *
 * Without the index an inverse would restore the right declarations in the wrong order, which
 * is a patch that undoes cleanly and leaves a document that is not the one the edit started
 * from — and a list order that means nothing is still an order. `PatchApplier` is where the
 * index is read; `DocumentDiff` is where the two-phase order it has to be issued in is
 * described.
 *
 * ### What a reference does not reach
 *
 * `Page.state` and `ComponentDecl.state` are §12.1's other two state scopes and they are not
 * addressed here. They are fields *of* a record this type does address, so a change to one of
 * them is a [PatchOp.PutRecord] of the owning page or component with a replaced record. The
 * alternative — three state scopes inside [RecordRef] — would make the reference carry a
 * triple of ids to express what one record copy already says.
 *
 * `UiDocument.theme` is the other thing this type deliberately cannot address, and for the
 * other reason: it is the *selection* of a theme rather than a theme, and a selection is
 * nullable. Every reference here names something that exists, so there is nothing to point a
 * `null` at; [PatchOp.SetTheme] carries it instead. See that op's KDoc for why the fix was an
 * op rather than a reference.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface RecordRef {

    /** An entry of `UiDocument.pages`. */
    @Serializable
    @SerialName("page")
    public data class Page(public val id: PageId) : RecordRef

    /** An entry of `UiDocument.components`, a document-defined reusable component (§5.7). */
    @Serializable
    @SerialName("component")
    public data class Component(public val id: ComponentDeclId) : RecordRef

    /** An entry of `UiDocument.themes`. Not the `theme` selection; see the interface KDoc. */
    @Serializable
    @SerialName("theme")
    public data class Theme(public val id: ThemeId) : RecordRef

    /** An entry of `UiDocument.resources` (§20). */
    @Serializable
    @SerialName("resource")
    public data class Resource(public val id: ResourceId) : RecordRef

    /**
     * One entry of `UiDocument.appState`, at [index].
     *
     * The only reference with a position in it, and the reason is in the interface KDoc:
     * `appState` is a list and every other declaration container is a map. The index is the
     * address within that list, and a [PutRecord] writes at it and removes at it, which is
     * what makes the inverse put the declaration back where it was rather than at the end.
     */
    @Serializable
    @SerialName("state")
    public data class State(public val id: StateId, public val index: Int) : RecordRef

    /** An entry of `UiDocument.dataModels`. */
    @Serializable
    @SerialName("dataModel")
    public data class DataModel(public val id: DataModelId) : RecordRef

    /** An entry of `UiDocument.enums` — the other half of `dataModels`' id space (§5.5). */
    @Serializable
    @SerialName("enum")
    public data class EnumType(public val id: TypeId) : RecordRef

    /**
     * One entry of `UiDocument.hostFunctions`, at [index].
     *
     * D31's addition, and the one reference in this hierarchy whose id is **not** a typed
     * identifier. That is not an inconsistency to be tidied away later — §11.6 declares
     * `HostFunctionDecl(name, params, returns, suspend)` and types the *arguments*, never the
     * callee, so `name` is a plain `String` in `:engine:model` and there is no checked id to
     * narrow it to here.
     *
     * `FunctionId` was the obvious candidate and is the wrong one for two reasons: it validates
     * a **namespaced** name (`list.isNotEmpty`), which would refuse the ordinary document-local
     * `submit`; and it keys the schema's *function registry*, which is a different namespace
     * entirely — §11.6's host functions are implemented by the embedding application and
     * declared by the document, not resolved through a registry. The consequence of taking a
     * `String` is the one every other variant in this hierarchy exists to prevent, so it is
     * named rather than left implicit: **a `PutRecord` here whose [id] and whose carried
     * declaration disagree on the name is refused by `PatchApplier`**, exactly as the typed
     * variants are refused for the same reason.
     *
     * [index] for the reason [State] carries one, and it is not optional: `hostFunctions` is a
     * list, a removal is checked against the declaration the position actually holds, and an
     * inverse that appended instead of restoring would produce a document holding the same
     * declarations in a different order.
     */
    @Serializable
    @SerialName("hostFunction")
    public data class HostFunction(public val id: String, public val index: Int) : RecordRef
}

/**
 * A document declaration, in the container [RecordRef] addresses.
 *
 * The sealed counterpart to [RecordRef], and it exists for one reason: `PatchOp.PutRecord`
 * has to carry a `new` and an `old` whose type says *which kind of declaration* it is. A
 * bare `Any` would be PLAN §23.4's forbidden untyped value in a position where the applier
 * immediately has to switch on it, and a `Map<PageId, Any>`-shaped record would put the union
 * back into the model.
 *
 * Each variant holds the declaration itself and not a copy of it, so the record on the wire is
 * the same record `:engine:model` already declares and there is one definition of what a
 * `ThemeDecl` is rather than a second one in the editing layer.
 *
 * The variants are named to match their [RecordRef] counterparts, which is not a style
 * accident: `PatchApplier` pairs the two in one switch per arm, and a pair whose names differ
 * would make every arm read as a lookup rather than as what it is — the two descriptions of
 * the same declaration, agreeing. It is also why [Page] spells its payload's type in full;
 * the nested name shadows the model's.
 *
 * @see RecordRef for which containers these appear in, and for the two that are not reachable.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Record {

    /** A screen: `UiDocument.pages`. */
    @Serializable
    @SerialName("page")
    public data class Page(public val value: dev.rotalex.lutter.model.doc.Page) : Record

    /** A document-defined reusable component: `UiDocument.components`. */
    @Serializable
    @SerialName("component")
    public data class Component(public val value: ComponentDecl) : Record

    /** A theme: `UiDocument.themes`. */
    @Serializable
    @SerialName("theme")
    public data class Theme(public val value: ThemeDecl) : Record

    /** A resource: `UiDocument.resources`. */
    @Serializable
    @SerialName("resource")
    public data class Resource(public val value: ResourceDecl) : Record

    /** One app-scoped state declaration: `UiDocument.appState`. */
    @Serializable
    @SerialName("state")
    public data class State(public val value: StateDecl) : Record

    /** A data model: `UiDocument.dataModels`. */
    @Serializable
    @SerialName("dataModel")
    public data class DataModel(public val value: DataModelDecl) : Record

    /** A document-declared enum type: `UiDocument.enums`. */
    @Serializable
    @SerialName("enum")
    public data class EnumType(public val value: EnumTypeDecl) : Record

    /**
     * A function the embedding application implements: `UiDocument.hostFunctions`.
     *
     * The eighth variant, added with [RecordRef.HostFunction] by D31, and it holds the
     * declaration itself rather than a copy for the reason every variant here does: there is
     * one definition of what a `HostFunctionDecl` is, and it is in `:engine:model`.
     */
    @Serializable
    @SerialName("hostFunction")
    public data class HostFunction(public val value: HostFunctionDecl) : Record
}