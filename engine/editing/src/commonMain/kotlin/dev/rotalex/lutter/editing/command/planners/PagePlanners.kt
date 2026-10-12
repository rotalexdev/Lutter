package dev.rotalex.lutter.editing.command.planners

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Record
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * [Command.AddPage].
 *
 * ### Two ops, and the order is the whole design
 *
 * `[PutNodes(subtree), PutRecord(RecordRef.Page(page.id), null, Record.Page(page))]`.
 *
 * `Page.root` names a `NodeId` that no node's slot references — §5.5 made a page hold no nodes
 * of its own — so the rows have to exist before the record can point at them, and D30's
 * [PatchOp.PutNodes] is the op that writes rows with no parent. The order is not a
 * preference: swapped, the `PutRecord` would name a node the table does not hold, and
 * `NodeTable.require` turns that into an `IllegalStateException` from whichever component
 * walks the page first.
 *
 * The pair also inverts as a pair. `PatchInverter` reverses the list, so undoing removes the
 * page first and then the rows, and a page that pointed at rows which had already gone would
 * not exist at any point in the undo.
 *
 * ### What is checked, and what is deliberately not
 *
 * Three refusals and a schema consultation that does not happen.
 *
 * **A page id already in the document.** `UiDocument.pages` is a map keyed by id and
 * `PatchApplier.put` would replace the entry, so a second `AddPage` for `p_home` would
 * silently replace a screen rather than add one.
 *
 * **An empty subtree.** `PatchOp.PutNodes` refuses one at construction, because its inverse is
 * a removal whose root is the first row; this planner refuses it first so the reason is a
 * finding with a message rather than an exception from a type.
 *
 * **A `page.root` that is not the subtree's first row.** §5.5's `Page.root` is the node a
 * screen's tree starts at, so a page naming anything else describes a tree the author never
 * wrote. Nothing in §17 would notice — the root would resolve, the walk would start somewhere
 * real — so this is the planner's to refuse.
 *
 * **No schema, and no id generator.** Nothing here places a child into a slot, so there is no
 * `SlotSpec` to consult and no `accepts` or `cardinality` to check: §25.3's two policies are
 * both about slots and a page root is in none. Whether the root's component type is registered
 * is D4's rule and §17 pass 4's `component.unknown`, not a refusal — a page whose root names a
 * component a plugin has not registered is a document to report on, not an edit to refuse.
 */
internal fun planAddPage(
    command: Command.AddPage,
    document: UiDocument,
    components: Registry<ComponentType, ComponentSpec>,
    ids: IdGenerator,
): CommandResult {
    val reasons = Rejections()

    if (command.page.id in document.pages) {
        reasons.add(
            EditCodes.DuplicateId,
            null,
            "the document already has a page '${command.page.id}'",
            mapOf("page" to command.page.id.value),
        )
    }

    val subtree: List<Node> = command.subtree.nodes
    val root: Node? = subtree.firstOrNull()
    if (root == null) {
        reasons.add(
            EditCodes.EmptySubtree,
            null,
            "a page needs a root node, and the subtree to add holds none",
            mapOf("page" to command.page.id.value),
        )
    } else if (root.id != command.page.root) {
        reasons.add(
            EditCodes.RootMismatch,
            command.page.root,
            "the page starts at '${command.page.root}' but the subtree's first row is '${root.id}'",
            mapOf("page" to command.page.id.value, "root" to command.page.root.value),
        )
    }

    val taken: Node? = subtree.firstOrNull { it.id in document.nodes }
    if (taken != null) {
        reasons.add(
            EditCodes.DuplicateId,
            taken.id,
            "the subtree carries id '${taken.id}', which the document already holds",
            mapOf("node" to taken.id.value),
        )
    }

    if (!reasons.isEmpty) return reasons.result(emptyList())

    return reasons.result(
        listOf(
            PatchOp.PutNodes(subtree),
            PatchOp.PutRecord(RecordRef.Page(command.page.id), null, Record.Page(command.page)),
        ),
    )
}