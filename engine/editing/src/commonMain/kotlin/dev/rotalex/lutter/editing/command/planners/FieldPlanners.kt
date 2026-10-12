package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * The four commands that write one node's own fields: [Command.SetProperty],
 * [Command.SetModifiers], [Command.SetEventHandler] and [Command.RenameNode].
 *
 * They are one file because they are one shape — read the node, capture the old value, emit
 * one `Set` op — and because they share the one decision this file exists to record.
 *
 * ### None of them consults the schema, and that is the decision
 *
 * §25.3 names the policies a planner checks, and they are both about slots:
 * *"Planners check schema policies (`SlotSpec.accepts/cardinality`) so an editor can offer
 * only legal drop targets."* The half of the sentence after the comma is the whole scope: an
 * editor offers legal drop targets from the spec, and the four setters place no children.
 *
 * So there is no check that the property key is declared, no check that the value's type
 * matches, no check that the modifier type exists, no check that the component raises the
 * event. Each of those is a §17 diagnostic with a code and a place in the catalogue:
 * `prop.unknown`, `prop.type_mismatch`, `modifier.unknown`, `event.unknown`. Answering any of
 * them here would start validation and not finish it — the cross-property `PropertyRule`s,
 * the scope requirements on a modifier and the action arguments in a handler are all further
 * questions this file would then be expected to answer, and §17's passes answer all of them
 * with a `ResolvedDocument` in hand. §25.4 makes the same division on the read side: the
 * property panel is `ComponentSpec.properties`, so the editor already knows which keys the
 * spec declares and does not need the engine to repeat it back as a refusal.
 *
 * What is left is the one question the ops themselves cannot check: **does the node exist.**
 * `PatchApplier` answers that with `NodeTable.require` and an `IllegalStateException`, and a
 * planner whose rejection path throws has not kept its promise.
 *
 * A `null` on any of the three writable fields clears the entry rather than writing null,
 * because the fields are maps and an absent entry is not an entry holding null.
 */
internal fun planSetProperty(
    command: Command.SetProperty,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()
    val node: Node? = document.nodes[command.node]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.node, "no node '${command.node}' to set '${command.key}' on")
        return reasons.result(emptyList())
    }

    return reasons.result(
        listOf(PatchOp.SetProp(command.node, command.key, node.props[command.key], command.value)),
    )
}

/** Replaces the whole modifier chain. Order is the meaning, so this is the whole list or nothing. */
internal fun planSetModifiers(
    command: Command.SetModifiers,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()
    val node: Node? = document.nodes[command.node]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.node, "no node '${command.node}' to set modifiers on")
        return reasons.result(emptyList())
    }

    return reasons.result(listOf(PatchOp.SetModifiers(command.node, node.modifiers, command.modifiers)))
}

/** Sets or clears one handler. `actions` is nullable and `null` removes the entry. */
internal fun planSetEventHandler(
    command: Command.SetEventHandler,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()
    val node: Node? = document.nodes[command.node]
    if (node == null) {
        reasons.add(
            EditCodes.UnknownNode,
            command.node,
            "no node '${command.node}' to bind '${command.event}' on",
        )
        return reasons.result(emptyList())
    }

    return reasons.result(
        listOf(PatchOp.SetHandler(command.node, command.event, node.events[command.event], command.actions)),
    )
}

/** Writes the label. §5.3's `Node.name` is a hint, validated by nothing and resolved by nothing. */
internal fun planRenameNode(
    command: Command.RenameNode,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()
    val node: Node? = document.nodes[command.id]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.id, "no node '${command.id}' to rename")
        return reasons.result(emptyList())
    }

    return reasons.result(listOf(PatchOp.SetNodeName(command.id, node.name, command.name)))
}