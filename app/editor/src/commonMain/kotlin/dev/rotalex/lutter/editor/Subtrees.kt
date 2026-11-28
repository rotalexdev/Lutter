package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId

/**
 * [id] and every node below it.
 *
 * A drag may not drop a node into its own subtree: the move would cut the branch out from under
 * the node that is being moved into it, and the planner refuses it. Refusing it *here* — by not
 * offering those containers as targets while the finger is down — is the difference between a
 * highlight that lies and a highlight that is only ever drawn where the drop would work.
 *
 * The same walk answers the delete question for a multi-selection: a selected node inside another
 * selected node's subtree is not a second thing to remove, and asking the planner to remove it
 * would refuse the second command against a document where it no longer exists.
 */
internal fun subtreeOf(document: UiDocument, id: NodeId): Set<NodeId> {
    val seen: MutableSet<NodeId> = mutableSetOf()
    fun walk(current: NodeId) {
        if (!seen.add(current)) return
        val node = document.nodes[current] ?: return
        for (children in node.slots.values) for (child in children) walk(child)
    }
    walk(id)
    return seen
}

/**
 * The members of [ids] that no other member already contains.
 *
 * Removing a container takes its children with it, so a selection that names both would dispatch
 * a second removal for a node the first one already deleted. Filtering to the roots keeps the
 * commands independent of each other, which is what lets them share one transaction.
 */
internal fun rootsOf(document: UiDocument, ids: Set<NodeId>): List<NodeId> = ids.filter { id ->
    ids.none { other -> other != id && id in subtreeOf(document, other) }
}
