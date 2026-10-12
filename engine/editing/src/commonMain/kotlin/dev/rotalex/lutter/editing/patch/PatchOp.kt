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
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
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
 * PLAN §26.2 declares eight, and this file declares eight with the plan's names and the plan's
 * field names, in the plan's order. Nothing is added, because every addition here is paid for
 * twice: `PatchApplier` grows a branch and `PatchInverter` grows a branch, and a ninth op that
 * only one of the two remembered is a patch whose inverse is silently wrong.
 *
 * ### Why these eight, and what the set is for
 *
 * They are not "every change the engine can make". They are the changes §26.1 chose for a
 * command to produce — *"Command → forward/inverse Patch"*, chosen over a snapshot per step
 * because it gives exact semantics and a mechanism that diffing, syncing and undo all reuse.
 * §33.6's remaining editing files (`DocumentDiff`, `SpecMigrationPass`, the planners) are
 * consumers of this vocabulary, not extensions of it: the migration pass is explicitly "apply
 * component migrations as a patch", and the diff is a patch between two documents.
 *
 * ### Every op carries its own `old`, and that is the property being bought
 *
 * Six of the eight have a field literally named `old`; [InsertNodes] and [RemoveSubtree] carry
 * the whole subtree they inserted or captured instead, which is the same information with a
 * different shape. The consequence is the one §26.2 draws out immediately after the
 * declaration: **inverting a patch needs no document.** There is no lookup, no index, no
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
 *  * **`UiDocument.meta`, `UiDocument.app` and `UiDocument.theme` have no op.** The first two
 *    are fixed for the life of a document, and the third is the *selection* of a theme rather
 *    than a theme — §14.2's nullable id with a default, which no `PutRecord` can express,
 *    because [RecordRef.Theme] addresses a `ThemeDecl` and not the document's pointer at one.
 *    A theme switch is a gap in §26.2's set, and it is a real one; the fix is a ninth op, and
 *    a ninth op is a decision for whoever owns the plan rather than a guess for this one.
 *  * **`UiDocument.hostFunctions` has no [RecordRef].** §26.2's own comment enumerates what
 *    `PutRecord` covers — *"pages, decls, themes, resources, state, models"* — and host
 *    functions are not in it. Adding a variant the comment does not sanction would be a
 *    decision made by transcription rather than by reading.
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
     *   before anybody has a document to apply it to.
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
 * PLAN §26.2 names `RemovedSubtree` in [PatchOp.RemoveSubtree] and declares it nowhere, and
 * the four fields are not a design so much as the observation that its inverse,
 * [PatchOp.InsertNodes], takes exactly these four. Writing them as two records with the same
 * shape is what lets `PatchInverter` invert a removal with a field copy, and what lets a
 * command capture a subtree once and build either op from it.
 *
 * [nodes] follows [PatchOp.InsertNodes]'s convention: the whole subtree, flattened, root
 * first. [root] is spelled separately because §26.2's `RemoveSubtree` names it, and because
 * the id is what the parent's slot held and therefore what the applier has to check before it
 * removes anything from that slot.
 */
@Serializable
public data class RemovedSubtree(

    /** The parent the subtree was lifted from. */
    public val parent: NodeId,

    /** The slot it was lifted from. */
    public val slot: SlotName,

    /** The position it held in that slot. */
    public val index: Int,

    /** The rows themselves, root first. Never empty. */
    public val nodes: List<Node>,
)

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
 * compiler refuses an applier that has forgotten a container. Seven arms, one per declaration,
 * and a sixth container added later is a compile error at the two places that switch on it —
 * which is the same argument `TypeRef` makes for keeping its variants abstract on the
 * interface.
 *
 * ### Six of the seven carry nothing but an id, and the seventh carries a position too
 *
 * `UiDocument.appState` is a `List<StateDecl>` and every other declaration container is a map
 * keyed by id. For a map the id *is* the address; for a list it is not, because an id says
 * which declaration and a position says where — and a document that removed its third state
 * declaration and appended a new one at the end is not the document it started as, even though
 * it holds the same declarations. [State] therefore carries an index, for the same reason
 * [Placement] does.
 *
 * ### What a reference does not reach
 *
 * `Page.state` and `ComponentDecl.state` are §12.1's other two state scopes and they are not
 * addressed here. They are fields *of* a record this type does address, so a change to one of
 * them is a [PatchOp.PutRecord] of the owning page or component with a replaced record. The
 * alternative — three state scopes inside [RecordRef] — would make the reference carry a
 * triple of ids to express what one record copy already says.
 *
 * `UiDocument.theme` is the one thing this type deliberately cannot address: it is the
 * *selection* of a theme rather than a theme, and §26.2's op set has nothing for it. See
 * [PatchOp]'s KDoc.
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
}