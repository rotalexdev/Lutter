package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.RemoveNode].
 *
 * The smallest planner in the set, and the one that most clearly shows what a planner is for:
 * the patch is a field copy, and everything worth having is in the refusals.
 *
 * ### Why a root cannot be removed
 *
 * §6.2 gives every node exactly one parent, and a page's root has none — it is pointed at by
 * `Page.root`. Removing it would leave the page naming a node the table does not hold, and
 * the failure arrives later as `NodeTable.require`'s `IllegalStateException` from whatever
 * walks the page next. So it is refused here, where the editor can say *why* in one sentence.
 *
 * ### The capture has to be the whole subtree, and has to say where it came from
 *
 * `RemovedSubtree` is the only op that could be undone: the node it names is gone once the
 * op runs, so the inverse — an insertion — has to be told the slot and the index from somewhere,
 * and the rows from somewhere else. Both live in the capture, which is why the plan is two
 * reads of the document rather than one: [DocumentIndex] for the position, [subtreeRows] for
 * the rows. A child left behind would be an orphan rather than a free node, so the capture
 * starts at the named node and does not stop at its direct children.
 */
internal fun planRemoveNode(
    command: Command.RemoveNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to remove")
        return reasons.result(emptyList())
    }

    val from = DocumentIndex(document).parentOf(command.id)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.id,
            "'${command.id}' is a root; removing it would leave its page pointing at nothing",
            mapOf("node" to command.id.value),
        )
        return reasons.result(emptyList())
    }

    val rows: List<Node> = subtreeRows(document, command.id)
    return reasons.result(
        listOf(PatchOp.RemoveSubtree(command.id, RemovedSubtree(from.parentId, from.slot, from.index, rows))),
    )
}