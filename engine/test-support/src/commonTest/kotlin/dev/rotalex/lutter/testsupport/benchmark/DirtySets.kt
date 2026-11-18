package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.index.DocumentIndex

/**
 * §6.2:537's caller half: one patch's dirty set, folded from the ops this side can see.
 *
 * `:engine:analysis` may not see `:engine:editing`, and the dirty-set factory in
 * `:engine:model` sees no patch at all — it takes the named ids, the vacated placements and
 * the record signal as plain data ([DirtyNodes.of] carries the whole contract). Somebody has
 * to stand between the two and fold a real [PatchOp] list into those inputs, and that
 * somebody is the caller that owns both modules: today the benchmark's incremental row, and
 * phase 13's equality loop after it.
 *
 * The fold is the contract, restated:
 *
 *   * **The named ids** are [Patch.touchedNodes] as it answers — the ops that name nodes
 *     carry them, and the three record kinds name none.
 *   * **The vacated placements** are the ones the document as the edits left it can no longer
 *     show: a removal's [RemovedSubtree.parent] and a move's `from` parent. Both are on no
 *     touched id's chain in the new document — the removal's ids are gone from it, and a
 *     move's `from` parent is off the moved node's new chain — yet their slot lists are
 *     exactly what changed, so the fold adds them to the touched set itself, and
 *     [DocumentIndex] closes the chains over the document as the edits left it.
 *   * **The record signal** is conservative and type-shaped: any of [PatchOp.PutRecord],
 *     [PatchOp.SetTheme] and [PatchOp.SetComponentVersion] answers
 *     `recordsAffectAllNodes`, because the ops' KDoc explains why no patch-side fact can say
 *     *which* record moved, and a narrower rule would be a silently smaller set. A caller
 *     that wants the narrow rule owes the seam the widening first, as the factory's KDoc
 *     says.
 *
 * [document] is the document **as the edits left it**, which is what [DirtyNodes.of]'s KDoc
 * asks the index to be built over.
 */
internal fun dirtySetFor(patch: Patch, document: UiDocument): DirtyNodes {
    val touched = LinkedHashSet(patch.touchedNodes)
    var records = false
    for (op in patch.ops) {
        when (op) {
            is PatchOp.RemoveSubtree -> op.captured.parent?.let { touched.add(it) }
            is PatchOp.MoveNode -> touched.add(op.from.parent)
            is PatchOp.PutRecord -> records = true
            is PatchOp.SetTheme -> records = true
            is PatchOp.SetComponentVersion -> records = true
            // InsertNodes, PutNodes, SetProp, SetModifiers, SetHandler and SetNodeName: the
            // named ids are already in `touchedNodes`, and everything else the edit disturbs
            // is on a touched id's chain in the document as the edits left it.
            else -> {}
        }
    }
    return DirtyNodes.of(touched, DocumentIndex(document), records)
}
