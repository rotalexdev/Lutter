package dev.rotalex.lutter.editing.policy

import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.planners.slotRejections
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * Where a node of a given component type may be dropped, answered from the specs.
 *
 * PLAN §25.3 is the whole reason this file exists, and the sentence is worth reading twice:
 * *"Planners check schema policies (`SlotSpec.accepts/cardinality`) so an editor can offer
 * only legal drop targets via `DropTargets.forNode(document, schema, nodeType)`."*
 *
 * ### One answer, asked once
 *
 * The clause after the comma is a promise about **agreement**, and it is the reason this file
 * calls the planners' own check rather than writing one of its own. A `DropTargets` that
 * re-derived "is a `m3.Text` legal in this slot" from `SlotSpec` would be a second
 * implementation of a rule that already exists in
 * [dev.rotalex.lutter.editing.command.planners.slotRejections], and the two would agree until
 * the day one of them was extended — a new `Cardinality`, a `ChildFilter` case — and the other
 * was not. Then an editor would render a drop target the planner refuses, which is the one
 * outcome §25.3 was written to rule out.
 *
 * So [forNode] enumerates the candidates — every node, every slot its spec declares — and asks
 * `slotRejections` the same question the planner will ask, and reports the ones with no
 * complaints. Agreement is then structural rather than tested-into-existence, and
 * `DropTargetAgreementTest` exists to keep the *enumeration* honest (which nodes, which slots,
 * in which order), not to hold two implementations together with a string.
 *
 * ### What is checked, beyond the two policies §25.3 names
 *
 * §25.3 names `accepts` and `cardinality`, and names them as the *slot* policies. Three more
 * things stand between a component type and a rendered drop target, and this file answers all
 * three for the reason above — each has to agree with what the planner does with the same
 * candidate.
 *
 *  * **The parent's component type has to be readable.** A node whose type nothing registers
 *    has no slots to ask about, so its slots are never offered, and the planner refuses an
 *    insertion into it with `edit.unknown_component`. Both sides refuse, for the same reason:
 *    with no spec there is no way to certify anything.
 *  * **The slot has to be declared.** Enumerating `ComponentSpec.slots` rather than the node's
 *    own `slots` map is what does this, and it matters: a document may hold a child in a slot
 *    its component does not declare — §17.1 reports it, and D4 keeps it in the document — and
 *    `Command.InsertNode` refuses such an insertion with `edit.slot_undeclared`.
 *  * **The overlay, so a `doc.*` instance is an ordinary parent.** §7.5 makes an instance a
 *    node whose type is `doc.<ComponentDeclId>` whose slots fill `ComponentDecl.slots`, and
 *    §8.4's overlay synthesizes its spec from the declaration. Without the overlay an instance
 *    has no readable spec at all and *nothing at all* could be dropped into one, while the
 *    planner — which does apply the overlay — would have accepted it. This is the case where
 *    the two would genuinely disagree rather than merely be written twice, and it is why the
 *    registry is resolved through [CommandPlanner.componentRegistry] rather than read off the
 *    schema.
 *
 * ### What is deliberately not checked
 *
 *  * **The id the inserted row will carry.** `edit.duplicate_id` is about the caller's own
 *    subtree, and [dev.rotalex.lutter.editing.template.IdRemapper] is how it gets a free one.
 *  * **Whether the child's own children are legal.** This is the question for a *template*,
 *    and a template can be illegal at depth; §17 owns that.
 *  * **Cycles.** Nothing is being moved — a target is somewhere to *put* a new node — so a
 *    move's `edit.cycle` cannot arise here. See the move note on [forNode].
 *
 * @see dev.rotalex.lutter.editing.command.CommandPlanner for the enforcement this mirrors.
 */
public object DropTargets {

    /**
     * Every place a node of [nodeType] may be inserted into [document] **right now**, in a
     * fixed order: nodes ascending by id, and within a node its spec's slots in declaration
     * order.
     *
     * The order is part of the answer rather than an accident of iteration. An editor renders
     * these in a tree or a palette, and §6.2's determinism claim covers the things the engine
     * produces from a document, not merely the bytes of one.
     *
     * A [DropTarget] is a **suggestion that is legal at the moment it was asked for**: the
     * slot fills up, the document changes, and the target stops being one. It is not a
     * reservation, and nothing here holds anything.
     *
     * ### The scope: inserting, not moving
     *
     * §25.3 spells this signature with a component *type*, and a type is what a drag out of a
     * palette carries: "the user is dragging a `m3.Text`". A drag that **moves** an existing
     * node asks almost the same question and needs one more answer this signature has no
     * parameter for: the node's own subtree has to be excluded, or a `Column` could be dropped
     * inside itself. That exclusion is the caller's, because it needs the node's id and this
     * signature has a type; the planner's `edit.cycle` refusal is the backstop if the caller
     * forgets. `Command.MoveNode`'s other two rules — an index read against the slot *after*
     * the node has left it, and a cardinality that is not re-checked within one slot — are
     * about a position rather than a place, and no list of places answers either.
     */
    public fun <A : Any, F : Any, T : Any> forNode(
        document: UiDocument,
        schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
        nodeType: ComponentType,
    ): List<DropTarget> {
        val components: Registry<ComponentType, ComponentSpec> = CommandPlanner.componentRegistry(document, schema)
        val found: MutableList<DropTarget> = mutableListOf()

        for (parentId in document.nodes.ids()) {
            val parent: Node = document.nodes[parentId] ?: continue

            // No spec, no readable slots, so nothing can be certified here. See the file KDoc.
            val spec: ComponentSpec? = components[parent.type] ?: continue

            for (slot in spec.slots) {
                val children: List<NodeId> = parent.slots[slot.name].orEmpty()
                val rejections = slotRejections(
                    spec = spec,
                    parent = parentId,
                    slot = slot.name,
                    childType = nodeType,
                    countAfter = children.size + 1,
                )
                if (rejections.isEmpty()) found += DropTarget(parentId, slot.name, children.size)
            }
        }

        return found
    }
}

/**
 * One place a node may go: which parent, which slot of it, and where in that slot.
 *
 * ### Why [index] is here at all
 *
 * §25.3 names "drop targets" and not "drop positions", so the two fields an editor draws
 * around are the parent's slot. The third is here because a caller that renders the target has
 * to turn it into a `Command.InsertNode`, and an index it had to compute itself would be a
 * second answer to "where in this slot" — computed from the same document, agreeing today.
 *
 * It is the **end** of the slot, because a drop appends. `Command.InsertNode` accepts any
 * position in `0..size`, so a caller with a precise drop point may choose another; that index
 * is then its own claim and its own to check, and no part of the agreement this file exists for
 * covers it. What is covered is that the pair *(parent, slot)* was offered because it is legal,
 * and any index in range of that slot stays legal.
 */
public data class DropTarget(

    /** The node whose slot would receive the child. */
    public val parent: NodeId,

    /** Which named child area of that parent. */
    public val slot: SlotName,

    /** Where in that slot: the end, because a drop appends. */
    public val index: Int,
)