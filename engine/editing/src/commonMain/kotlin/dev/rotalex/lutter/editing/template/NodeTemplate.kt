package dev.rotalex.lutter.editing.template

import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId

/**
 * A subtree held for insertion: the rows, root first, and nothing else.
 *
 * PLAN §25.4 names this in the clipboard row — *"NodeTemplate.capture(doc, ids) /
 * InsertNode(...) with IdRemapper"* — and §33.6 files it at `template/NodeTemplate.kt` beside
 * `IdRemapper.kt`. Both halves are here now, and both are the clipboard: a **capture** that
 * takes a subtree out of a document, and an **insertion** that puts one somewhere else, which
 * is [dev.rotalex.lutter.editing.command.Command.InsertNode] carrying this type. What turns a
 * capture into an insertion is [IdRemapper], because the rows that come out of a document
 * carry the ids the document already uses and §6.2 assigns an id once.
 *
 * ### Why the shape is [nodes] and not a root plus a map of children
 *
 * Because it is exactly the shape [PatchOp.InsertNodes] takes and the shape
 * [dev.rotalex.lutter.editing.patch.RemovedSubtree] captures: the whole subtree, flattened,
 * root first. §5.6 normalized the document so that a child is a reference rather than a nested
 * value, and a template that reintroduced nesting would be one operation away from the tree
 * §5.6 rejected — and `NodeTable.with` would happily write the rows while the children went in
 * as values.
 *
 * Root first, for the same reason `InsertNodes` says it: the consumer must be able to walk the
 * list once without sorting, and the first element is the only one that goes into the parent's
 * slot. §5.6's normalized table and §5.7's templates meet here and nowhere else — a palette
 * entry ("Login form") is a snapshot of rows, and instantiating it is an editing command, not
 * a core-model concept.
 *
 * ### Total by construction, and why the emptiness check is not here
 *
 * There is no `require(nodes.isNotEmpty())`. `PatchApplier` refuses an empty `InsertNodes`
 * at apply time with an `IllegalArgumentException`, and a planner is the right place to
 * refuse it instead: a rejection is a value an editor can show and an exception is a crash.
 * Keeping the check there means [root] is a total property of the record and the emptiness
 * question has exactly one answer at the layer that has a document in hand.
 *
 * @see IdRemapper for the ids a captured subtree has to be given before it can go anywhere.
 * @see dev.rotalex.lutter.editing.policy.DropTargets for where a captured subtree may go.
 */
public data class NodeTemplate(

    /** The rows, root first. May be empty; the planner refuses one that is. */
    public val nodes: List<Node>,
) {

    /**
     * The node that goes into the parent's slot — [nodes]' first element, or `null` when the
     * template holds nothing.
     *
     * A getter and not a stored field: it is [nodes]' head, and a second source of truth for
     * an id is the redundancy §6.2's "one fact, one place" is about.
     */
    public val root: Node?
        get() = nodes.firstOrNull()

    public companion object {

        /**
         * The subtree under each of [ids], out of [document], as one template.
         *
         * §25.4 spells the call `capture(doc, ids)` and this is it: the ids are **the roots to
         * take**, not a generator and not the set of rows to copy. A clipboard holds one
         * selection, a selection may hold several nodes, and each of them comes with its own
         * descendants — §6.2 gives every node exactly one parent, so a subtree that left its
         * children behind would orphan them rather than copy them.
         *
         * ### The two refusals, and why they are exceptions rather than diagnostics
         *
         * A planner answers a question about a document and reports "no" as a value. This
         * function is not a planner: it is the reader a paste is built from, and the two
         * things it refuses are not answers to "may this edit be made" but **a document that
         * cannot be read as a subtree at all**.
         *
         *  * **An id the table does not hold.** There is no subtree there. Returning an empty
         *    template would turn a caller's mistake into `edit.empty_subtree`, a refusal about
         *    an edit, when the truth is that nothing was ever captured.
         *  * **A subtree that does not close** — a row listing a child the document does not
         *    hold. The rows would come back fine and the template would carry a reference to a
         *    node outside itself. Inserting it would write a row pointing at a node that does
         *    not exist, and §17.1's structural pass is what would report it, several layers
         *    away from the paste that caused it. The planners cannot catch it either:
         *    `Command.InsertNode`'s id checks are about the document, and a row that is not in
         *    the document yet is exactly the one they cannot see. So the invariant is
         *    established here, where the capture is taken, and a violation is loud.
         *
         * The same reasoning is why `Command.InsertNode`'s planner can treat a template as
         * already closed: by the time one is in hand, this function or [IdRemapper] put it
         * there.
         *
         * ### Overlapping roots are collected once, in the order they were named
         *
         * A selection naming a node and its own descendant asks for both subtrees, and the
         * descendant's rows arrive with its ancestor's. Rows already collected are not
         * collected twice, so the result is still a set of complete subtrees rather than one
         * subtree with a hole in the middle — and it is still root first, because each root's
         * rows are appended whole.
         *
         * @throws IllegalArgumentException if an id in [ids] is not in [document], or if the
         *   captured rows reference a child the document does not hold.
         */
        public fun capture(document: UiDocument, ids: Collection<NodeId>): NodeTemplate {
            val collected: MutableList<Node> = mutableListOf()
            val seen: MutableSet<NodeId> = mutableSetOf()

            for (root in ids) {
                require(root in document.nodes) {
                    "NodeTemplate.capture cannot capture '$root': the document holds no node with " +
                        "that id, so there is no subtree to take"
                }
                for (row in subtreeRows(document, root)) {
                    if (seen.add(row.id)) collected += row
                }
            }

            for (row in collected) {
                val hole: NodeId? = danglingChildren(document, row).firstOrNull()
                require(hole == null) {
                    "NodeTemplate.capture cannot capture '${row.id}': it lists child '$hole', " +
                        "which the document does not hold, so the subtree would not be closed"
                }
            }

            return NodeTemplate(collected)
        }
    }
}

/**
 * Every row of the subtree rooted at [root]: the root, then its descendants depth first.
 *
 * Parents before their children, which is the only ordering the two things that carry a
 * subtree need — [PatchOp.InsertNodes] places the first element and writes the rest into the
 * table, [dev.rotalex.lutter.editing.patch.RemovedSubtree] deletes them all, and this file's
 * [NodeTemplate.capture] hands them to a caller as a template. Within that, the order is fixed
 * rather than incidental for two reasons: children are visited in slot-name order so a node's
 * children come back the same way on every target, because §6.2's determinism is a claim about
 * documents and this function feeds the patch that produces them; and a `seen` set stops a
 * cycle, because §6.2's invariants are §17.1's to report and an index built by walking
 * malformed input must terminate anyway.
 *
 * A child id the table does not hold is skipped rather than invented — `null` is the only
 * honest answer for a row that is not there — and the caller that needs to know asks
 * [danglingChildren], because a skipped child is a structural fault and this is a walk.
 *
 * ### Why it lives here and not beside the planners
 *
 * Three things walk a subtree: `Command.RemoveNode`'s planner, `Command.DuplicateNode`'s, and
 * [NodeTemplate.capture]. They must produce the same rows in the same order, because two walks
 * that disagreed would put a subtree's root first on one path and not on the other — and
 * `PatchApplier` places `nodes.first()`. So the walk is written once, here, and the planners
 * import it. The direction that makes that possible without a package cycle is this one:
 * `command` already depends on `template`, because `Command.InsertNode` carries a
 * [NodeTemplate], and it would be a cycle the other way round.
 */
internal fun subtreeRows(document: UiDocument, root: NodeId): List<Node> {
    val collected: MutableList<Node> = mutableListOf()
    val seen: MutableSet<NodeId> = mutableSetOf(root)
    val stack: MutableList<NodeId> = mutableListOf(root)

    while (stack.isNotEmpty()) {
        val id: NodeId = stack.removeAt(stack.lastIndex)
        val node: Node = document.nodes[id] ?: continue
        collected += node
        val children: List<NodeId> = node.slots.entries
            .sortedBy { it.key.value }
            .flatMap { it.value }
        // Reversed, so the first child is popped first and a node's own subtree is walked
        // before its next sibling's.
        for (index in children.indices.reversed()) {
            val child: NodeId = children[index]
            if (seen.add(child)) stack += child
        }
    }
    return collected
}

/** The child ids [node] lists that the table does not hold. §17.1 reports them; we refuse on them. */
internal fun danglingChildren(document: UiDocument, node: Node): List<NodeId> =
    node.slots.values.flatten().filter { it !in document.nodes }