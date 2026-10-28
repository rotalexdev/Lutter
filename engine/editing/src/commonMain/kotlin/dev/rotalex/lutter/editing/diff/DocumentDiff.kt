package dev.rotalex.lutter.editing.diff

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Record
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.editing.template.subtreeRows
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.StateId

/**
 * A patch from one document to another, and the only reader of both sides that builds one.
 *
 * PLAN §33.6 files this at `diff/DocumentDiff.kt` beside `migration/SpecMigrationPass.kt`, and
 * `PatchOp`'s KDoc is explicit that this file is a *consumer* of §26.2's vocabulary and not an
 * extension of it: the migration pass is "apply component migrations as a patch" and this is "a
 * patch between two documents". Nothing here adds an op or renames one — D31 extended the
 * vocabulary from `PatchOp.kt` and this file picked the two new arms up — and the one gap the
 * applier cannot paper over is written down below rather than worked around.
 *
 * ### What a diff is for, and why it is not a snapshot
 *
 * §26.1 chose "Command → forward/inverse Patch" over a snapshot per step precisely so that
 * diffing, syncing and undo all reuse one mechanism. This file is that reuse: two documents
 * go in, one [Patch] comes out, and [dev.rotalex.lutter.editing.patch.PatchApplier.apply] is
 * the only thing that turns it back into a document. A caller that wants "what changed" for a
 * user-facing summary gets the same answer the engine gets, because there is no second
 * comparison anywhere.
 *
 * ### The fields this refuses, and why refusing is better than approximating
 *
 * **[UiDocument.meta]'s identity and [UiDocument.app] have no op, so a pair that disagrees on
 * either is refused.** §26.2 gives them none and `PatchOp`'s KDoc gives the reason: they are
 * fixed for the life of a document — its name, its plugin requirements, its package and the page
 * it opens on — so a difference between them is not an edit and not a patch. [compute] throws
 * rather than emitting a patch that reproduces every other field and quietly leaves these two
 * behind, because a caller that received such a patch would apply it, see no error, and hold a
 * document that is neither side. The failure this replaces is the one `PatchApplier`'s guards
 * exist for, in the one place nothing downstream can catch it: nothing in the engine compares a
 * document's identity, and §17's passes walk nodes.
 *
 * The alternative — assuming them equal, as this file did before D31 — is not a smaller promise.
 * It is a *silent* one: the patch looks complete and is not, and the difference it dropped is the
 * difference between two different applications.
 *
 * **`DocumentMeta.componentVersions` is the one field of `meta` this file no longer refuses,
 * because an op for it exists.** [PatchOp.SetComponentVersion] is the twelfth op, added with
 * §19.3's migration pass, and D31's blanket rule could not survive it — not because the rule was
 * wrong but because it was stated wider than its own reasoning. It was reasoned from *"a
 * document's identity is fixed for its life"*, which is true of `name` and of `plugins` and is
 * **not** true of a record of what the document was written against: that record is stale the
 * moment a plugin ships, and §19.3 moves it in the same patch that moves the nodes it describes.
 * So [compute] compares `meta` with that one field blanked, and diffs it as a group of its own.
 * **The refusal is narrowed, not weakened**: a pair that disagrees on anything else about `meta`
 * is still refused, and the message still names both fields.
 *
 * ### What is now covered that was not, and what still is not
 *
 * D31 closed the other two gaps by extending the vocabulary rather than this file, and the two
 * extensions are used here with no special-casing: the theme selection is [PatchOp.SetTheme], and
 * §11.6's declarations go through [RecordRef.HostFunction] exactly as [RecordRef.State] does.
 * [RecordRef]'s own KDoc is the argument for the second one — a host function's name is a plain
 * `String`, because §11.6 never typed the callee — and [listOps] is the argument for treating the
 * two list containers with one protocol.
 *
 * **What is left is a kept row whose [dev.rotalex.lutter.model.doc.Node.type] changed.** There is
 * no op for it, `PatchApplier` has no branch for it, and so this file says nothing: the row comes
 * back with the type it had. That is a real gap and `PatchOp`'s KDoc names it as the op that
 * would close it rather than as something to work around here — a component type *is* edited, by
 * pasting from a palette, and a diff that quietly kept the old type would report a document that
 * is neither side. §19.3 did not close it and did not need to: a component contract migration
 * rewrites the *body* of a node of that type, never its type. It is stated here so that a caller
 * knows which pairs the identity below does not hold for.
 *
 * ### Determinism
 *
 * §6.2's determinism is a claim about bytes, and two calls on the same pair producing two
 * equal lists of ops is what makes a document diffable at all. Every iteration over a map
 * below is sorted explicitly — by the id's string form, since these are value classes over
 * `String` with no natural ordering — and every iteration over a node table goes through
 * [dev.rotalex.lutter.model.doc.NodeTable.ids], which is ascending by id by contract. Nothing
 * here depends on a hash map's iteration order.
 */
public object DocumentDiff {

    /**
     * The patch that turns [old] into [new]: `apply(compute(old, new), old) == new`.
     *
     * ### The ordering rule, which is the part a caller has to know
     *
     * A patch is a list because the ops can see each other's effects, so the order below is
     * not a presentation choice — every one of these groups relies on the ones before it having
     * run. The rule is **removals, then additions, then structural rewrites, then field edits,
     * then records, then the component versions, then the theme selection**:
     *
     *  1. **Removals.** Every row [old] holds and [new] does not, as one unplaced
     *     `RemoveSubtree` per maximal gone subtree, ascending by id. Unplaced on purpose: the
     *     parent that still lists the removed child is rewritten in step 3, and leaving the
     *     parent to be fixed here would mean computing an index that step 3 has not yet
     *     invalidated. It also means step 1 has no position precondition to satisfy, which is
     *     why the order within it is by id rather than by depth.
     *  2. **Additions.** One `PutNodes` holding every row [new] holds and [old] does not,
     *     parents before children. §26.2's applier requires such an id not to be in the table
     *     already, and step 1 has emptied it of everything that is leaving, so the two steps
     *     have to be in that order. Additions precede the rewrites for the same reason
     *     `PutNodes` exists ahead of the `PutRecord` that makes a page root reachable: a row
     *     exists before anything points at it.
     *  3. **Structural rewrites.** For a kept row whose `slots` changed — the case §5.6's
     *     normalization creates, where a node that moved is a change to *its parent* and not
     *     to itself — an unplaced `RemoveSubtree` of the old row followed by a `PutNodes` of
     *     the new one. This is where a reorder, a reparent and a lost child all land, and the
     *     two ops are a pair rather than a sequence for the reason on [rewrittenRows]: the
     *     applier refuses a `PutNodes` the table already holds, so the wrong way round does not
     *     corrupt a document, it throws.
     *  4. **Field edits.** For a kept row whose `slots` did *not* change, one op per differing
     *     field: `SetNodeName`, then `SetProp` per key in ascending key order, then
     *     `SetModifiers`, then `SetHandler` per key in ascending key order.
     *  5. **Records.** The six maps in a fixed order, then `appState` and `hostFunctions`,
     *     whose own two-phase order is described on [listOps].
     *  6. **The component versions**, ascending by type. The boundary between this group and
     *     the next is the one place in the rule where nothing forces a choice — an op that moves
     *     a number in `DocumentMeta.componentVersions` touches nothing a [PatchOp.SetTheme] reads,
     *     and nothing a `SetTheme` writes can affect it — so the group sits here because the
     *     theme selection was already last and keeping it there leaves [compute]'s documented
     *     claim ("the theme selection is emitted last") true rather than editing it a second
     *     time. Read it as a tie broken for stability, not as a dependency.
     *  7. **The theme selection**, last and unconditionally within its group. Its position is
     *     free for a reason worth stating: the op addresses one field of the document, names no
     *     node, no position and no declaration container, and no other group's validity can
     *     depend on it. It goes last because its
     *     subject is a declaration, and a patch that adds the theme and then selects it never
     *     passes through a document whose selection names something it does not hold. The
     *     reverse pairing cannot be had — a document that *removes* the theme it had selected
     *     passes through one intermediate state where the selection is dangling whichever order
     *     is chosen — and that limit is stated on [themeOps] rather than papered over.
     *
     * ### Why a rewrite rather than [PatchOp.MoveNode]
     *
     * `MoveNode` exists and it would express a reorder more cheaply, and it is not used here
     * for two reasons that are really one reason. Its `to` index is read against the slot
     * *after* `from` has been vacated, so a patch with several moves in one slot has to
     * account for the order its own earlier moves left the list in — a coupling between ops
     * that `PatchApplier`'s guards exist to catch and that a caller cannot check without
     * simulating the applier. Rewriting the parent row has no such rule: `PutNodes` has no
     * position at all, which is exactly why `PatchApplier` checks nothing for it, and the
     * whole of §5.6's new slot list is written in one op.
     *
     * The second reason is that nothing this file can reach is uniform about slot keys.
     * `PatchApplier` drops a key only when the op says that op created it — D33's `slotExisted`
     * — so whether a splice would preserve one document's spelling and lose the other's depends
     * on ops this file does not build. Rewriting writes `new`'s row verbatim, so the result is
     * `new`'s row rather than a slot list this file re-derived — and that is also why the
     * per-field ops are skipped for a rewritten row: the row that lands is the whole of it.
     *
     * ### The total function it is and is not
     *
     * On a pair of documents that agree on `app` and on everything in `meta` except
     * `componentVersions`, the identity above holds: every row of the result is `new`'s row,
     * every declaration of the result is `new`'s declaration, the selection is `new`'s selection,
     * the recorded versions are `new`'s recorded versions, and the two refused fields are equal
     * by assumption. The one exception the class KDoc names is a kept row whose `type` changed,
     * for which no op exists; everything else is produced by an op, which is what makes the claim
     * checkable one row at a time.
     *
     * ### What it assumes about the documents themselves
     *
     * Nothing structural. §6.2's invariants — one parent, no cycles, every slot naming a row
     * that exists — are §17.1's to report, and this file is a walk over whatever it is handed.
     * [subtreeRows] carries a visited set for exactly that reason: a cycle in a document being
     * diffed must terminate, and which part of it is wrong is the structural pass's answer.
     *
     * @param old the document the patch applies to.
     * @param new the document it produces.
     * @return the ops, in the order above; an empty list when the two documents are equal,
     *   which is a legal patch and not a degenerate one.
     * @throws IllegalArgumentException if [old] and [new] disagree on [UiDocument.app], or on
     *   any field of [UiDocument.meta] other than `componentVersions`, because no op can carry
     *   those. The patch would otherwise be a description of a third document, and this is a
     *   property of the *pair* rather than of one op, which is why it is the only refusal here
     *   and why it is an `IllegalArgumentException` rather than the `IllegalStateException`
     *   [dev.rotalex.lutter.model.doc.NodeTable.require] throws for a row that is not in the
     *   table.
     */
    public fun compute(old: UiDocument, new: UiDocument): Patch {
        require(identityOf(old) == identityOf(new) && old.app == new.app) {
            "a diff needs UiDocument.meta's identity and UiDocument.app to be equal, because no " +
                "PatchOp edits either — componentVersions is the one field of meta a PatchOp does " +
                "edit, so it is compared separately: meta is ${identityOf(old)} against " +
                "${identityOf(new)}, and app is " +
                "'${old.app.packageName}'/'${old.app.startPage}' against " +
                "'${new.app.packageName}'/'${new.app.startPage}'"
        }

        val ops: MutableList<PatchOp> = mutableListOf()

        ops += removedNodes(old, new)
        ops += addedNodes(old, new)
        ops += rewrittenRows(old, new)
        ops += editedFields(old, new)
        ops += recordOps(old, new)
        ops += componentVersionOps(old, new)
        ops += themeOps(old, new)

        return Patch(ops)
    }

    /**
     * [document]'s `meta` with `componentVersions` blanked out: the part of it a diff refuses
     * to compare because a difference in it is not an edit.
     *
     * **A `copy` rather than two comparisons of `name` and `plugins`**, because the rule is
     * about *which fields* are editable and a field-by-field spelling of it is one more place to
     * forget to update when a field is added. `DocumentMeta` is a data class, so blanking one
     * field and comparing is the whole of "compare everything else", and the message at
     * [compute] prints the blanked form — which is what makes the refusal readable: it names the
     * two records as they would have to be equal, with the one movable field already gone.
     */
    private fun identityOf(document: UiDocument): DocumentMeta =
        document.meta.copy(componentVersions = emptyMap())

    // -----------------------------------------------------------------------------------
    // The node table
    // -----------------------------------------------------------------------------------

    /**
     * One unplaced [PatchOp.RemoveSubtree] per subtree [old] holds and [new] does not.
     *
     * The capture is the gone rows below the root, root first, and only the *maximal* gone
     * rows get an op: a gone row's gone descendants are already inside an ancestor's capture,
     * and emitting them again would produce a second op for rows the first one deleted.
     * Ascending by id is therefore the whole of the determinism requirement, and it is the
     * same order a reader would pick.
     *
     * **The capture is filtered to the rows that are actually leaving, and that filter is the
     * whole difference between a subtree and a removal.** A subtree walk reaches whatever
     * [old] hangs below the root, and a node can be reparented out of a subtree that is going
     * away: drag a child onto a column that is staying and then delete the column it came
     * from, and the child's own row is in [new] and in [old]. Capturing it would have the
     * applier delete a row that the next three groups have no reason to write back — it is in
     * [old], so [addedNodes] will not call it added — and the result would be a document
     * missing a node it still has a parent for. So a row is captured if and only if it is
     * gone, and what the capture still means is the same thing: *these rows existed and
     * nothing points at them any more*.
     *
     * **Unplaced, and that is what makes this step position-free.** The applier's strictest
     * check — that the node an op claims at a position is the node actually there — exists
     * because a wrong-order document is invisible to every existence check in the engine.
     * Step 3 below replaces the parent row with `new`'s, and the parent's slot list goes with
     * it, so there is nothing for this step to get wrong about. `slotExisted = true` for the
     * same reason: there is no slot here, so there is no slot key this op can lose.
     */
    private fun removedNodes(old: UiDocument, new: UiDocument): List<PatchOp> {
        val gone: Set<NodeId> = old.nodes.ids().filterNot { it in new.nodes }.toSet()

        val ops: MutableList<PatchOp> = mutableListOf()
        val captured: MutableSet<NodeId> = mutableSetOf()

        for (id in old.nodes.ids()) {
            if (id !in gone || id in captured) continue
            val rows: List<Node> = subtreeRows(old, id).filter { it.id in gone }
            captured += rows.map { it.id }
            ops += PatchOp.RemoveSubtree(id, RemovedSubtree.unplaced(rows), slotExisted = true)
        }
        return ops
    }

    /**
     * One [PatchOp.PutNodes] holding every row [new] holds and [old] does not.
     *
     * A single op rather than one per subtree, for the reason the applier's guards give: it
     * refuses a row the table already holds, and rows that were *just* removed are in no slot
     * and no other op claims them. One op also means one place where "which rows are new" is
     * answered, which is the question a caller reading the patch has first.
     *
     * **Roots before their descendants, components in ascending id.** [subtreeRows] already
     * walks parents first, and a row is emitted under the first root that reaches it. The
     * `written` set is load-bearing rather than defensive: §6.2 gives every node one parent,
     * but two added roots can reach the same new row through a *kept* row between them — the
     * kept row is not in `referenced`'s scope, so both roots qualify — and a row written into
     * one `PutNodes` twice is refused as a duplicate by the applier. The order itself is for
     * the reader: `PutNodes` writes a table and does not care, but §5.3's "sorted when
     * written" habit is worth keeping in a list a person will read in a log.
     */
    private fun addedNodes(old: UiDocument, new: UiDocument): List<PatchOp> {
        val added: Set<NodeId> = new.nodes.ids().filterNot { it in old.nodes }.toSet()
        if (added.isEmpty()) return emptyList()

        // A row another added row already lists is written by that row's op. A row a *kept*
        // row lists is not, because the kept row's op is not carrying anything — it is a
        // rewrite of the parent's own slots, and that comes after this.
        val referenced: MutableSet<NodeId> = mutableSetOf()
        for (id in new.nodes.ids()) {
            if (id in added) referenced += childrenOf(new, id)
        }

        val rows: MutableList<Node> = mutableListOf()
        val written: MutableSet<NodeId> = mutableSetOf()

        for (id in new.nodes.ids()) {
            if (id !in added || id in referenced || id in written) continue
            for (row in subtreeRows(new, id)) {
                if (row.id in added && written.add(row.id)) rows += row
            }
        }

        return listOf(PatchOp.PutNodes(rows))
    }

    /**
     * For every kept row whose `slots` changed, the whole row replaced: an unplaced
     * [PatchOp.RemoveSubtree] of `old`'s row then a [PatchOp.PutNodes] of `new`'s.
     *
     * This is the step §5.6's normalization exists to make cheap, and it is why there is no
     * move logic anywhere in this file. §5.6 put a node's position in its **parent's slot**,
     * so a node dragged from index 0 to index 2 has not changed: its parent has. Comparing
     * the two slot maps is therefore the whole of "did anything move", and the parent is a row
     * this file can write.
     *
     * The two ops are not a pair that could be one, and their order inside the patch is not a
     * style choice either. `PutNodes` refuses an id the table already holds, so writing the
     * replacement first would *replace* a live row — silently deleting the node and everything
     * under it — except that it does not silently do it: the applier's guard turns the wrong
     * order round into an `IllegalArgumentException` naming the id. That is what makes this the
     * one ordering constraint in this file that a test can pin by watching something fail;
     * every other group boundary is free, because the ops in different groups touch disjoint
     * ids, containers and positions. Read as a unit the pair says: this row's content is now
     * that.
     *
     * **The whole slot map travels in the replacement row, so D33's question never arises here.**
     * A diff rewrites the parent rather than splicing one slot of it, which means a move, an
     * insert and a removal inside one slot are all the same single fact — "`before`'s `slots`
     * and `after`'s `slots` differ" — and the spelling each side used comes across inside the
     * two rows. `slotExisted = true` because the removal is unplaced and can lose no key.
     */
    private fun rewrittenRows(old: UiDocument, new: UiDocument): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        for (id in new.nodes.ids()) {
            if (id !in old.nodes) continue
            val before: Node = old.nodes.require(id)
            val after: Node = new.nodes.require(id)
            if (before.slots == after.slots) continue

            ops += PatchOp.RemoveSubtree(id, RemovedSubtree.unplaced(listOf(before)), slotExisted = true)
            ops += PatchOp.PutNodes(listOf(after))
        }
        return ops
    }

    /**
     * One op per differing field of a kept row whose `slots` did not change.
     *
     * Rows that [rewrittenRows] already owns are skipped: the `PutNodes` in that step carries
     * the whole new row, so a `SetProp` for the same row would be a second description of a
     * change already made.
     *
     * Every map here is walked as the union of both key sets, so a property that was cleared
     * and a handler that was added both produce an op with a null on one side — which is
     * exactly what [PatchOp.SetProp] and [PatchOp.SetHandler] mean by a null, and why an
     * absent key is not the same fact as a key holding null. Keys are sorted on their string
     * form because these are value classes over `String` with no natural ordering, and
     * [dev.rotalex.lutter.model.doc.NodeTable.ids] sorts the same way for the same reason.
     *
     * Within a row the order is name, props, modifiers, handlers, and [PatchOp.SetModifiers]
     * replaces the whole chain rather than one entry because order is the meaning — a chain
     * that was reordered has no "changed entry", only a changed list.
     */
    private fun editedFields(old: UiDocument, new: UiDocument): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        for (id in new.nodes.ids()) {
            if (id !in old.nodes) continue
            val before: Node = old.nodes.require(id)
            val after: Node = new.nodes.require(id)
            if (before.slots != after.slots) continue

            if (before.name != after.name) {
                ops += PatchOp.SetNodeName(id, before.name, after.name)
            }

            for (key in (before.props.keys + after.props.keys).sortedBy { it.value }) {
                val previous = before.props[key]
                val replacement = after.props[key]
                if (previous != replacement) ops += PatchOp.SetProp(id, key, previous, replacement)
            }

            if (before.modifiers != after.modifiers) {
                ops += PatchOp.SetModifiers(id, before.modifiers, after.modifiers)
            }

            for (key in (before.events.keys + after.events.keys).sortedBy { it.value }) {
                val previous = before.events[key]
                val replacement = after.events[key]
                if (previous != replacement) ops += PatchOp.SetHandler(id, key, previous, replacement)
            }
        }
        return ops
    }

    /** The child ids the row [id] names, or nothing for an id the table does not hold. */
    private fun childrenOf(document: UiDocument, id: NodeId): List<NodeId> =
        document.nodes[id]?.slots?.values?.flatten().orEmpty()

    // -----------------------------------------------------------------------------------
    // The declarations
    // -----------------------------------------------------------------------------------

    /**
     * Every differing declaration, in a fixed container order.
     *
     * The order is written out rather than derived because nothing requires it to be any
     * particular order and determinism requires it to be *this* one: `PutRecord` ops on
     * different containers cannot see each other, so the list is only ever read by a person or
     * a log. `appState` and `hostFunctions` come last because they are the two containers with
     * a position in them, and [listOps] is where that becomes visible.
     *
     * Every container in `UiDocument` is here. §5.5 declares seven and D31 added the eighth,
     * and a container left out would be one this file silently reproduces as equal — which is
     * the failure the class KDoc's refusal was written to prevent, arrived at by a different
     * route.
     */
    private fun recordOps(old: UiDocument, new: UiDocument): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        ops += mapOps(old.pages, new.pages, { RecordRef.Page(it) }, { Record.Page(it) })
        ops += mapOps(old.components, new.components, { RecordRef.Component(it) }, { Record.Component(it) })
        ops += mapOps(old.themes, new.themes, { RecordRef.Theme(it) }, { Record.Theme(it) })
        ops += mapOps(old.resources, new.resources, { RecordRef.Resource(it) }, { Record.Resource(it) })
        ops += mapOps(old.dataModels, new.dataModels, { RecordRef.DataModel(it) }, { Record.DataModel(it) })
        ops += mapOps(old.enums, new.enums, { RecordRef.EnumType(it) }, { Record.EnumType(it) })

        ops += listOps(
            old.appState,
            new.appState,
            { id, index -> RecordRef.State(StateId(id), index) },
            { Record.State(it) },
            { it.id.value },
        )
        ops += listOps(
            old.hostFunctions,
            new.hostFunctions,
            { name, index -> RecordRef.HostFunction(name, index) },
            { Record.HostFunction(it) },
            { it.name },
        )

        return ops
    }

    /**
     * The [PatchOp.PutRecord]s for one declaration map, one per key that differs, ascending.
     *
     * One generic helper rather than six hand-written loops, and the reason is the address check.
     * Each op names a key in its [RecordRef] and carries a record that names its own id, and
     * [dev.rotalex.lutter.editing.patch.PatchApplier] refuses the two when they disagree. The
     * two lambdas are what pair them; writing them out six times would have put that pairing
     * in six places, and a sixth one nobody cross-checks is the kind of thing that rots.
     *
     * `null` on one side is an insertion or a removal and is how the two are told apart
     * without a flag — the applier's own contract for `PutRecord`.
     *
     * Sorted on `toString()` rather than on the key, because `K` is an id type and the map's
     * key order is the document's, not a contract. `IdSyntax` restricts an id to letters,
     * digits and underscore, so this is the same order `NodeTable.ids` publishes.
     */
    private fun <K, V> mapOps(
        before: Map<K, V>,
        after: Map<K, V>,
        ref: (K) -> RecordRef,
        record: (V) -> Record,
    ): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        for (id in (before.keys + after.keys).sortedBy { it.toString() }) {
            val previous = before[id]
            val replacement = after[id]
            if (previous == replacement) continue
            ops += PatchOp.PutRecord(ref(id), previous?.let(record), replacement?.let(record))
        }
        return ops
    }

    /**
     * The [PatchOp.PutRecord]s that turn one declaration **list** into another, in two phases.
     *
     * [UiDocument.appState] and [UiDocument.hostFunctions] are the two containers that are
     * lists rather than maps, and [RecordRef.State] and [RecordRef.HostFunction] carry an index
     * for that. That index turns out to be the awkward part, and it is worth being precise about
     * what the applier will and will not accept at it:
     *
     *  * **Writing** at index `i` with a record in hand replaces whatever occupies `i`, and
     *    only appends when `i` is one past the end. So "insert at `i`" is really "overwrite
     *    the occupant, or append", which is exactly right for producing a final list one
     *    position at a time and is why the first phase can be ascending.
     *  * **Removing** at index `i` is refused unless position `i` holds the declaration the
     *    ref names. A removal therefore cannot be issued by the index the caller computed
     *    against `before` if anything has already shifted that position.
     *
     * So: **writes first, ascending, then removals, descending.** A replacement moves nothing,
     * so index `i` still holds `before[i]` when its op runs; a removal moves everything above
     * it down by one, and going from the top down means a later removal never addresses a
     * position an earlier one has already changed.
     *
     * Every index the two lists share is compared positionally rather than by id, and that is
     * deliberate: `["a","b"] → ["b","a"]` is a different document even though it holds the
     * same two declarations, which is the sentence [RecordRef.State]'s KDoc makes when it
     * explains why the index is in the address.
     *
     * ### One function for both containers, and what that is and is not
     *
     * [idOf] and the two lambdas are the whole of the difference between them, and there is
     * nothing to be gained by writing the protocol twice: §12.1's state declarations key a
     * checked `StateId` while §11.6's host functions are named by a plain `String`, and the
     * *protocol* — which positions, which order, which check — is identical, because
     * [PatchApplier] implements it identically too. A second copy would be a second thing to
     * keep in step with the applier, and nothing about it would be easier to read.
     *
     * What is not shared is the naming: `idOf` reads a declaration's own name, and that name
     * is what [RecordRef.State]'s typed id and [RecordRef.HostFunction]'s string both reduce to
     * when a comparison or a message needs it.
     */
    private fun <D> listOps(
        before: List<D>,
        after: List<D>,
        ref: (String, Int) -> RecordRef,
        record: (D) -> Record,
        idOf: (D) -> String,
    ): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        for (index in 0 until after.size) {
            val previous = before.getOrNull(index)
            val replacement = after[index]
            if (previous == replacement) continue
            ops += PatchOp.PutRecord(
                ref(idOf(replacement), index),
                previous?.let(record),
                record(replacement),
            )
        }

        for (index in before.size - 1 downTo after.size) {
            val removed = before[index]
            ops += PatchOp.PutRecord(
                ref(idOf(removed), index),
                record(removed),
                null,
            )
        }
        return ops
    }

    /**
     * The [PatchOp.SetComponentVersion]s that turn one recorded version map into another.
     *
     * Written like [mapOps] and for the same reason: one op per key that differs, ascending by
     * the type's string form, so that "which components moved" is a single readable list and
     * two engines walk a pair in the same order. `null` on one side is an entry added or dropped,
     * which is how the two are told apart without a flag and what
     * [dev.rotalex.lutter.editing.patch.PatchApplier]'s `null` arm already means.
     *
     * **This is the group §19.3's migration patch is read against.** The pass does not use this
     * file — it builds its ops from the registry's steps, one node at a time, and that is the
     * more reviewable shape for an upgrade because each step's contribution is legible on its
     * own. What this group buys is the other direction: two documents where one has been
     * upgraded diff to a patch that says *only* what the upgrade changed to the record, which is
     * the question "did this document get migrated, and what did the engine think about it?"
     * and the one a collaboration round trip has to be able to answer.
     */
    private fun componentVersionOps(old: UiDocument, new: UiDocument): List<PatchOp> {
        val before = old.meta.componentVersions
        val after = new.meta.componentVersions
        val ops: MutableList<PatchOp> = mutableListOf()

        for (type in (before.keys + after.keys).sortedBy { it.value }) {
            val previous = before[type]
            val replacement = after[type]
            if (previous == replacement) continue
            ops += PatchOp.SetComponentVersion(type, previous, replacement)
        }
        return ops
    }

    /**
     * The one [PatchOp.SetTheme], or nothing.
     *
     * A single op rather than a pair of puts, because the selection is one nullable field and
     * [PatchApplier] has one branch for it. The comparison is plain equality on the id, which
     * is §6.2's rule for ids made visible: two selections are the same fact when they name the
     * same theme, including when both name none, and §14.2's `null` is a selection rather than
     * an absence — which is why an unchanged document produces nothing here rather than an op
     * setting `null` to `null`.
     *
     * ### Where it runs, and the one case the order cannot save
     *
     * [compute]'s ordering rule puts this last, and the reason is that a selection is only
     * meaningful against the `themes` map the records above it write: a patch that adds
     * `t_base` and then selects it never passes through a document whose selection names a
     * theme it does not hold.
     *
     * **That is one direction only, and the other one is stated rather than hidden.** A
     * document that removes the theme it had selected must put both facts in one patch, and
     * whichever order they are issued in, one intermediate document is out of step: with the
     * removal first the selection dangles for the length of one op, with the selection first the
     * selection falls back to §14.2's rule while the theme still exists. Nothing here decides
     * which of those two is worse, because the applier checks neither and §14.2's resolution is
     * a total function of both fields at once — a patch is not a document, and §6.2's invariants
     * are properties of the document each op leaves behind.
     */
    private fun themeOps(old: UiDocument, new: UiDocument): List<PatchOp> =
        if (old.theme == new.theme) {
            emptyList()
        } else {
            listOf(PatchOp.SetTheme(old.theme, new.theme))
        }
}
