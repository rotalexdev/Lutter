package dev.rotalex.lutter.editing.template

import dev.rotalex.lutter.model.doc.Node

/**
 * A subtree held for insertion: the rows, root first, and nothing else.
 *
 * PLAN §25.4 names this in the clipboard row — *"NodeTemplate.capture(doc, ids) /
 * InsertNode(...) with IdRemapper"* — and §33.6 files it at `template/NodeTemplate.kt` with
 * `IdRemapper.kt`, which is a different work unit. Only the **shape** is declared here,
 * because [dev.rotalex.lutter.editing.command.Command.InsertNode] and
 * [dev.rotalex.lutter.editing.command.Command.AddPage] cannot be written without a type to
 * carry a subtree, and a command set with two holes in it is not §25.3's closed set.
 *
 * What is deliberately *not* here is the clipboard half: `capture`, `IdRemapper` and the
 * id-minting a paste needs. Those are decisions about what a paste *is* — does a cut remove,
 * does a paste renumber, what happens to a reference into a removed subtree — and guessing
 * them here would be the same mistake as inventing `ExtractComponent`'s semantics. The
 * smallest remap a duplicate needs lives beside the planners that need it and is marked for
 * that move.
 *
 * ### Why the shape is [nodes] and not a root plus a map of children
 *
 * Because it is exactly the shape [dev.rotalex.lutter.editing.patch.PatchOp.InsertNodes]
 * takes and the shape [dev.rotalex.lutter.editing.patch.RemovedSubtree] captures: the whole
 * subtree, flattened, root first. §5.6 normalized the document so that a child is a
 * reference rather than a nested value, and a template that reintroduced nesting would be one
 * operation away from the tree §5.6 rejected — and `NodeTable.with` would happily write the
 * rows while the children went in as values.
 *
 * Root first, for the same reason `InsertNodes` says it: the consumer must be able to walk
 * the list once without sorting, and the first element is the only one that goes into the
 * parent's slot.
 *
 * ### Total by construction, and why the emptiness check is not here
 *
 * There is no `require(nodes.isNotEmpty())`. `PatchApplier` refuses an empty `InsertNodes`
 * at apply time with an `IllegalArgumentException`, and a planner is the right place to
 * refuse it instead: a rejection is a value an editor can show and an exception is a crash.
 * Keeping the check there means [root] is a total property of the record and the emptiness
 * question has exactly one answer at the layer that has a document in hand.
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
}