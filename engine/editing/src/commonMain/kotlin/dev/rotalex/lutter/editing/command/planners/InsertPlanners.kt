package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.template.IdRemapper
import dev.rotalex.lutter.editing.template.NodeTemplate
import dev.rotalex.lutter.editing.template.danglingChildren
import dev.rotalex.lutter.editing.template.subtreeRows
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.InsertNode] and [Command.DuplicateNode]: the two commands that put rows into the
 * table.
 *
 * They are one file because they are one question — *may these rows go here?* — asked about
 * two different things: a subtree the caller already holds, and one that has to be copied
 * first. Everything after that question is the same checks in the same order.
 *
 * ### The order of the checks, and why it is this one
 *
 * Existence first, then the index, then id freedom, then the schema. Each step after the
 * first reads something the step before established, so a command naming a node that is not
 * there is refused for that and not for the four other things also wrong with it — which
 * matters, because a user who dropped a node into a deleted container should be told the
 * container is gone, not that a slot is full.
 *
 * ### What is *not* checked about the inserted root
 *
 * **Whether its component type is registered.** A document naming a component this engine has
 * never heard of still reads, still round-trips and is reported by §17 pass 4 as
 * `component.unknown`; that is D4's rule and it is about *nodes*. Nothing about legality
 * needs the child's spec — a `ChildFilter` is a set of component types, so membership is
 * answered from the type alone. Refusing here would make an editor unable to place a node
 * whose plugin has not registered its specs yet, which is the opposite of what D4 is for.
 *
 * The parent's spec is the other way round, and it is refused: without it there is no way to
 * know whether the slot exists, and planning an edit that cannot be certified is the failure
 * §25.3 exists to prevent.
 */
internal fun planInsertNode(
    command: Command.InsertNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val parent: Node? = document.nodes[command.parent]
    if (parent == null) {
        reasons.add(
            EditCodes.UnknownNode,
            command.parent,
            "no node '${command.parent}' to insert into",
            mapOf("node" to command.parent.value),
        )
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

    val subtree: List<Node> = command.subtree.nodes
    val root: Node? = subtree.firstOrNull()
    if (root == null) {
        reasons.add(EditCodes.EmptySubtree, command.parent, "the subtree to insert holds no nodes")
        return reasons.result(emptyList())
    }

    val siblings: List<NodeId> = parent.slots[command.slot].orEmpty()

    // Whether the parent spells this slot at all, read from the document rather than defaulted.
    // `PatchApplier` drops a slot key that this insertion empties only when this is `false`, so
    // it is the difference between undoing an insertion into `{}` and undoing one into
    // `{"content":[]}` — two documents no other check in the engine can tell apart, and D33's
    // field is the only place either one is written down. A parent whose component declares the
    // slot and whose row has never spelled it is the ordinary case here, not an exotic one.
    val slotExisted: Boolean = parent.slots.containsKey(command.slot)

    if (command.index !in 0..siblings.size) {
        reasons.add(
            EditCodes.IndexOutOfRange,
            command.parent,
            "index ${command.index} is outside 0..${siblings.size} of '${command.slot}'",
            mapOf("slot" to command.slot.value, "index" to command.index.toString()),
        )
        return reasons.result(emptyList())
    }

    // §6.2: an id is assigned once and never reused. `NodeTable.with` answers a collision by
    // *replacing* the row, so a duplicate id here would not fail — it would delete a
    // different node, which is the failure no existence check anywhere in the engine reports.
    val taken: List<Node> = subtree.filter { it.id in document.nodes }
    if (taken.isNotEmpty()) {
        reasons.add(
            EditCodes.DuplicateId,
            taken.first().id,
            "the subtree carries id '${taken.first().id}', which the document already holds",
            mapOf("node" to taken.first().id.value),
        )
        return reasons.result(emptyList())
    }

    reasons.addAll(
        slotRejections(
            spec = spec,
            parent = parent.id,
            slot = command.slot,
            childType = root.type,
            countAfter = siblings.size + 1,
        ),
    )
    if (!reasons.isEmpty) return reasons.result(emptyList())

    return reasons.result(
        listOf(
            PatchOp.InsertNodes(
                parent = parent.id,
                slot = command.slot,
                index = command.index,
                nodes = subtree,
                slotExisted = slotExisted,
            ),
        ),
    )
}

/**
 * [Command.DuplicateNode]: the node's subtree copied into the slot immediately after it,
 * under ids the document is not already using.
 *
 * The copy is an insertion and not a move, because §6.2 says a move keeps ids and a duplicate
 * does not — the same op with a different consequence. Everything after the capture is
 * [planInsertNode]'s work, which is why this is in the same file.
 *
 * **The ids come from [IdRemapper]**, the same class the clipboard uses, so there is one
 * answer to "what id does this copy get" in this module. §33.6 files it under `template`,
 * where the paste that needs it more lives; §6.2's row — *"Duplicate/paste generates new ids
 * (`IdRemapper`)"* — puts both words in one sentence for the same reason.
 *
 * The generator's contract is checked rather than trusted. `IdGenerator.nextNodeId` promises
 * an id no node is using, and a `SequentialIdGenerator` restarted against a document that
 * already holds `n_1` breaks that promise; because `NodeTable.with` replaces rather than
 * refuses, honouring it would quietly destroy a node. So an id that is already taken, or the
 * same id handed out twice, is refused.
 */
internal fun planDuplicateNode(
    command: Command.DuplicateNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to duplicate")
        return reasons.result(emptyList())
    }

    val from = DocumentIndex(document).parentOf(command.id)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.id,
            "'${command.id}' is a root, and a copy of it would have nowhere to go",
        )
        return reasons.result(emptyList())
    }

    val parent: Node? = document.nodes[from.parentId]
    if (parent == null) {
        reasons.add(EditCodes.UnknownNode, from.parentId, "no node '${from.parentId}'")
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

    // A dangling child would be dropped by the walk and would then be missing from the copy,
    // which is a smaller document than the user asked for rather than an error.
    val dangling: List<NodeId> = danglingChildren(document, node)
    if (dangling.isNotEmpty()) {
        reasons.add(
            EditCodes.UnknownNode,
            dangling.first(),
            "'${command.id}' lists child '${dangling.first()}', which the document does not hold",
        )
        return reasons.result(emptyList())
    }

    val rows: List<Node> = subtreeRows(document, command.id)
    val copy: NodeTemplate = IdRemapper(document, ids).remap(rows)

    // The remapper is built against the whole document, so an id it hands back is one nothing
    // in the document is using — unless the generator broke the promise `IdGenerator` makes.
    // That is checked here rather than trusted, because `NodeTable.with` answers a collision
    // by replacing the row, and the same id twice is that collision one step earlier.
    val minted: List<NodeId> = copy.nodes.map { it.id }
    if (minted.any { it in document.nodes } || minted.distinct().size != rows.size) {
        reasons.add(
            EditCodes.DuplicateId,
            minted.firstOrNull(),
            "the id generator returned an id the document already holds, or the same id twice",
        )
        return reasons.result(emptyList())
    }

    reasons.addAll(
        slotRejections(
            spec = spec,
            parent = parent.id,
            slot = from.slot,
            childType = node.type,
            countAfter = parent.slots[from.slot].orEmpty().size + 1,
        ),
    )
    if (!reasons.isEmpty) return reasons.result(emptyList())

    // `from.index + 1` is the position the original node is pushed to by the insertion, which
    // is what the slot holds when the op runs.
    //
    // `slotExisted` is read rather than assumed, and it reads `true`: `DocumentIndex.parentOf`
    // found the original node *in this slot*, so the list is non-empty and the key is there. It
    // is still the document's answer rather than a literal, because the copy is placed beside a
    // live sibling and never invents a slot the way an insertion into a declared-but-unspelled
    // one does.
    return reasons.result(
        listOf(
            PatchOp.InsertNodes(
                parent = from.parentId,
                slot = from.slot,
                index = from.index + 1,
                nodes = copy.nodes,
                slotExisted = parent.slots.containsKey(from.slot),
            ),
        ),
    )
}