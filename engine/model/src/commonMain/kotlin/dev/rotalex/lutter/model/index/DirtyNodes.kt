package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.ids.NodeId

/**
 * §6.2:537's dirty set: the node ids an edit makes re-analyse — the ids the edits name, closed
 * over the ancestor chains [DocumentIndex] computes.
 *
 * ### Why the type lives here, and why the patch half arrives as data
 *
 * §6.2 forms the set from two halves this repository owns in different modules: the ids a
 * patch's ops name (`Patch.touchedNodes`, §26.2:1919) and the ancestor closure. The set's
 * consumer is `:engine:analysis`, which §23.2 permits to see only `:engine:model` and
 * `:engine:schema` — never `:engine:editing`, where the patch lives — and `:engine:model` is
 * the root of the graph and may depend on nothing at all. So the closure is computed here,
 * beside the index it consults, and the patch half arrives as plain [NodeId]s plus one signal:
 * whatever caller can see both modules folds the patch and hands the answer over — the named
 * ids, the placements the edits vacate, and the record signal. No module grows an edge the
 * matrix refuses, and the type's inputs stay the two the section names.
 *
 * ### The half the named ids cannot say: record edits
 *
 * `touchedNodes` contributes nothing for the three ops that name no node — the record edit, the
 * theme selection and the component-version note (the three empty arms of `touchedNodes` in
 * `patch/Patch.kt`). A record edit is real and can reach every node all the same: a theme
 * declaration or the theme selection changes the defaults and tokens each node resolves
 * through (§14.2), and a component declaration is the spec every instance of it resolves
 * against. One op variant carries all the record kinds, so nothing on the patch side can say
 * *which* record moved, and the signal `of` takes for it is therefore conservative:
 * `recordsAffectAllNodes` marks **every** node dirty, which for re-analysis reads as "reuse
 * nothing". A caller able to see the patch's `RecordRef` who wants a narrower rule owes the
 * seam a widening first, not a silently smaller set.
 */
public class DirtyNodes private constructor(private val ids: Set<NodeId>?) {

    /** Whether [contains] answers true for every node: everything dirty, nothing reusable. */
    public val isEverything: Boolean get() = ids == null

    /**
     * Whether [id] has to be re-analysed.
     *
     * The total set contains every id, because "does this edit reach that node?" has one answer
     * everywhere when the edit is a record one: yes.
     */
    public operator fun contains(id: NodeId): Boolean = ids == null || id in ids

    public companion object {

        /**
         * The total set: every node dirty, no subtree reusable at reference identity.
         *
         * The record edit's answer, nameable without a document and therefore a constant.
         */
        public val ALL: DirtyNodes = DirtyNodes(null)

        /**
         * §6.2:537's set for one batch of edits: [touched] closed over its ancestors.
         *
         * [index] is built over the document **as the edits left it** — the chains that matter
         * are the ones the re-analysis will walk — and [touched] is the ids the edits name, what
         * `touchedNodes` answers, **plus the placements the edits vacate**: a removal's captured
         * parent and a move's `from` parent. The index cannot supply those on its own, because
         * the parent an edit removed a child from is on no touched id's chain in the document as
         * the edits left it — the removed id itself is gone and `ancestorsOf` rightly skips it —
         * yet that parent's slot list is exactly what changed. The caller that can see the patch
         * folds both halves into one set; the factory cannot, because it sees no patch.
         *
         * Ids the table does not hold contribute nothing, by `ancestorsOf`'s own rule: a node
         * the document no longer holds has nothing left to re-analyse. The touched ids that do
         * remain are in their own set — `pathTo` ends at the node, so a changed node is the
         * first thing re-analysed.
         *
         * [recordsAffectAllNodes] is the record half the named ids cannot express; the class
         * KDoc carries the whole argument. `true` returns [ALL] without consulting [touched] or
         * [index].
         */
        public fun of(
            touched: Set<NodeId>,
            index: DocumentIndex,
            recordsAffectAllNodes: Boolean = false,
        ): DirtyNodes = if (recordsAffectAllNodes) ALL else DirtyNodes(index.ancestorsOf(touched))
    }
}
