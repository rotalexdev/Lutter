package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Placement
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.editing.template.IdRemapper
import dev.rotalex.lutter.editing.template.danglingChildren
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.WrapNode] and [Command.UnwrapNode]: the two commands that change *who* holds a
 * node rather than where a node is.
 *
 * ### What a wrap costs, and why it is two ops
 *
 * A wrapper is a **new** row plus a **move** of the node into it. One op cannot do both: the
 * wrapper has to be in the table before anything can be moved into it, and
 * `PatchOp.InsertNodes` places a subtree's *first* row in a parent's slot — so inserting the
 * wrapper alone would leave the node it should contain with nowhere to go.
 *
 * The wrapper is inserted with **no slots at all** and the node is moved into it afterwards.
 * That is not a convenience. If the wrapper arrived already holding the node, the removal
 * half of the inverse would delete only the wrapper's row and leave the node behind with no
 * parent — §26.2's property would fail on the command a designer presses most. With an empty
 * wrapper and an explicit move, the inverse of the move puts the node back first and the
 * inverse of the insertion then removes a row whose slot is empty again.
 *
 * ### What an unwrap costs, and the order it is built in
 *
 * The children are moved **last first**, each to the position the node itself held. That
 * ordering is not a style choice; it is what makes the patch invertible, and it is worth
 * spelling out because the forward result and the inverse want opposite things:
 *
 *  * **Forward**, each child lands at the same index and pushes the previous one right, so
 *    the children end up in their original order and the node ends up after them. Iterating
 *    them backwards is what makes the *last* child land first and therefore leaves the
 *    *first* child in front.
 *  * **Inverted**, the ops run in the opposite order and each one restores a child at the
 *    index it held *in the node*, which — because the children were lifted from the back —
 *    is still the index it has room for.
 *
 * The removal's capture is the node **as it stands once the children have left**, so every
 * slot it spelled now holds nothing. That is not a convenient rewrite of the capture: it is
 * what the applier will find, and §26.2's property is about the state an op actually met.
 *
 * **Every slot, and every key — a capture of `slots = {}` is a different row.** §5.5 gives
 * `Node.slots` an empty-map default, so `{}` and `{"footer":[]}` are two spellings of the same
 * node that every reader treats alike, and the applier cannot recover the one the capture threw
 * away: `InsertNodes` writes `captured.nodes` back verbatim, so a key missing from the capture
 * is a key the undo does not restore.
 *
 * **A slot the lifts emptied hides it, and a slot the node left empty shows it.** A slot the
 * unwrap fills is written again by the inverse's own `MoveNode`, whose `attach` always writes the
 * key it is handed — so a capture of `{}` survives every unwrap whose children cover its slots.
 * What does not survive is a slot the node spelled and held **nothing** in, because no inverse
 * move ever names it and the capture was its only witness.
 * `CommandPlannerRoundTripTest` holds that row now, and it is the shape the Phase 9 rows never
 * built: both of them refill every key the node they dissolved held.
 *
 * Children of several slots are lifted in slot-name order, which is a determinism choice and
 * not a claim about intent — `Node.slots` is a map, and two runs disagreeing about which of
 * two slots came first would produce two different documents.
 */
internal fun planWrapNode(
    command: Command.WrapNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to wrap")
        return reasons.result(emptyList())
    }

    val from = DocumentIndex(document).parentOf(command.id)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.id,
            "'${command.id}' is a root, and wrapping it would put a parent above the root of a page",
        )
        return reasons.result(emptyList())
    }

    val parent: Node? = document.nodes[from.parentId]
    if (parent == null) {
        reasons.add(EditCodes.UnknownNode, from.parentId, "no node '${from.parentId}'")
        return reasons.result(emptyList())
    }

    val wrapperSpec: ComponentSpec? = components[command.wrapper]
    if (wrapperSpec == null) {
        reasons.add(
            EditCodes.UnknownComponent,
            parent.id,
            "'${command.wrapper}' is not registered, so nothing can be wrapped in it",
            mapOf("component" to command.wrapper.value),
        )
        return reasons.result(emptyList())
    }

    val parentSpec: ComponentSpec? = specOf(parent, components)
    if (parentSpec == null) {
        reasons.add(
            EditCodes.UnknownComponent,
            parent.id,
            "'${parent.type}' is not registered, so its slots cannot be read",
            mapOf("component" to parent.type.value),
        )
        return reasons.result(emptyList())
    }

    // The wrapper's own slot. One child is about to go into a brand new slot, which every
    // `Cardinality` admits — `ExactlyOne` included — so the filter is the only question.
    reasons.addAll(slotFilterRejections(wrapperSpec, parent.id, command.slot, node.type))

    // The parent's slot. The wrapper takes the node's place, so the count is unchanged and
    // only the filter is a question; asking about capacity here would refuse an edit to a
    // slot that was already at its limit before the user touched anything.
    reasons.addAll(slotFilterRejections(parentSpec, parent.id, from.slot, command.wrapper))

    // One row created out of nothing, which is the smallest thing `IdRemapper` does. It is
    // the same class `Command.DuplicateNode` mints a whole subtree's worth through, so there
    // is one place in this module that decides what a new node's id is.
    val wrapperId: NodeId = IdRemapper(document, ids).fresh()
    if (wrapperId in document.nodes) {
        reasons.add(
            EditCodes.DuplicateId,
            wrapperId,
            "the id generator returned '${wrapperId}', which the document already holds",
        )
    }
    if (!reasons.isEmpty) return reasons.result(emptyList())

    return reasons.result(
        listOf(
            // The wrapper arrives empty and at the position the node held, so the node is at
            // `from.index + 1` by the time the move runs.
            //
            // `slotExisted` reads the parent, and it reads `true` for the same reason the
            // duplicate's does: the node being wrapped is in this slot right now, so the key is.
            // A wrap never creates a slot key — the wrapper's own empty `content` arrives with
            // the move, which writes its keys back rather than choosing.
            PatchOp.InsertNodes(
                parent = from.parentId,
                slot = from.slot,
                index = from.index,
                nodes = listOf(Node(id = wrapperId, type = command.wrapper)),
                slotExisted = parent.slots.containsKey(from.slot),
            ),
            PatchOp.MoveNode(
                id = command.id,
                from = Placement(from.parentId, from.slot, from.index + 1),
                to = Placement(wrapperId, command.slot, 0),
            ),
        ),
    )
}

/** One child of the node being unwrapped, with the position it will be lifted from. */
private data class UnwrappedChild(
    val id: NodeId,
    val slot: SlotName,
    val index: Int,
)

/**
 * [Command.UnwrapNode]: the node is dissolved and its children take its place.
 *
 * Refuses a node with no children rather than producing an empty patch. §26.2 calls an empty
 * patch legal, and it is — but it is what a command produces when it has nothing to do, not
 * what a user pressing *unwrap* on an empty container should get back as a silent success.
 */
internal fun planUnwrapNode(
    command: Command.UnwrapNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to unwrap")
        return reasons.result(emptyList())
    }

    val from = DocumentIndex(document).parentOf(command.id)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.id,
            "'${command.id}' is a root, and a root has no position to lift its children into",
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

    // A child the table does not hold would make a move the applier cannot make, and one two
    // slots claim would make two moves out of the same row. §17.1 reports both on the
    // document; refusing them here is what keeps the promise that nothing this planner
    // returns throws.
    val dangling: List<NodeId> = danglingChildren(document, node)
    if (dangling.isNotEmpty()) {
        reasons.add(
            EditCodes.UnknownNode,
            dangling.first(),
            "'${command.id}' lists child '${dangling.first()}', which the document does not hold",
        )
        return reasons.result(emptyList())
    }

    // Slot-name order, then index order. See the file KDoc: determinism, not intent.
    val children: List<UnwrappedChild> = node.slots.entries
        .sortedBy { it.key.value }
        .flatMap { (slotName, childIds) ->
            childIds.mapIndexed { position, childId -> UnwrappedChild(childId, slotName, position) }
        }

    if (children.isEmpty()) {
        reasons.add(
            EditCodes.NothingToUnwrap,
            command.id,
            "'${command.id}' has no children, so unwrapping it would change nothing",
        )
        return reasons.result(emptyList())
    }

    if (command.id in children.map { it.id }) {
        reasons.add(EditCodes.Cycle, command.id, "'${command.id}' lists itself as a child")
        return reasons.result(emptyList())
    }
    if (children.map { it.id }.distinct().size != children.size) {
        reasons.add(
            EditCodes.MultipleParents,
            command.id,
            "two of '${command.id}'s slots claim the same child",
        )
        return reasons.result(emptyList())
    }

    // One refusal per offending child *type*: three `Text` children in a slot that admits
    // none of them needs one sentence, not three.
    val countAfter: Int = parent.slots[from.slot].orEmpty().size - 1 + children.size
    val offending: List<ComponentType> = children
        .mapNotNull { document.nodes[it.id]?.type }
        .distinct()
    for (childType in offending) {
        reasons.addAll(slotRejections(spec, parent.id, from.slot, childType, countAfter))
    }
    if (!reasons.isEmpty) return reasons.result(emptyList())

    val lifts: List<PatchOp> = children.reversed().map { child ->
        PatchOp.MoveNode(
            id = child.id,
            from = Placement(command.id, child.slot, child.index),
            to = Placement(from.parentId, from.slot, from.index),
        )
    }

    // The row as the lifts above leave it: every slot the node spelled, each holding nothing.
    // The keys are the whole of this line — `emptyMap()` would describe a node with no slots at
    // all, which is a different row, and the inverse would put that row back rather than this
    // one. See the file KDoc for why only a slot the node left empty ever showed it.
    val dissolved: Map<SlotName, List<NodeId>> = node.slots.mapValues { emptyList() }

    return reasons.result(
        lifts + listOf(
            PatchOp.RemoveSubtree(
                root = command.id,
                captured = RemovedSubtree(
                    parent = from.parentId,
                    slot = from.slot,
                    index = from.index + children.size,
                    nodes = listOf(node.copy(slots = dissolved)),
                ),
                // The node being dissolved is in this slot when the patch runs, so the key was
                // there before it — and the slot holds the children it is lifting, so it holds
                // more afterwards. Undoing this removal restores the node rather than deleting a
                // key, which is the one thing a `false` here would get wrong.
                slotExisted = true,
            ),
        ),
    )
}