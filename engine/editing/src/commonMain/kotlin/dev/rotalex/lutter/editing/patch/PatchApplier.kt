package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName

/**
 * The only thing in the engine that turns a patch into a document.
 *
 * ### The single-writer rule, and why it is worth stating here rather than in a convention file
 *
 * `UiDocument` is an immutable value and every record it holds is immutable, so "mutating" a
 * document means producing a new one — which means nothing prevents a second piece of code from
 * doing it, and a `NodeTable` is deliberately built so that doing so is easy: [NodeTable.with]
 * and [NodeTable.without] are public and there is no capability to hold. The rule is therefore
 * not enforced by the type system and cannot be. It is enforced by having exactly one place
 * that does it.
 *
 * **Nothing else in `:engine:editing` may produce a changed `UiDocument` from an existing one.**
 * The planners under `command/planners` (§33.6) return patches; `history/History.kt` replays
 * patches; `diff/DocumentDiff.kt` computes patches; `migration/SpecMigrationPass.kt` is
 * specified as "apply component migrations as a patch". Every one of them is a *producer* or a
 * *caller*, and this object is the callee they all share.
 *
 * The reason is not tidiness. If two places can rebuild a document, then "what did this edit
 * do?" stops being answerable by reading one function, a patch stops being a description of a
 * change that can be trusted, and §26.2's property — apply the inverse and the document comes
 * back — becomes a property of a *pair* of code paths that happened to agree. One writer is
 * what makes the property checkable at all.
 *
 * ### Pure, and total on well-formed patches
 *
 * [apply] is a function of its two arguments: it reads nothing, holds no state between calls,
 * and returns a new document rather than changing one. Two engines running the same patch over
 * the same document produce the same document, which is the first half of §26.2's determinism
 * claim and the half that makes the second half testable.
 *
 * What it is *not* total on is a patch that does not fit its document, and that refusal is
 * deliberate. A node that is not in the table, an index that is past the end of a slot, a
 * removal whose captured position no longer holds the node it names — each of those is a patch
 * built against a document that is not the one in front of us, and applying it "successfully"
 * would corrupt a document rather than report a bug. Every check here names what was expected
 * and what was found.
 *
 * Two exception types, and the split is deliberate rather than an oversight. A **check this
 * file makes** throws [IllegalArgumentException], because the patch is the thing being
 * objected to. A **node that is not in the table** throws [IllegalStateException], because that
 * is [NodeTable.require]'s own answer and rewriting it here would hide which of the two
 * questions failed.
 *
 * That is the opposite of how `Node` and `NodeId` behave, and on purpose: a *record* checks
 * its own syntax because it can, while this is an operation against a document it was not
 * given at construction time and cannot check anything about until it runs. §17.1's passes own
 * the invariants a document must satisfy afterwards; nothing here checks them, because this
 * function's job is to make the change the patch describes and not to decide whether the
 * result is a legal document.
 *
 * ### The one canonicalisation, and why §26.2's property needs it
 *
 * **An op that empties a slot removes that slot's key.** `{"content":[]}` becomes `{}`.
 *
 * It is here because the alternative is a broken inverse, and the case is short. Insert a
 * child into a slot the node does not have, and the node's `slots` goes from `{}` to
 * `{"content":["n_f"]}`. Undo it, and the only thing `PatchOp.RemoveSubtree` knows about the
 * slot is that it held one child at index 0 — the parent's `slots` map as it was *before* the
 * insertion is not in the op, is not in `RemovedSubtree`, and cannot be: §26.2 fixes
 * `InsertNodes(parent, slot, index, nodes)` and `RemoveSubtree(root, captured)` down to those
 * fields, and there is nowhere in either to record "this slot key was not there". Leaving the
 * empty list behind restores `{"content":[]}`, which is not the document that was edited, and
 * §26.2's property fails on the one case a user hits immediately — dropping the last child out
 * of a component that has just been given one.
 *
 * So the applier picks the branch that round-trips, and pays for it with an honest limit:
 * **a document that already spelled the empty slot comes back without it.** `Node.slots` is
 * `Map<SlotName, List<NodeId>>` and every reader treats a missing key and an empty list
 * identically — §15.4 walks the list, and an empty list renders nothing — so the two documents
 * mean the same thing and differ only in bytes. `PatchRoundTripTest` asserts the limit rather
 * than leaving it to be discovered, and this is the only place in this file that changes a
 * document beyond what an op names.
 *
 * @see PatchOp for the vocabulary, and for the three gaps in it this function cannot paper
 *   over.
 * @see PatchInverter for why the ops carry enough to be undone without a document.
 */
public object PatchApplier {

    /**
     * [document] with [patch] applied, one op at a time.
     *
     * Each op sees the document the previous one produced, which is the whole reason a patch
     * is a list. Applying the ops in a different order is a different edit, not a reordering
     * of the same one — and [Patch.inverse] relies on that by reversing the order it hands
     * them back in.
     *
     * @throws IllegalArgumentException if an op does not fit [document] as it stands when that
     *   op runs, or @throws IllegalStateException if a node the op names is not in the table at
     *   all. Nothing is applied twice and no partial application is offered: a half-applied
     *   document is not a document anybody can undo from.
     */
    public fun apply(patch: Patch, document: UiDocument): UiDocument {
        var result = document
        for (op in patch.ops) {
            result = applyOne(op, result)
        }
        return result
    }

    /**
     * One op against one document.
     *
     * Exhaustive with no `else`, which is the point: an op added to [PatchOp] does not compile
     * until it has been given a behaviour here, and `when`-completeness is a much weaker prompt
     * than a compiler error at the one place the op has to mean something. D31's tenth op,
     * [PatchOp.SetTheme], and §19.3's twelfth, [PatchOp.SetComponentVersion], are the tenth and
     * the twelfth such errors this switch has raised.
     */
    private fun applyOne(op: PatchOp, document: UiDocument): UiDocument = when (op) {
        is PatchOp.InsertNodes -> applyInsert(op, document)
        is PatchOp.PutNodes -> applyPutNodes(op, document)
        is PatchOp.RemoveSubtree -> applyRemove(op, document)
        is PatchOp.MoveNode -> applyMove(op, document)
        is PatchOp.SetProp -> applySetProp(op, document)
        is PatchOp.SetModifiers -> applySetModifiers(op, document)
        is PatchOp.SetHandler -> applySetHandler(op, document)
        is PatchOp.SetNodeName -> applySetNodeName(op, document)
        is PatchOp.PutRecord -> applyPutRecord(op, document)
        is PatchOp.SetTheme -> applySetTheme(op, document)
        is PatchOp.SetComponentVersion -> applySetComponentVersion(op, document)
    }

    // -----------------------------------------------------------------------------------
    // The node table
    // -----------------------------------------------------------------------------------

    /**
     * Writes the subtree's rows into the table and places its root in the parent's slot.
     *
     * Only the root goes into the slot. §5.6 normalized the document so that a child is a
     * reference; an insert that wrote the whole subtree into the parent's slot would produce
     * §5.6's rejected nested tree inside a single operation, and every reader downstream —
     * `DocumentIndex.descendants`, the structural pass, the renderer — would disagree with it
     * about how many children the parent has.
     *
     * `index` may be `siblings.size`, which appends. That is the one bound that is not simply
     * "in range": it is the position a freshly built list has, so an append does not have to
     * know the length first.
     */
    private fun applyInsert(op: PatchOp.InsertNodes, document: UiDocument): UiDocument {
        val root = op.nodes.firstOrNull()?.id
            ?: throw IllegalArgumentException(
                "PatchOp.InsertNodes into ${op.parent}.${op.slot} carries no nodes, so it has no " +
                    "root to place",
            )

        var table = document.nodes
        for (node in op.nodes) {
            table = table.with(node)
        }

        val siblings = table.require(op.parent).slots[op.slot].orEmpty()
        require(op.index in 0..siblings.size) {
            "PatchOp.InsertNodes addresses index ${op.index} of ${op.parent}.${op.slot}, which " +
                "holds ${siblings.size} child(ren)"
        }

        val extended: List<NodeId> = siblings.toMutableList().also { it.add(op.index, root) }
        return withChildren(document, table, op.parent, op.slot, extended)
    }

    /**
     * Writes rows into the table with no parent: [PatchOp.PutNodes].
     *
     * Two refusals, and the second is the one that matters. **An id the table already holds
     * would replace the row**, because `NodeTable.with` replaces rather than refusing — so an
     * unguarded write here would delete a live node and its whole subtree without a word. The
     * same id twice *within* one op is the same failure one step later: the second write
     * replaces the first, and the capture the inverse would carry is not the document.
     *
     * **No position checks, and there are none to have.** Every other node-table op names where
     * a row went, and this one does not, because a row that nothing points at is exactly what
     * it writes — a page root, before the `PutRecord` that makes it reachable. The strict
     * checks in [applyInsert] and [applyRemove] exist because §5.6's normalization makes a
     * wrong-order document invisible to every existence check in the engine; that failure needs
     * an order to be wrong in.
     */
    private fun applyPutNodes(op: PatchOp.PutNodes, document: UiDocument): UiDocument {
        val held = op.nodes.firstOrNull { it.id in document.nodes }
        require(held == null) {
            "PatchOp.PutNodes carries '${held?.id}', which the table already holds; writing it " +
                "would replace a live node rather than add one"
        }

        val repeated = op.nodes.firstOrNull { node -> op.nodes.count { it.id == node.id } > 1 }
        require(repeated == null) {
            "PatchOp.PutNodes carries '${repeated?.id}' twice, and the second write would " +
                "replace the first"
        }

        var table = document.nodes
        for (node in op.nodes) {
            table = table.with(node)
        }
        return document.copy(nodes = table)
    }

    /**
     * Deletes the captured rows, and takes the root out of the slot it was captured from when
     * there was one.
     *
     * The position is checked against the node's own id before anything is removed. A removal
     * that found a *different* node at that index would leave a document whose nodes are all
     * still present and whose order is wrong, which is the failure §5.6's normalization makes
     * invisible: nothing is missing, so no existence check anywhere in the engine would notice.
     * The check is also what turns "this patch was built for another document" from a silent
     * corruption into an error that names both ids.
     *
     * **A capture with no position is [PatchOp.PutNodes]'s inverse** and does strictly less:
     * the rows are dropped and no slot is touched, because there was no slot. See
     * [RemovedSubtree] for how absence is spelled and why.
     *
     * A removal that drops rows something still references is *not* refused here. Nothing in
     * this file can see reachability without walking the whole table, §17.1's structural pass
     * reports the dangling reference as `struct.missing_node`, and a check that only half
     * answered would be worse than the pass that answers all of it.
     */
    private fun applyRemove(op: PatchOp.RemoveSubtree, document: UiDocument): UiDocument {
        var table = document.nodes
        for (node in op.captured.nodes) {
            table = table.without(node.id)
        }

        val from: Placement = op.captured.placement ?: return document.copy(nodes = table)

        val siblings = table.require(from.parent).slots[from.slot].orEmpty()
        val found = siblings.getOrNull(from.index)
        require(found == op.root) {
            "PatchOp.RemoveSubtree captured '${op.root}' at ${from.parent}.${from.slot}[${from.index}], " +
                "but that position holds '$found'"
        }

        val remaining: List<NodeId> = siblings.toMutableList().also { it.removeAt(from.index) }
        return withChildren(document, table, from.parent, from.slot, remaining)
    }

    /**
     * Moves one node: detach it from [PatchOp.MoveNode.from], attach it at
     * [PatchOp.MoveNode.to].
     *
     * No row changes. The node is the same [dev.rotalex.lutter.model.doc.Node] before and
     * after — same id, same type, same props, same modifiers, same events, same descendants —
     * because §5.6 normalized the tree so that position lives in a parent's slot and nowhere
     * else. That is what makes a move two record rewrites rather than a subtree splice, and
     * why the table is not touched at all here.
     *
     * **The one rule a caller has to know: [PatchOp.MoveNode.to]'s index is read against the
     * slot as it is *after* [PatchOp.MoveNode.from] has been vacated.** The two lists are not
     * one list with two coordinates, and adjusting `to` by one when the move is within a single
     * slot would be the obvious and wrong guess. It is wrong because of the inverse: this op
     * undone is the same op with its placements swapped, and an applier that adjusted indices
     * would put the adjustment on one side of the swap and not the other, so the round trip
     * would fail exactly when the move was within one slot — the common case.
     *
     * Across different parents or different slots the two lists are independent and no rule is
     * needed; the rule only bites when `from` and `to` name the same slot, and in that case
     * the detached list is simply the shorter one.
     */
    private fun applyMove(op: PatchOp.MoveNode, document: UiDocument): UiDocument {
        val detached = detach(op.id, op.from, document.nodes)
        return document.copy(nodes = attach(op.id, op.to, detached))
    }

    /** Removes [id] from the position [from] names, returning the table with that done. */
    private fun detach(id: NodeId, from: Placement, table: NodeTable): NodeTable {
        val parent = table.require(from.parent)
        val siblings = parent.slots[from.slot].orEmpty()
        require(siblings.getOrNull(from.index) == id) {
            "PatchOp.MoveNode says '$id' is at ${from.parent}.${from.slot}[${from.index}], but " +
                "that position holds '${siblings.getOrNull(from.index)}'"
        }

        val remaining: List<NodeId> = siblings.toMutableList().also { it.removeAt(from.index) }
        return table.with(parent.copy(slots = withSlot(parent.slots, from.slot, remaining)))
    }

    /** Puts [id] at [to], returning the table with that done. */
    private fun attach(id: NodeId, to: Placement, table: NodeTable): NodeTable {
        val parent = table.require(to.parent)
        val siblings = parent.slots[to.slot].orEmpty()
        require(to.index in 0..siblings.size) {
            "PatchOp.MoveNode addresses index ${to.index} of ${to.parent}.${to.slot}, which holds " +
                "${siblings.size} child(ren) once the node has been detached"
        }

        val extended: List<NodeId> = siblings.toMutableList().also { it.add(to.index, id) }
        return table.with(parent.copy(slots = withSlot(parent.slots, to.slot, extended)))
    }

    /**
     * [slots] with [slot] holding exactly [children], or with [slot] gone when [children] is
     * empty. See the class KDoc: this is the applier's one canonicalisation and it is load
     * bearing for §26.2's property.
     */
    private fun withSlot(
        slots: Map<SlotName, List<NodeId>>,
        slot: SlotName,
        children: List<NodeId>,
    ): Map<SlotName, List<NodeId>> =
        if (children.isEmpty()) slots - slot else slots + (slot to children)

    /**
     * [table] with [parent]'s [slot] holding exactly [children].
     *
     * The parent is re-read from the table rather than taken from the caller's document,
     * because the ops above have already rewritten it: an insert into a node that a previous
     * op in the same patch moved, or renamed, must see that change. Re-reading is also what
     * makes [applyMove]'s two halves compose when both name the same parent.
     */
    private fun withChildren(
        document: UiDocument,
        table: NodeTable,
        parent: NodeId,
        slot: SlotName,
        children: List<NodeId>,
    ): UiDocument {
        val node = table.require(parent)
        return document.copy(nodes = table.with(node.copy(slots = withSlot(node.slots, slot, children))))
    }

    // -----------------------------------------------------------------------------------
    // One node's own fields
    // -----------------------------------------------------------------------------------

    /** Sets or clears one entry of `props`. See [PatchOp.SetProp] for why `null` is the answer. */
    private fun applySetProp(op: PatchOp.SetProp, document: UiDocument): UiDocument {
        val node = document.nodes.require(op.node)
        val replacement = op.new
        val props = if (replacement == null) {
            node.props - op.key
        } else {
            node.props + (op.key to replacement)
        }
        return document.copy(nodes = document.nodes.with(node.copy(props = props)))
    }

    /** Replaces the whole modifier chain. No sorting, ever — see [PatchOp.SetModifiers]. */
    private fun applySetModifiers(op: PatchOp.SetModifiers, document: UiDocument): UiDocument {
        val node = document.nodes.require(op.node)
        return document.copy(nodes = document.nodes.with(node.copy(modifiers = op.new)))
    }

    /** Sets or clears one event handler. */
    private fun applySetHandler(op: PatchOp.SetHandler, document: UiDocument): UiDocument {
        val node = document.nodes.require(op.node)
        val replacement = op.new
        val events = if (replacement == null) {
            node.events - op.event
        } else {
            node.events + (op.event to replacement)
        }
        return document.copy(nodes = document.nodes.with(node.copy(events = events)))
    }

    /** Sets or clears a node's label. The only op that touches no collection. */
    private fun applySetNodeName(op: PatchOp.SetNodeName, document: UiDocument): UiDocument {
        val node = document.nodes.require(op.node)
        return document.copy(nodes = document.nodes.with(node.copy(name = op.new)))
    }

    // -----------------------------------------------------------------------------------
    // The declarations
    // -----------------------------------------------------------------------------------

    /**
     * Writes or removes the declaration [PatchOp.PutRecord.ref] names.
     *
     * Switched on the [RecordRef] rather than on the record, because the ref is the address
     * and the record is the payload: a removal has no record to switch on at all, and a
     * `Record` carries no position for the two containers that need one. Eight arms, one per
     * container, and `RecordRef` is sealed — so a declaration added to `UiDocument` without a
     * reference here does not compile.
     *
     * The two halves of the op are not symmetrical and are not meant to be. A write is
     * addressed by the record it writes and keyed by that record's own id; a removal is
     * addressed by the ref, because the record is gone. Every arm therefore checks the one
     * thing that could disagree — that the ref's id and the record's id are the same id — and
     * a `PutRecord` naming one declaration while carrying another is a bug in the planner that
     * says so, at the point, with both names in the message.
     */
    private fun applyPutRecord(op: PatchOp.PutRecord, document: UiDocument): UiDocument {
        val ref = op.ref
        return when (ref) {
            is RecordRef.Page -> {
                val value = pageOf(op.new)
                document.copy(pages = put(document.pages, ref.id, value, value?.id?.value))
            }

            is RecordRef.Component -> {
                val value = componentOf(op.new)
                document.copy(components = put(document.components, ref.id, value, value?.id?.value))
            }

            is RecordRef.Theme -> {
                val value = themeOf(op.new)
                document.copy(themes = put(document.themes, ref.id, value, value?.id?.value))
            }

            is RecordRef.Resource -> {
                val value = resourceOf(op.new)
                document.copy(resources = put(document.resources, ref.id, value, value?.id?.value))
            }

            is RecordRef.DataModel -> {
                val value = dataModelOf(op.new)
                document.copy(dataModels = put(document.dataModels, ref.id, value, value?.id?.value))
            }

            is RecordRef.EnumType -> {
                val value = enumTypeOf(op.new)
                document.copy(enums = put(document.enums, ref.id, value, value?.id?.value))
            }

            // The two containers that are lists, and the only two arms that have to remember
            // *where*. See `RecordRef.State` and `putAt` for why the index is in the address
            // rather than nowhere, and why both of them share one implementation.
            is RecordRef.State -> document.copy(appState = putState(op.new, ref, document.appState))
            is RecordRef.HostFunction ->
                document.copy(hostFunctions = putHostFunction(op.new, ref, document.hostFunctions))
        }
    }

    /**
     * [values] with [key] set to [value], or without [key] when [value] is `null`.
     *
     * [carriedId] is the id the record being written claims for itself, and the check is the
     * whole of the address agreement: a `PutRecord` whose [RecordRef] names `t_dark` while
     * carrying a theme whose own id is `t_base` would otherwise write `t_base`'s content under
     * `t_dark`'s key, and the document would then hold two themes whose ids disagree with
     * their keys — a corruption no later pass reports, because §5.5's structural rules are
     * about nodes.
     *
     * `null` means remove, and there is nothing to check in that direction: a removal names
     * the key it wants gone and the id it checked was the key itself.
     */
    private fun <K, V> put(
        values: Map<K, V>,
        key: K,
        value: V?,
        carriedId: String?,
    ): Map<K, V> {
        require(carriedId == null || carriedId == key.toString()) {
            "the reference names '$key' but the record it carries is '$carriedId'"
        }
        return if (value == null) values - key else values + (key to value)
    }

    /**
     * `appState` with the declaration [ref] names written or removed at [ref]'s index.
     *
     * A one-line wrapper over [putAt], because `appState` and `hostFunctions` are the same
     * container shape and this arm is where the two of them meet. What differs is only how a
     * declaration is named — a `StateId` against §11.6's plain `String` — and [putAt]'s `idOf`
     * is what carries that difference, so the index protocol below is written once.
     */
    private fun putState(record: Record?, ref: RecordRef.State, declared: List<StateDecl>): List<StateDecl> =
        putAt(
            address = "RecordRef.State('${ref.id}') of appState",
            id = ref.id.value,
            index = ref.index,
            declared = declared,
            replacement = stateOf(record),
            idOf = { it.id.value },
        )

    /**
     * `hostFunctions` with the declaration [ref] names written or removed at [ref]'s index.
     *
     * The same protocol as [putState] and for the same reason: §11.6's declarations are a
     * `List`, a position is part of the address, and an inverse that appended instead of
     * restoring would leave a document holding the same declarations in a different order.
     */
    private fun putHostFunction(
        record: Record?,
        ref: RecordRef.HostFunction,
        declared: List<HostFunctionDecl>,
    ): List<HostFunctionDecl> =
        putAt(
            address = "RecordRef.HostFunction('${ref.id}') of hostFunctions",
            id = ref.id,
            index = ref.index,
            declared = declared,
            replacement = hostFunctionOf(record),
            idOf = { it.name },
        )

    /**
     * [declared] with the declaration [id] names written or removed at [index].
     *
     * Write, remove and replace are all one function because they are one operation with
     * three shapes, and splitting them would mean three functions each re-deriving where the
     * declaration currently sits — which is the only interesting fact here. [putState] and
     * [putHostFunction] are the same argument once more, one level out: two list containers
     * means two copies of a position protocol, and the second copy is the one nobody
     * cross-checks.
     *
     * An index one past the end is a legal insertion, as it is for a slot: it is the position
     * an appended declaration will have. Any other out-of-range index is a patch built for
     * another document.
     *
     * **A removal is checked against the occupant and a write is not**, which is the asymmetry
     * the whole of the protocol rests on. A write at `i` replaces whatever is there, so the
     * only thing that can disagree is the record's own id against [id]; a removal at `i`
     * deletes whatever is there, so it also has to check that the thing there is the thing
     * being removed. A caller that removes by a position something else has already shifted
     * gets the wrong declaration deleted, and §6.2's "ids are never reused" means nothing
     * would ever report it.
     *
     * [idOf] is how a declaration names itself, and it is a parameter rather than a comparison
     * written here because the two containers disagree about what a name is: `appState` keys a
     * checked `StateId` and `hostFunctions` holds §11.6's `name`, which §11.6 never made a
     * checked id. Both are read here as the string a comparison can make and a message can
     * print.
     *
     * [address] is what a message knows about the reference and [id] is what a comparison knows,
     * and they are separate parameters rather than one assembled string on purpose: building the
     * text here would mean this function knew which `RecordRef` variant it had been called for,
     * which is the switch's job above and would put the two containers' naming in the one place
     * that serves both.
     */
    private fun <D> putAt(
        address: String,
        id: String,
        index: Int,
        declared: List<D>,
        replacement: D?,
        idOf: (D) -> String,
    ): List<D> {
        val updated = declared.toMutableList()
        require(index in 0..updated.size) {
            "$address addresses position $index, which holds ${updated.size} declaration(s)"
        }

        val occupant = updated.getOrNull(index)

        if (replacement == null) {
            require(occupant != null && idOf(occupant) == id) {
                "$address removes position $index, which holds '${occupant?.let(idOf)}'"
            }
            updated.removeAt(index)
            return updated
        }

        require(idOf(replacement) == id) {
            "$address cannot carry a declaration whose id is '${idOf(replacement)}'"
        }
        if (occupant == null) updated.add(index, replacement) else updated[index] = replacement
        return updated
    }

    // -----------------------------------------------------------------------------------
    // The document's own fields
    // -----------------------------------------------------------------------------------

    /**
     * [PatchOp.SetTheme]: the document selects [PatchOp.SetTheme.new].
     *
     * **A `copy`, and no check at all**, which is the same shape [applySetProp] has and for
     * the same reason: [PatchOp.SetTheme.old] is the inverter's business and not this one's,
     * so nothing here reads it — an op is a claim, and a claim about the previous state is
     * checked by nothing in this file for the same reason it is not applied.
     *
     * **The selection is not checked against `themes`, and that is §14.2's total function
     * rather than a gap here.** A document selecting a theme it does not declare is a document
     * §17 reports, and a patch that refused to carry it would be refusing the *edit* — a user
     * picking a theme that is being created in the same transaction is the ordinary case, and
     * the ops for it are independent. Refusing here would also make the op's success depend on
     * the order the caller chose, which is the one thing about a patch this file refuses to
     * have an opinion on.
     */
    private fun applySetTheme(op: PatchOp.SetTheme, document: UiDocument): UiDocument =
        document.copy(theme = op.new)

    /**
     * [PatchOp.SetComponentVersion]: `DocumentMeta.componentVersions` records [PatchOp.SetComponentVersion.new]
     * for [PatchOp.SetComponentVersion.component].
     *
     * **A `copy` all the way down, and nothing checked**, for the same reason [applySetProp]
     * checks nothing: [PatchOp.SetComponentVersion.old] is the inverter's business, and an op is
     * a claim about both sides. What *is* checked here is the one thing the map makes worth
     * checking, and it is not checked — a `new` of `1` for a component whose current version is
     * `3` is written, because §19.1 makes those numbers the component author's business and a
     * patch that refused one would be refusing to *describe* a document. §27.2's
     * `plugin.version_mismatch` is where a document whose versions do not match the loaded
     * plugins is reported.
     *
     * The `null` arm is a **removal of the entry**, not a write of a null version: the field is a
     * `Map`, and §5.5 gives it an empty-map default, so a document that has never recorded a
     * component has no key for it rather than a key holding nothing. See that op's KDoc.
     */
    private fun applySetComponentVersion(op: PatchOp.SetComponentVersion, document: UiDocument): UiDocument {
        val recorded = document.meta.componentVersions
        val replacement: Int? = op.new
        val updated = if (replacement == null) recorded - op.component else recorded + (op.component to replacement)
        return document.copy(meta = document.meta.copy(componentVersions = updated))
    }

    // -----------------------------------------------------------------------------------
    // Reading a payload out of the record union
    // -----------------------------------------------------------------------------------

    /**
     * The eight functions below are the cost of pairing two sealed hierarchies.
     *
     * A single `when` over [Record] in each of `applyPutRecord`'s eight arms would need its
     * own exhaustiveness check eight times, and an `else` in each of those would throw away
     * the check for the case that matters: that the record is the kind its reference named.
     * These keep the check. Each throws a message that names the reference rather than
     * reporting a bare class, because the question a caller has is never "what class is this"
     * but "why did my page ref arrive holding a theme".
     */
    private fun pageOf(record: Record?): Page? = when (record) {
        null -> null
        is Record.Page -> record.value
        else -> throw IllegalArgumentException("a RecordRef.Page carries a page and nothing else")
    }

    private fun componentOf(record: Record?): ComponentDecl? = when (record) {
        null -> null
        is Record.Component -> record.value
        else -> throw IllegalArgumentException("a RecordRef.Component carries a component and nothing else")
    }

    private fun themeOf(record: Record?): ThemeDecl? = when (record) {
        null -> null
        is Record.Theme -> record.value
        else -> throw IllegalArgumentException("a RecordRef.Theme carries a theme and nothing else")
    }

    private fun resourceOf(record: Record?): ResourceDecl? = when (record) {
        null -> null
        is Record.Resource -> record.value
        else -> throw IllegalArgumentException("a RecordRef.Resource carries a resource and nothing else")
    }

    private fun dataModelOf(record: Record?): DataModelDecl? = when (record) {
        null -> null
        is Record.DataModel -> record.value
        else -> throw IllegalArgumentException("a RecordRef.DataModel carries a model and nothing else")
    }

    private fun enumTypeOf(record: Record?): EnumTypeDecl? = when (record) {
        null -> null
        is Record.EnumType -> record.value
        else -> throw IllegalArgumentException("a RecordRef.EnumType carries an enum type and nothing else")
    }

    private fun stateOf(record: Record?): StateDecl? = when (record) {
        null -> null
        is Record.State -> record.value
        else -> throw IllegalArgumentException("a RecordRef.State carries a state declaration and nothing else")
    }

    private fun hostFunctionOf(record: Record?): HostFunctionDecl? = when (record) {
        null -> null
        is Record.HostFunction -> record.value
        else -> throw IllegalArgumentException(
            "a RecordRef.HostFunction carries a host function declaration and nothing else",
        )
    }
}