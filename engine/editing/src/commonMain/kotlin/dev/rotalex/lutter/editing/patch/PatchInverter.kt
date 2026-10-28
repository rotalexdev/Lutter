package dev.rotalex.lutter.editing.patch

/**
 * Turns a patch into the patch that undoes it.
 *
 * ### Why this needs no document
 *
 * This is the property §26.2 names immediately after the `PatchOp` declaration, and it is the
 * reason the op set looks the way it does. Read [PatchOp] op by op: every one of them carries
 * the state it is about to replace.
 *
 *  * [PatchOp.SetProp], [PatchOp.SetModifiers], [PatchOp.SetHandler],
 *    [PatchOp.SetNodeName], [PatchOp.SetTheme] and [PatchOp.SetComponentVersion] have a field
 *    named `old`.
 *  * [PatchOp.PutRecord] has one too, and it is a whole declaration.
 *  * [PatchOp.MoveNode] has [PatchOp.MoveNode.from], and the two placements are each other's
 *    negation.
 *  * [PatchOp.RemoveSubtree] has [PatchOp.RemoveSubtree.captured], which is the entire subtree
 *    *and* the position it was lifted from — or, for the form [PatchOp.PutNodes] produces, the
 *    subtree and the fact that there was no position.
 *  * [PatchOp.InsertNodes] and [PatchOp.PutNodes] need no capture, because each is holding the
 *    new state and the place it went to, and the two facts about the document it leaves behind
 *    — which nodes, where — are exactly what [PatchOp.RemoveSubtree] needs.
 *
 *  * [PatchOp.InsertNodes] and [PatchOp.RemoveSubtree] each carry a `slotExisted`, and the two
 *    arms below move it across without looking at it. It is the hardest case of the same
 *    property, because the document's answer to it is not a value anywhere: `Node.slots` spells
 *    an absent key and an empty list identically to every reader, so "was the key there" has to
 *    be captured at the moment the op met the document or not at all.
 *
 * So an inverse is a function of the op alone. There is no lookup, no parent index, no
 * "where was this node before", and — the consequence that matters for undo — **no way to
 * compute an inverse against a document that has moved on.** An editor applies a patch, the
 * user keeps working, and twenty edits later the undo button is pressed: by then the document
 * has been through twenty other patches, and an inverter that re-read the current state to
 * find what used to be there would be answering a different question from the one the user
 * asked.
 *
 * The price is that every op holds two versions of the change rather than one, which §26.1
 * accepted in exchange for exactness, and that a command has to capture the old state at the
 * moment it plans the edit rather than at the moment it applies it. Both are visible costs and
 * neither is avoidable without giving up the property.
 *
 * ### Reversal is part of the job, not an afterthought
 *
 * [invert] reverses the order as well as the ops. §26.2's property is stated over a whole
 * patch, and a two-op patch whose ops are individually invertible can still be uninvertible if
 * the second one addresses a position the first has already changed. Which is why the order is
 * reversed here and nowhere else: a caller that inverted each op and kept the order would have
 * produced a `Patch` that compiles, satisfies every per-op round trip, and fails on the only
 * property anybody cares about.
 *
 * ### What this file deliberately does not do
 *
 * It does not validate. An op whose `old` does not match the document is not this file's
 * problem and not this file's evidence — the op is a claim, [PatchApplier] is where a claim is
 * checked against a document, and a check here would either duplicate that one or replace it
 * with a worse version that could not see the document at all.
 */
public object PatchInverter {

    /**
     * [patch] with every op replaced by its inverse, and the ops in reverse order.
     *
     * Total: there is no op for which no inverse can be written, which is the property the
     * closed set was chosen to buy. An op that could not carry its own `old` would have to add
     * a case here that throws, and a `when` that throws is the shape of a design that did not
     * hold.
     */
    public fun invert(patch: Patch): Patch = Patch(patch.ops.asReversed().map { inverseOf(it) })

    /**
     * The op that undoes [op] against the document [op] was applied to.
     *
     * `asReversed()` above is a view, and `map` copies it, so the returned list is an ordinary
     * list in the reversed order and holds no reference to the original.
     */
    private fun inverseOf(op: PatchOp): PatchOp = when (op) {
        // The pair is exact rather than reconstructed: a capture already holds the subtree and
        // the position in the shape an insertion takes, so the inverse is four fields moved from
        // one record to the other. The `root` is `nodes.first()` for the same reason `InsertNodes`
        // has no root field of its own, and `slotExisted` moves across unchanged for the same
        // reason `old` does on `SetProp`: it is a fact about the document *before* the op, and
        // deciding it here would make an inverse depend on a document that has moved on since —
        // which is the one thing this file exists to make impossible.
        is PatchOp.InsertNodes -> PatchOp.RemoveSubtree(
            root = op.nodes.first().id,
            captured = RemovedSubtree(op.parent, op.slot, op.index, op.nodes),
            slotExisted = op.slotExisted,
        )

        // The other half of the same pair, with the position it never had. `unplaced` is what
        // makes this a two-line inversion rather than a reconstruction: the rows are the whole
        // capture, and their absence from a slot is the one fact that has to be invented.
        //
        // `slotExisted = true` because an unplaced removal touches no slot and so can lose no
        // slot key — which is what the field says once the capture has no `slot` to be about.
        // It is never read (see `PatchApplier.applyRemove`, which returns before it does), and
        // it is not defaulted, because §6.2 writes a patch that fills in its own values out of
        // the wire format.
        is PatchOp.PutNodes -> PatchOp.RemoveSubtree(
            root = op.nodes.first().id,
            captured = RemovedSubtree.unplaced(op.nodes),
            slotExisted = true,
        )

        // The unplaced arm is not a fallback for a malformed capture. A capture is either
        // placed or unplaced and the two never interleave: `placement` is null unless all
        // three position fields agree, so anything else is an unplaced removal — which is the
        // form D30 added, and the one `PutNodes` above produces.
        //
        // `slotExisted` crosses back the way it came. That is the whole of D33's inverse: the
        // flag says which spelling of "this slot holds nothing" the document had, it is the
        // insertion's to state and the removal's to act on, and nothing here has to know which
        // of the two it is.
        is PatchOp.RemoveSubtree -> {
            val from: Placement? = op.captured.placement
            if (from == null) {
                PatchOp.PutNodes(op.captured.nodes)
            } else {
                PatchOp.InsertNodes(
                    parent = from.parent,
                    slot = from.slot,
                    index = from.index,
                    nodes = op.captured.nodes,
                    slotExisted = op.slotExisted,
                )
            }
        }

        // Each placement is the other's. `PatchApplier` reads `to.index` against the list as it
        // is once `from` has been vacated, and swapping the two turns that rule into itself —
        // which is the whole reason a move needs no adjustment arithmetic.
        is PatchOp.MoveNode -> PatchOp.MoveNode(op.id, from = op.to, to = op.from)

        is PatchOp.SetProp -> PatchOp.SetProp(op.node, op.key, old = op.new, new = op.old)

        is PatchOp.SetModifiers -> PatchOp.SetModifiers(op.node, old = op.new, new = op.old)

        is PatchOp.SetHandler -> PatchOp.SetHandler(op.node, op.event, old = op.new, new = op.old)

        // Including the nullable pair: `old = null, new = v` inverts to `old = v, new = null`,
        // which is what clears a key. An inverter that treated null as "nothing to do" would
        // leave a property the user set still set after undoing it.
        is PatchOp.SetNodeName -> PatchOp.SetNodeName(op.node, old = op.new, new = op.old)

        is PatchOp.PutRecord -> PatchOp.PutRecord(op.ref, old = op.new, new = op.old)

        // The two nullable ids swap, which is the whole of it and the reason this op is in the
        // set at all. An inverter that read `new` for both sides would turn an undo into a
        // re-application: `SetTheme(t_dark, null)` — the user dropping their theme — would
        // become `SetTheme(null, null)` and leave the document exactly as wrong as it was.
        is PatchOp.SetTheme -> PatchOp.SetTheme(old = op.new, new = op.old)

        // The same swap over the same nullable pair, and the reason §19.3's undo story needs
        // this file to exist as much as it needs the op: a document that has stopped recording
        // a component (`old = 2, new = null`) inverts to one that records it again at v2, which
        // is the only shape that restores "this document was written against m3.Text v2" rather
        // than leaving the upgrade half-undone. `component` is not swapped because it is the
        // key on both sides, exactly as it is the address on [PatchOp.SetProp.node].
        is PatchOp.SetComponentVersion -> PatchOp.SetComponentVersion(op.component, old = op.new, new = op.old)
    }
}