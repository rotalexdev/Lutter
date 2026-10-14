package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.ids.NodeId
import kotlinx.serialization.Serializable

/**
 * An ordered list of edits: what a command produced, what undo replays backwards, and what
 * `DocumentDiff` (§33.6) emits between two documents.
 *
 * PLAN §26.2 declares one field and two members. This declares the same, and the whole of the
 * design is in the field's type being a `List` rather than a `Set`: **a patch is a sequence,
 * because the ops in it can see each other's effects.** An insertion at index 2 followed by a
 * removal from index 3 is not two independent facts about a document — the second one is
 * about the document the first one produced. Reordering them is not a canonicalization, it is
 * a different edit.
 *
 * ### The two members, and what each is for
 *
 * [inverse] is the undo/redo mechanism and is the reason every op in [PatchOp] carries its own
 * `old` value. [touchedNodes] is the cheap question an incremental editor asks before it
 * bothers: *which nodes does this change reach?* It is a property of the patch alone, which is
 * what makes it answerable without applying anything.
 *
 * ### Determinism, which is a property of this record and not of its caller
 *
 * §26.2's property has two halves and both are decided here. `apply(p, d)` is deterministic
 * because [PatchApplier] is a pure function of a patch and a document and the document is
 * compared by structure (§6.2), and `apply(p.inverse(), apply(p, d)) == d` holds because the
 * ops carry their own inverses. What *this* class owns is the second half of both: the order
 * of [ops] is the order they are applied in and the order [inverse] reverses, and
 * [touchedNodes] is built in op order so that two equal patches iterate their touched nodes
 * in the same order on every target.
 *
 * ### An empty patch is a legal patch
 *
 * `Patch(emptyList())` applies to nothing, inverts to nothing, and touches nothing. That is not
 * a degenerate case to be guarded against — it is what a command that validated successfully
 * and then found there was nothing to do produces, and §26.3's rule that a *failed* command
 * never enters history is enforced by the caller, not by refusing to represent the outcome.
 */
@Serializable
public data class Patch(

    /**
     * The edits, in the order they apply.
     *
     * No default. A patch with no ops still says it has none, which is the same decision
     * `UiDocument.nodes` makes: a record that fills in its own emptiness is a record whose wire
     * form has a default this repository does not encode (§6.2).
     */
    public val ops: List<PatchOp>,
) {

    /**
     * This patch undone: every op replaced by its own inverse, in reverse order.
     *
     * The reversal is not an optimization and not a nicety — it is what makes a patch of
     * interacting ops invertible at all. An insertion at index 1 and a removal from index 1 are
     * the same index only until one of them runs; undone in the order they were applied, the
     * second one addresses a document the first has not yet restored, and either it removes the
     * wrong node or [PatchApplier] refuses it outright. Reversed, each op meets the document
     * in exactly the shape it left.
     *
     * Delegates to [PatchInverter], which is where the per-op answers live.
     */
    public fun inverse(): Patch = PatchInverter.invert(this)

    /**
     * The ids of the nodes the ops name.
     *
     * "Name", not "reach": this is the set an editor can compute from the patch alone, with no
     * document in hand, and it is deliberately not a set of "every node whose rendering could
     * change". Those two are different, and the difference is worth stating rather than
     * discovering. Inserting a child into a `Column` changes that column's layout, so the
     * *parent* is affected — and the parent is not in this set, because an op names the child
     * and the slot it went into, not the parent it landed on. A consumer that needs the
     * render-affecting set must close it itself, which is what `diff/DocumentDiff.kt`
     * (§33.6) exists to do: it compares two whole documents and so can see every node either
     * one changed.
     *
     * A [PatchOp.PutRecord] contributes nothing, and that is a fact about the type rather than
     * an oversight: it addresses a declaration, and a declaration is not a `NodeId`. Editing a
     * page's route does not touch a node — it changes what the page *is*. [PatchOp.SetTheme]
     * and [PatchOp.SetComponentVersion] contribute nothing for the same reason and one step
     * further out: each addresses one field of the document itself, so there is no node in the
     * address at all, and the rule this set already follows is that an op contributes the
     * `NodeId`s it names. Inventing a sentinel node id for them would put a value in the set
     * that names nothing a document holds, and every consumer of this set would then have to
     * know which ids are real.
     *
     * Built in op order into a `LinkedHashSet`, so two equal patches iterate in the same order
     * on every target. `Set` equality is order-independent (§6.2) and iteration is not, and
     * determinism is the claim this type is making.
     *
     * A getter and not a stored field. A property with a backing field inside a `@Serializable`
     * class is serialized, and a `touchedNodes` on the wire would be a second, derived source
     * of truth for something anybody can compute — the exact kind of redundancy §6.2's "one
     * fact, one place" is about. The cost is recomputation, which for a list of ops is a fold.
     */
    public val touchedNodes: Set<NodeId>
        get() {
            val touched = LinkedHashSet<NodeId>()
            for (op in ops) {
                when (op) {
                    is PatchOp.InsertNodes -> for (node in op.nodes) touched.add(node.id)
                    is PatchOp.PutNodes -> for (node in op.nodes) touched.add(node.id)
                    is PatchOp.RemoveSubtree -> {
                        touched.add(op.root)
                        for (node in op.captured.nodes) touched.add(node.id)
                    }

                    is PatchOp.MoveNode -> touched.add(op.id)
                    is PatchOp.SetProp -> touched.add(op.node)
                    is PatchOp.SetModifiers -> touched.add(op.node)
                    is PatchOp.SetHandler -> touched.add(op.node)
                    is PatchOp.SetNodeName -> touched.add(op.node)

                    is PatchOp.PutRecord -> {
                        // Addresses a declaration, and a declaration is not a `NodeId`.
                    }

                    // Names no node at all: this op is the document's own pointer at one of
                    // its declarations, so there is nothing to add. See the KDoc.
                    is PatchOp.SetTheme -> {
                        // The document's selection, and a selection is not a node.
                    }

                    is PatchOp.SetComponentVersion -> {
                        // The document's record of what it was written against, and a number
                        // in `DocumentMeta` is not a node either. A §19.3 upgrade patch is
                        // entirely made of node ops *plus* these, so "this patch touches no
                        // nodes" and "this patch changes nothing" are not the same answer.
                    }
                }
            }
            return touched
        }
}