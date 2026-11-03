package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Record
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.editing.template.danglingChildren
import dev.rotalex.lutter.editing.template.subtreeRows
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.index.NodeOwner
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * `ExtractComponent`: a subtree lifted into a document-declared component, its place taken by
 * an instance of it.
 *
 * The four document-shape questions §25.3 deferred are settled on [Command.ExtractComponent]'s
 * KDoc — this file is where they become a patch, and the patch is the shape the decisions
 * imply:
 *
 *  1. [PatchOp.RemoveSubtree] lifts the subtree, capturing the rows and the position they
 *     held, so the inverse is a field-for-field restore.
 *  2. [PatchOp.PutNodes] puts the same rows back with no parent. A component body is rows in
 *     the table that `ComponentDecl.root` points at — §5.5's "the body lives in
 *     `UiDocument.nodes`" — and a row nothing references is the legal intermediate state
 *     [PatchOp.PutNodes] exists to hold.
 *  3. [PatchOp.PutRecord] declares the component: empty params (the dangling-item rule), the
 *     body's `core.SlotOutlet` names as its slots, and the extracted root — unchanged id — as
 *     its root.
 *  4. [PatchOp.InsertNodes] places the instance at the captured position: a fresh id, the type
 *     `doc.` + the name, and no props — the complete override surface is params and slots
 *     (§5.7), and both start empty.
 *
 * The slot the instance enters is asked the filter question, not the cardinality one:
 * extraction replaces one child with one child, so the count cannot move, and asking the
 * capacity question would refuse an edit that did not change it.
 */
internal fun planExtractComponent(
    command: Command.ExtractComponent,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    // The name is the declaration's id and the generated composable's name; the command's
    // KDoc says why one string does both jobs. Shape first, existence second: a string that
    // is not an identifier cannot collide, and an identifier that collides is a real one.
    if (!IDENTIFIER.matches(command.name)) {
        reasons.add(
            EditCodes.ComponentName,
            command.root,
            "'${command.name}' is not a Kotlin identifier, and a component's name is emitted as one",
        )
        return reasons.result(emptyList())
    }
    val declId = ComponentDeclId(command.name)
    if (declId in document.components) {
        reasons.add(
            EditCodes.ComponentExists,
            command.root,
            "the document already declares a component named '${command.name}'",
        )
        return reasons.result(emptyList())
    }

    val node: Node? = document.nodes[command.root]
    if (node == null) {
        reasons.add(EditCodes.UnknownNode, command.root, "no node '${command.root}' to extract")
        return reasons.result(emptyList())
    }

    val from = DocumentIndex(document).parentOf(command.root)
    if (from == null) {
        reasons.add(
            EditCodes.NoParent,
            command.root,
            "'${command.root}' is a root, and lifting it would leave its owner pointing at a body that moved",
        )
        return reasons.result(emptyList())
    }

    // Inside a component body the outer declaration's param reads would dangle differently
    // than an item would, and the extraction surface for now is page-owned subtrees. The
    // code's KDoc carries the argument; this check is the one thing that makes it true.
    when (DocumentIndex(document).ownerOf(command.root)) {
        is NodeOwner.Component -> {
            reasons.add(
                EditCodes.InComponentBody,
                command.root,
                "'${command.root}' is inside a component's body, and extracting from one is not planned for yet",
            )
            return reasons.result(emptyList())
        }
        else -> Unit
    }

    val parent: Node? = document.nodes[from.parentId]
    if (parent == null) {
        reasons.add(EditCodes.UnknownNode, from.parentId, "no node '${from.parentId}'")
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

    // The same promise DuplicateNode checks: a dangling child would make the body a smaller
    // document than the subtree the user pointed at.
    val dangling: List<NodeId> = danglingChildren(document, node)
    if (dangling.isNotEmpty()) {
        reasons.add(
            EditCodes.UnknownNode,
            dangling.first(),
            "'${command.root}' lists child '${dangling.first()}', which the document does not hold",
        )
        return reasons.result(emptyList())
    }

    val rows: List<Node> = subtreeRows(document, command.root)

    // A body can read its params, its slots and the document's app state — an item variable
    // bound by an iteration outside the subtree survives none of those. Promoting it to a
    // param needs the item's type, and §23.3 keeps that knowledge in analysis; the planner
    // refuses rather than guess. The command's KDoc carries the whole argument.
    val danglingItem: Node? = rows.firstOrNull { row -> row.props.values.any { it.readsAnItem() } }
    if (danglingItem != null) {
        reasons.add(
            EditCodes.DanglingItem,
            danglingItem.id,
            "'${danglingItem.id}' reads an iteration item that is bound outside the extracted subtree",
        )
        return reasons.result(emptyList())
    }

    // The declaration's slots are the body's outlets, named by their `slot` property in
    // first-occurrence order — deterministic, like every derivation this engine makes.
    val outlets: List<SlotName> = outletSlots(rows, reasons)
    if (!reasons.isEmpty) return reasons.result(emptyList())

    val instanceId = ids.nextNodeId()
    if (instanceId in document.nodes) {
        reasons.add(
            EditCodes.DuplicateId,
            instanceId,
            "the id generator returned an id the document already holds",
        )
        return reasons.result(emptyList())
    }
    val instanceType = ComponentType("doc.${declId.value}")
    reasons.addAll(slotFilterRejections(parentSpec, parent.id, from.slot, instanceType))
    if (!reasons.isEmpty) return reasons.result(emptyList())

    val decl = ComponentDecl(
        id = declId,
        name = command.name,
        params = emptyList(),
        slots = outlets.map(::SlotDecl),
        state = emptyList(),
        root = command.root,
    )
    val instance = Node(id = instanceId, type = instanceType)

    return reasons.result(
        listOf(
            PatchOp.RemoveSubtree(
                command.root,
                RemovedSubtree(from.parentId, from.slot, from.index, rows),
                slotExisted = true,
            ),
            PatchOp.PutNodes(rows),
            PatchOp.PutRecord(RecordRef.Component(declId), old = null, new = Record.Component(decl)),
            PatchOp.InsertNodes(from.parentId, from.slot, from.index, listOf(instance), slotExisted = true),
        ),
    )
}

/** The shape a component name must already have: it is emitted as a composable's name. */
private val IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9_]*")

/** The outlet node's type, and the property that names the slot it draws. */
private val SLOT_OUTLET = ComponentType("core.SlotOutlet")
private val SLOT_KEY = PropertyKey("slot")

/**
 * The body's outlet names, in first-occurrence order, or the refusals for the ones that
 * cannot be carried statically.
 *
 * [PropertyValue.Const] is the only derivable spelling of the outlet's name: the spec's own
 * comment says the value is read at render time and may be computed, and a computed name is
 * a name no `SlotDecl` can hold. The duplicates collapse — two outlets drawing the same slot
 * is legal in a body and one declaration of it.
 */
private fun outletSlots(rows: List<Node>, reasons: Rejections): List<SlotName> {
    val found = mutableListOf<SlotName>()
    for (row in rows) {
        if (row.type != SLOT_OUTLET) continue
        when (val value = row.props[SLOT_KEY]) {
            is PropertyValue.Const -> found += SlotName((value.value as Value.Str).v)
            else -> reasons.add(
                EditCodes.OutletName,
                row.id,
                "'${row.id}' names its slot with a computed string, and a declaration needs the constant",
            )
        }
    }
    return found.distinct()
}

/**
 * Whether a property's expression reads `RefTarget.Item` anywhere.
 *
 * The walk is exhaustive over [Expr]'s variants with no `else`, so a ninth expression kind
 * cannot silently skip the scan — the same closed-set argument the command union makes,
 * paid where the reading happens.
 */
private fun PropertyValue.readsAnItem(): Boolean = when (this) {
    is PropertyValue.Const -> false
    is PropertyValue.Computed -> expr.readsAnItem()
}

private fun Expr.readsAnItem(): Boolean = when (this) {
    is Expr.Const -> false
    is Expr.Ref -> target is RefTarget.Item
    is Expr.Member -> receiver.readsAnItem()
    is Expr.Call -> args.any { it.readsAnItem() }
    is Expr.Unary -> operand.readsAnItem()
    is Expr.Binary -> left.readsAnItem() || right.readsAnItem()
    is Expr.If -> cond.readsAnItem() || then.readsAnItem() || otherwise.readsAnItem()
    is Expr.ListLiteral -> items.any { it.readsAnItem() }
    is Expr.Template -> parts.any { it.readsAnItem() }
}
