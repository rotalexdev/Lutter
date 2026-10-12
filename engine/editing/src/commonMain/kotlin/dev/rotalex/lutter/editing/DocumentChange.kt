package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.model.ids.NodeId

/**
 * One applied patch, announced so an editor can re-render without diffing the document.
 *
 * PLAN §25.2 describes `changes` as *"Patch + touched node ids"* and this is that record with
 * the two halves named. The third field, [revision], is an addition and it is there for one
 * reason: **a collector of a hot flow needs to know which state it is now in.** `state` and
 * `changes` are two flows, and a collector woken for a change may read `state.value` after a
 * further edit has landed — so without a revision it cannot tell a patch it is about to render
 * from one two edits out of date. `state.revision == change.revision` is the only place where
 * the two are guaranteed to be about the same moment.
 *
 * ### What an undo announces
 *
 * **The inverse patch, not the original.** An undo is an edit, it changes the document, and an
 * editor that repaints from this flow has to be told. Announcing the original patch instead
 * would be announcing an edit that is no longer happening, and the touched-node set with it —
 * which for a `RemoveSubtree` is precisely the set of nodes the editor would then try to find.
 *
 * ### What is not announced
 *
 * * **A command that was refused.** §26.3 says a refused command changes nothing, so there is
 *   no document change to announce. The caller learns from the `CommandResult` it was handed.
 * * **A patch with no ops.** §26.2 says an empty patch is legal and names it as the outcome of
 *   a command that had nothing to do. Announcing it would wake a renderer to repaint an
 *   identical document, and would advance a revision for no change.
 * * **The label or the merge key.** §25.1 keeps an editor's own copy in the editor; this record
 *   carries what the engine changed and nothing an editor would have to translate.
 *
 * ### Not persisted
 *
 * There is no `@Serializable` here and there should not be one. A change is something that
 * happened in one session to one document; §26.3 puts history outside the document, and the
 * patch that undoes it is already [dev.rotalex.lutter.editing.history.History]'s business.
 *
 * @see Patch for what the ops mean and what `touchedNodes` deliberately does not include.
 * @see DocumentState for the snapshot this record moves.
 */
public data class DocumentChange(

    /**
     * The [DocumentState.revision] this change produced.
     *
     * Equal to the controller's revision immediately after the change was applied, so a
     * collector that sees `change.revision == state.value.revision` knows the two describe the
     * same moment and `change.patch` is what moved the document into `state.value.document`.
     */
    public val revision: Long,

    /**
     * The patch that was applied, forwards or backwards.
     *
     * For an undo this is the inverse of the entry that was undone, so applying this patch to
     * the document the change was announced from undoes the change itself.
     */
    public val patch: Patch,
) {

    /**
     * The ids of the nodes [patch] names.
     *
     * A getter and not a stored field, for the reason [dev.rotalex.lutter.editing.patch.RemovedSubtree.placement]
     * is one: it is [Patch.touchedNodes] with a different name, and a second copy of it is a
     * second thing a reader could compare against the patch and find disagreeing. The set is
     * rebuilt per read, which is a fold over a list of ops.
     *
     * "Named", not "reach": inserting a child changes how its parent's layout comes out, and
     * the parent is not in this set. An editor that needs the render-affecting set has to close
     * it itself — see `Patch.touchedNodes` for the same statement in the place it originates.
     */
    public val touchedNodes: Set<NodeId>
        get() = patch.touchedNodes
}