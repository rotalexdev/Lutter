package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName

/**
 * What the user asked for, before the engine has worked out how to do it.
 *
 * PLAN §25.3 declares a closed set and this is that set with the plan's names and the plan's
 * fields, in the plan's order. Twelve variants, not thirteen: [ExtractComponent] is deferred,
 * and the reason is in its own paragraph below rather than in a commit message.
 *
 * Every one of the twelve now plans. [AddPage] did not until D30 added the op that made it
 * expressible, and the refusal that stood in its place carried a code — `edit.unrepresentable` —
 * that no longer exists, because there is nothing left for it to be true of.
 *
 * ### Intent and mechanism are two types on purpose
 *
 * §26.1 chose *"Command → forward/inverse Patch"* over a snapshot per step precisely because
 * the two ends of that arrow are different kinds of thing. A [Command] says *what* — "this
 * node goes into that slot at that index" — and knows nothing about rows, tables or
 * inverses. A `Patch` says *how*, carries the old value of everything it replaces, and is the
 * only thing [dev.rotalex.lutter.editing.patch.PatchApplier] accepts. Conflating them would
 * put a second way to change a document next to the one writer `PatchApplier`'s KDoc
 * forbids, and §26.2's property would become a property of two code paths that happened to
 * agree.
 *
 * ### Not serializable, and the asymmetry with `PatchOp` is a decision
 *
 * `PatchOp` is `@Serializable` because §26.1's rejected "full snapshot per step" is what the
 * patch replaces, and a patch that cannot cross a process boundary cannot be logged, shipped
 * to a collaborating editor or replayed in a test. This interface is not, and does not need
 * to be. §26.3's own rule is that history is *"editor session state; it is not persisted in
 * the document"* — so a command is the same kind of thing. A wire format for commands is a
 * decision for whoever wants one, and adding `@Serializable` to a closed set of twelve is
 * cheaper to do later than to take back.
 *
 * ### What the closed set buys
 *
 * [CommandPlanner]'s dispatch is a `when` over this interface with no `else`, so a thirteenth
 * command does not compile until it has been given a planner — which is a far stronger prompt
 * than a reviewer noticing. It is the same argument [PatchApplier.applyOne] makes for the op
 * set, and the reason both sets are closed is the same one: every addition is paid for twice,
 * once where the thing is produced and once where it is consumed, and an addition only one of
 * them remembered is a silently wrong half.
 *
 * ### The one variant that is absent, and why
 *
 * **§25.3's `ExtractComponent(root, name)` is deferred, and the plan says so twice.** Its own
 * line in §25.3 ends *"(subtree → ComponentDecl (wave 2))"*, and §32 files "Component wave 2"
 * as post-MVP Phase 11 — the roadmap table, not Phase 9's task list, which names "command
 * planners with schema policy checks" and nothing about extraction.
 *
 * Declaring it without a planner would mean inventing the semantics, and they are not
 * obvious: which `ComponentDeclId` the new declaration gets, how its `params` are derived
 * from the subtree, what happens to a `Page.root` that named one of the extracted nodes, and
 * whether the extracted body's root keeps its id. Each of those is a document-shape decision
 * the plan has not made, and guessing four of them to fill a `when` arm is worse than an
 * absent variant that is documented. It belongs to the wave-2 work unit, where the extraction
 * rules and the lazy `doc.*` synthesis have the same author.
 *
 * @see CommandResult for what planning one of these returns.
 * @see CommandPlanner for where legality is decided.
 */
public sealed interface Command {

    /**
     * A subtree placed into a parent's slot at a position.
     *
     * [index] is absolute in the slot as it is when the command runs, and `slots.size` appends.
     * The same convention every `PatchOp` index uses — see [PatchOp]'s "Indices are absolute,
     * never relative" — and for the same reason: the document this was planned against does
     * not exist any more once the patch is applied somewhere else.
     *
     * The ids in [subtree] are the ids the inserted rows carry. They must not already be in
     * the document; §6.2 assigns an id once and never reuses it, and `NodeTable.with`
     * *replaces* on a collision, so a duplicate id would silently delete a different node
     * rather than fail. The planner refuses one rather than discovering it at apply time.
     */
    public data class InsertNode(
        /** The node whose slot receives the subtree. */
        public val parent: NodeId,

        /** Which named child area of that parent. */
        public val slot: SlotName,

        /** Where in that slot, counting from zero. */
        public val index: Int,

        /** The rows to place, root first. */
        public val subtree: NodeTemplate,
    ) : Command

    /**
     * A subtree lifted out of the document.
     *
     * The whole subtree goes with the root, not just the root: §6.2 gives every node exactly
     * one parent, so leaving the children behind would make them orphans rather than free
     * them. A node no parent lists — a page or component root — cannot be removed, and the
     * planner refuses it rather than leaving the page pointing at a node that is gone.
     */
    public data class RemoveNode(
        /** The root of the subtree to lift out. */
        public val id: NodeId,
    ) : Command

    /**
     * One node repositioned, within a slot or between two.
     *
     * **[index] is the position in the target slot *after* the node has been vacated from its
     * current one**, because that is how [PatchOp.MoveNode.to] is read and the two must mean
     * the same thing. It only bites when the two placements name the same slot; across
     * different parents the two lists are independent and the number is just an index.
     *
     * The move does not touch the node's row: §5.6 normalized the document so that position
     * lives in a parent's slot and nowhere else, which is what makes this two record rewrites
     * rather than a tree splice.
     */
    public data class MoveNode(
        /** The node being moved. Its record is unchanged; only its position is. */
        public val id: NodeId,

        /** The node whose slot receives it. */
        public val newParent: NodeId,

        /** Which named child area of that parent. */
        public val slot: SlotName,

        /** Where in that slot, once this node has been taken out of where it is now. */
        public val index: Int,
    ) : Command

    /**
     * The node's subtree copied into place next to itself, under fresh ids.
     *
     * Fresh ids are not a detail of this command; they are what makes it legal. §6.2 assigns
     * an id once and never reuses it, so a copy that kept them would be a second node with
     * the first one's identity — and `NodeTable.with` would answer that collision by
     * replacing the original. The copy lands immediately after the node it was copied from.
     */
    public data class DuplicateNode(
        /** The root of the subtree to copy. */
        public val id: NodeId,
    ) : Command

    /**
     * A new component placed around a node, which moves into one of its slots.
     *
     * The node keeps its id, its props and its place in the layout; only its parent changes.
     * The wrapper takes the position the node held, so a layout does not jump when a box is
     * dropped around something.
     */
    public data class WrapNode(
        /** The node to move into the wrapper. */
        public val id: NodeId,

        /** The component type to wrap it in. */
        public val wrapper: ComponentType,

        /** Which of the wrapper's slots receives the node. */
        public val slot: SlotName,
    ) : Command

    /**
     * A node removed and its children lifted into its place.
     *
     * The inverse of [WrapNode] for the case where the wrapper adds nothing: the children
     * keep their ids and their order and take the node's position in the parent's slot. The
     * children of a node with several slots arrive in slot-name order, which is a
     * determinism choice and not a claim about what the author meant.
     */
    public data class UnwrapNode(
        /** The node to dissolve. */
        public val id: NodeId,
    ) : Command

    /**
     * One entry of a node's `props` map set, replaced or cleared.
     *
     * [value] is nullable and `null` clears the key, for the reason [PatchOp.SetProp] gives:
     * `Node.props` is a map, so an absent entry and an entry holding null are different facts.
     *
     * **The value is not type-checked here.** That is §17's pass 4 answering
     * `prop.type_mismatch`, and §25.3 names the two policies a planner consults — a slot's
     * `accepts` and a slot's `cardinality` — and they are both about slots.
     */
    public data class SetProperty(
        /** The node whose properties change. */
        public val node: NodeId,

        /** Which entry. */
        public val key: PropertyKey,

        /** The value to write, or `null` to clear the key. */
        public val value: PropertyValue?,
    ) : Command

    /**
     * A node's whole modifier chain replaced at once.
     *
     * The entire list and not one entry, for the reason [PatchOp.SetModifiers] gives: order is
     * the meaning. No schema check either — a modifier a component does not accept is
     * `modifier.unknown`, which is §17 pass 3's, and §25.3 does not name it here.
     */
    public data class SetModifiers(
        /** The node whose modifier chain changes. */
        public val node: NodeId,

        /** The chain to write, in application order. */
        public val modifiers: List<ModifierEntry>,
    ) : Command

    /**
     * One event handler on a node set or cleared.
     *
     * [actions] is nullable and `null` removes the handler, for the same reason [value] is on
     * [SetProperty]: `Node.events` is a map and an absent entry is not an empty one.
     */
    public data class SetEventHandler(
        /** The node whose handlers change. */
        public val node: NodeId,

        /** Which handler. */
        public val event: EventKey,

        /** The sequence to write, or `null` to remove the handler. */
        public val actions: ActionSequence?,
    ) : Command

    /**
     * A node's human-facing label changed or cleared.
     *
     * `Node.name` is a nullable label that nothing validates and nothing resolves, so this
     * command can be planned with no schema at all. **It is not a page rename**, which changes
     * generated identifiers and is a `PutRecord` of the page rather than of a node.
     */
    public data class RenameNode(
        /** The node to relabel. */
        public val id: NodeId,

        /** The label to write, or `null` to clear it. */
        public val name: String?,
    ) : Command

/**
     * A screen added to the document, together with the tree it starts at.
     *
     * [page]'s `root` must name the first row of [subtree]. §5.5 makes that pointer the node
     * a screen's tree starts at, so a page naming anything else describes a tree the author
     * never wrote — and nothing in §17 would notice, because the root would resolve and the
     * walk would start somewhere real. The planner refuses it.
     *
     * This command needed a ninth `PatchOp` to exist at all, and D30 is what added it: the
     * original eight could put rows into a *slot* but not into the *table*, and a page root is
     * exactly the row no slot references. It is worth recording that the planner refused this
     * command as unrepresentable before D30, because that was the honest answer to a §25.3
     * that could not be expressed — and worth recording too that it was reported rather than
     * papered over with a `PutRecord` that would have produced a document failing later.
     */
    public data class AddPage(
        /** The screen to add. Its `root` names the first row of [subtree]. */
        public val page: Page,

        /** The rows the screen's tree starts at, root first. */
        public val subtree: NodeTemplate,
    ) : Command

    /**
     * A patch replayed as if a command had produced it.
     *
     * The one variant that plans to itself. It exists so that a patch arriving from somewhere
     * the engine does not model — a diff of two documents, a migration, a collaborator's
     * queue — can enter the same history and the same undo stack as a local edit instead of
     * needing a parallel mechanism for "an edit that did not come from here".
     *
     * It is the one path into a document that is **not** schema-checked, and that is
     * deliberate rather than an oversight: the ops were built against a document this planner
     * has not been shown, and the legality of a patch against a document is
     * `PatchApplier`'s question — it compares what each op claims against what the document
     * holds and refuses the patch that does not fit.
     */
    public data class ApplyPatch(
        /** The patch to apply as one entry. */
        public val patch: Patch,
    ) : Command
}