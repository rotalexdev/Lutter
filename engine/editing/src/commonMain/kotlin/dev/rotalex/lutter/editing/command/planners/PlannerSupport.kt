package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.registry.Registry

// Everything the planners share that is not a command.
//
// The helpers are here rather than duplicated per family because each of them is a *fact about
// the document* that two families need and a third would otherwise re-derive: what a slot's
// `Cardinality` permits, and which child types its `ChildFilter` admits. A copy of "may this
// slot take that" written four times is four answers to one question, and the day they disagree
// is the day an editor offers a drop the planner refuses.
//
// The two facts that used to be here — which rows a subtree has, and which of a node's children
// the document does not hold — moved to `template/NodeTemplate.kt`, where `NodeTemplate.capture`
// needs them too. Three callers now walk a subtree rather than two, and two walks that disagreed
// on which row is first would put a different node in a parent's slot depending on which command
// asked. The direction is one-way because `Command.InsertNode` already carries a `NodeTemplate`,
// so `command` depends on `template` and the walk belongs on that side of the edge.
//
// **Nothing in this file is public.** §33.6 gives the planners `internal`, and that includes
// their vocabulary: a caller that wants the legal drop targets of a node asks `DropTargets`
// (§33.6's own `policy` file), and a caller that wants to plan a command of its own — a
// plugin, a migration — goes through `CommandPlanner`. `DropTargets` is the one exception and
// it is a deliberate one: §25.3's promise is that it answers the same question the planners do,
// so it calls `slotRejections` below rather than re-deriving it.

/**
 * The reasons a command was refused, gathered in the order they were found.
 *
 * A list rather than the first failure because an editor showing a drop target wants to say
 * *both* "this component has no such slot" and "this slot is full" when both are true, and
 * because a caller that has to render the first one anyway learns nothing about the second
 * from a caller that never got that far. Insertion order is preserved, so two runs over the
 * same document produce the same reasons in the same order.
 *
 * Every reason is a [Diagnostic] at [Severity.Error] — see [CommandResult]'s KDoc for why a
 * refusal has no other severity — carrying the node it is about, the same [args] shape an
 * analysis pass fills in, and a code from [EditCodes].
 */
internal class Rejections {

    private val found: MutableList<Diagnostic> = mutableListOf()

    /** Records one refusal. */
    fun add(
        code: DiagnosticCode,
        node: NodeId?,
        message: String,
        args: Map<String, String> = emptyMap(),
    ) {
        found += Diagnostic(
            severity = Severity.Error,
            code = code,
            location = DiagnosticLocation(nodeId = node),
            message = message,
            args = args,
        )
    }

    /** Records every refusal in [reasons], in order. */
    fun addAll(reasons: List<Diagnostic>) {
        found += reasons
    }

    /** Whether anything has been refused yet. */
    val isEmpty: Boolean get() = found.isEmpty()

    /**
     * `Ok(Patch(ops))` when nothing was refused, and every refusal otherwise.
     *
     * [ops] is ignored on the rejection arm, which is the whole point: a refused command has
     * produced nothing, and a planner that handed its half-built patch along with its
     * complaints would leave the caller to decide whether to trust it.
     */
    fun result(ops: List<PatchOp>): CommandResult =
        if (found.isEmpty()) CommandResult.Ok(Patch(ops)) else CommandResult.Rejected(found.toList())
}

/** The spec for [node]'s component type, or `null` when the schema does not register it. */
internal fun specOf(node: Node, components: Registry<ComponentType, ComponentSpec>): ComponentSpec? =
    components[node.type]

/** The spec's slot by name, or `null` when it declares no such child area. */
internal fun slotSpecOf(spec: ComponentSpec, slot: SlotName): SlotSpec? =
    spec.slots.firstOrNull { it.name == slot }

/**
 * How many children a slot of this cardinality may hold.
 *
 * §7.1 gives three values and only one of them is a bound; the other two are bounds of one,
 * which is what "exactly one" and "at most one" both mean to a caller asking *"may I put one
 * more in?"*. A bound that is not a bound is `Int.MAX_VALUE`, so the caller below needs no
 * special case for the unbounded slot.
 */
internal val Cardinality.capacity: Int
    get() = when (this) {
        Cardinality.ExactlyOne -> 1
        Cardinality.ZeroOrOne -> 1
        Cardinality.Many -> Int.MAX_VALUE
    }

/** Whether this filter admits a child of [type]. §7.1's three filters, one question. */
internal fun ChildFilter.admits(type: ComponentType): Boolean = when (this) {
    ChildFilter.Any -> true
    is ChildFilter.Only -> type in types
    is ChildFilter.Except -> type !in types
}

/**
 * The refusals that §25.3 names: the slot is not declared, it is full, or it does not admit
 * this child. Zero, one or two of them.
 *
 * The three are independent *given* a [SlotSpec], which is why they are accumulated rather
 * than returned as the first one: a slot that is both full and closed is two things a user
 * would have to fix by editing the document twice. The first is not, because the other two
 * cannot be answered without a spec — and a slot the component does not declare has no
 * capacity and no filter.
 *
 * [countAfter] is the size the slot will have **once the edit lands**, not the size it has
 * now. That is what makes one check cover an insertion, a move between two slots and a move
 * within one.
 */
internal fun slotRejections(
    spec: ComponentSpec,
    parent: NodeId,
    slot: SlotName,
    childType: ComponentType,
    countAfter: Int,
): List<Diagnostic> {
    val slotSpec: SlotSpec = slotSpecOf(spec, slot) ?: return listOf(undeclaredSlot(spec, parent, slot))

    val reasons: MutableList<Diagnostic> = mutableListOf()
    if (countAfter > slotSpec.cardinality.capacity) reasons += fullSlot(spec, slotSpec, parent, countAfter)
    if (!slotSpec.accepts.admits(childType)) reasons += childNotAllowed(spec, slotSpec, parent, childType)
    return reasons
}

/**
 * The refusals that do not depend on how many children a slot holds: it is not declared, or
 * its filter does not admit this child.
 *
 * Split from [slotRejections] because the count is not always a question. A wrap replaces one
 * child with another, so the parent's slot is neither fuller nor emptier and asking about
 * its capacity would refuse an edit to a slot that was already at its limit before the user
 * touched anything.
 */
internal fun slotFilterRejections(
    spec: ComponentSpec,
    parent: NodeId,
    slot: SlotName,
    childType: ComponentType,
): List<Diagnostic> {
    val slotSpec: SlotSpec = slotSpecOf(spec, slot) ?: return listOf(undeclaredSlot(spec, parent, slot))
    if (slotSpec.accepts.admits(childType)) return emptyList()
    return listOf(childNotAllowed(spec, slotSpec, parent, childType))
}

/** "This component has no such slot", which the other two refusals cannot be asked without. */
private fun undeclaredSlot(spec: ComponentSpec, parent: NodeId, slot: SlotName): Diagnostic = Diagnostic(
    severity = Severity.Error,
    code = EditCodes.UndeclaredSlot,
    location = DiagnosticLocation(nodeId = parent),
    message = "'${spec.type}' declares no slot named '$slot'",
    args = mapOf("component" to spec.type.value, "slot" to slot.value),
)

/** "The edit would leave more children in this slot than its cardinality allows". */
private fun fullSlot(
    spec: ComponentSpec,
    slotSpec: SlotSpec,
    parent: NodeId,
    countAfter: Int,
): Diagnostic = Diagnostic(
    severity = Severity.Error,
    code = EditCodes.SlotCardinality,
    location = DiagnosticLocation(nodeId = parent),
    message = "slot '${slotSpec.name}' of '${spec.type}' holds ${slotSpec.cardinality}, " +
        "and the edit would leave $countAfter child(ren) in it",
    args = mapOf(
        "component" to spec.type.value,
        "slot" to slotSpec.name.value,
        "cardinality" to slotSpec.cardinality.name,
        "count" to countAfter.toString(),
    ),
)

/** "This component does not take a child of that type here". */
private fun childNotAllowed(
    spec: ComponentSpec,
    slotSpec: SlotSpec,
    parent: NodeId,
    childType: ComponentType,
): Diagnostic = Diagnostic(
    severity = Severity.Error,
    code = EditCodes.SlotChildNotAllowed,
    location = DiagnosticLocation(nodeId = parent),
    message = "slot '${slotSpec.name}' of '${spec.type}' does not accept a child of type '$childType'",
    args = mapOf(
        "component" to spec.type.value,
        "slot" to slotSpec.name.value,
        "child" to childType.value,
    ),
)