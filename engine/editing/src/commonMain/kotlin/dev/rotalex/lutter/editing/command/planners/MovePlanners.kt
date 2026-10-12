package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Placement
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.MoveNode].
 *
 * ### The index, and the one rule the caller has to know
 *
 * **[index] is read against the target slot once this node has been taken out of where it
 * currently is.** That is not a quirk of this planner — it is `PatchApplier.applyMove`'s rule,
 * and repeating it here is what keeps the command and the op meaning the same thing.
 *
 * The rule only bites when the two placements name the same slot, which is the common case:
 * moving the third child of a `Column` to the front says index 0, and after the detach index 0
 * is the second child, so the move does what the user drew. It is *not* an off-by-one to be
 * corrected for; adjusting it here would put the adjustment on one side of the inverse and
 * not the other, and §26.2's property would fail exactly when the move was within one slot.
 *
 * ### The two checks a move needs and an insertion does not
 *
 * **A cycle.** A node moved into its own subtree becomes its own ancestor, and §6.2 forbids
 * that. `PatchApplier` would apply the patch and leave a document whose walk never returns —
 * it has no view of the tree, only of the slot it is editing — so the check is here.
 *
 * **Cardinality against the vacated slot.** For a move within one slot the count does not
 * change, and re-checking it would refuse a reorder of a slot that was already over-full,
 * which is a document §17.1 reports rather than an edit to refuse.
 */
internal fun planMoveNode(
    command: Command.MoveNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to move")
        return reasons.result(emptyList())
    }

    val under = DocumentIndex(document)
    val from = under.parentOf(command.id)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.id,
            "'${command.id}' is a root, and a root's position is decided by the page that owns it",
        )
        return reasons.result(emptyList())
    }

    val parent: Node? = document.nodes[command.newParent]
    if (parent == null) {
        reasons.add(EditCodes.UnknownNode, command.newParent, "no node '${command.newParent}' to move into")
        return reasons.result(emptyList())
    }

    val spec: ComponentSpec? = specOf(parent, components)
    if (spec == null) {
        reasons.add(
            EditCodes.UnknownComponent,
            parent.id,
            "'${parent.type}' is not registered, so its slots cannot be read",
            mapOf("component" to parent.type.value),
        )
        return reasons.result(emptyList())
    }

    // The index is against the slot as the applier will see it, which is after the detach.
    val withinSameSlot = from.parentId == command.newParent && from.slot == command.slot
    val occupied: List<NodeId> = if (withinSameSlot) {
        parent.slots[from.slot].orEmpty().filter { it != command.id }
    } else {
        parent.slots[command.slot].orEmpty()
    }
    if (command.index !in 0..occupied.size) {
        reasons.add(
            EditCodes.IndexOutOfRange,
            parent.id,
            "index ${command.index} is outside 0..${occupied.size} of '${command.slot}' " +
                "once '${command.id}' has left its current position",
            mapOf("slot" to command.slot.value, "index" to command.index.toString()),
        )
        return reasons.result(emptyList())
    }

    // A move puts `command.id` under `command.newParent`, so a cycle exists exactly when the
    // new parent is the node itself or one of its own descendants. Note the direction: asking
    // whether `id` is a descendant of the *target* would refuse a node being lifted out of
    // that subtree, which is the opposite of the move a tree view makes on every drag.
    if (command.id == command.newParent || command.newParent in under.descendants(command.id)) {
        reasons.add(
            EditCodes.Cycle,
            command.id,
            "moving '${command.id}' into '${command.newParent}' would make it its own ancestor",
        )
        return reasons.result(emptyList())
    }

    if (!withinSameSlot) {
        reasons.addAll(
            slotRejections(
                spec = spec,
                parent = parent.id,
                slot = command.slot,
                childType = node.type,
                countAfter = occupied.size + 1,
            ),
        )
        if (!reasons.isEmpty) return reasons.result(emptyList())
    }

    return reasons.result(
        listOf(
            PatchOp.MoveNode(
                id = command.id,
                from = Placement(from.parentId, from.slot, from.index),
                to = Placement(command.newParent, command.slot, command.index),
            ),
        ),
    )
}