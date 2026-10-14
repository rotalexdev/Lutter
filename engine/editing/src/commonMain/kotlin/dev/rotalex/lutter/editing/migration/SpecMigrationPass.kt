package dev.rotalex.lutter.editing.migration

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.schema.migration.SpecMigration
import dev.rotalex.lutter.schema.migration.SpecMigrationRegistry

/**
 * Component contract migrations, lifted into one reviewable patch (§19.3, D9).
 *
 * ### What this is for, and the one sentence the whole design turns on
 *
 * §19.3: *"Executed by a `SpecMigrationPass` after decoding, against the document's recorded
 * `componentVersions`. Each pass produces a patch, so the result is reviewable in diff form and
 * can be undone by the editor as a single 'upgrade' transaction."*
 *
 * That last clause is what fixes the shape. **The output is a [Patch] and not a rewritten
 * [UiDocument].** A document that silently changed shape is neither: there is nothing to read in
 * a diff because there was never a diff, and there is nothing for the editor's undo stack to
 * hold, so the upgrade cannot be taken back by the person who did not ask for it. Producing the
 * patch and letting [PatchApplier] apply it keeps the single-writer rule [PatchApplier]'s KDoc
 * states — this file produces ops and never a document.
 *
 * ### Which types are walked, and why the map rather than the node table
 *
 * **The recorded keys of `DocumentMeta.componentVersions`, and nothing else.** The map is what
 * §19.3 says the pass runs "against", and it is the only place a document says what it believes
 * about its own contracts. A node whose type the document never recorded is left completely
 * alone — there is no `from` to plan from, and inferring one would mean guessing a component
 * author's version out of thin air. That is D4's rule arriving from the other side: unknown is
 * preserved, here because it is *unrecorded* rather than because it is unrecognised.
 *
 * ### How the chain is driven, and the op order that falls out of it
 *
 * [SpecMigrationRegistry.plan] is per [ComponentType], so a document with several components at
 * several versions is walked one type at a time, in **ascending [ComponentType]** order. Within
 * a type, the nodes are walked in **ascending `NodeId`** order — which is
 * [dev.rotalex.lutter.model.doc.NodeTable.ids]'s own contract, not a sort re-derived here — and
 * within a node the chain runs **step by step, v1 to v3**, with each step's ops emitted before
 * the next step's.
 *
 * The whole sequence is: **component type, then node, then chain step, then the record.**
 *
 *  * **Type first**, because types are the outermost unit of meaning here. A reviewer reading
 *    the patch asks "what happened to `m3.Text`?" before they ask "what happened to `n_4`?", and
 *    grouping by type is what makes that question answerable by reading.
 *  * **Node before step**, because §19.3's steps are per node: `m3.Text` v1 to v2 is a rename
 *    applied to every `m3.Text` row, and a patch that interleaved the two steps across all the
 *    rows would make "one node crossed two versions" impossible to see.
 *  * **The record last, and this one is a dependency rather than a preference.** A document whose
 *    `componentVersions` says `m3.Text: 3` while its rows still speak v1 is a lie that the next
 *    load acts on — it reads "already current" and migrates nothing. Writing the record last
 *    means every op before it has already put the nodes where the record will then describe
 *    them, and it is the same reason [dev.rotalex.lutter.editing.diff.DocumentDiff] emits the
 *    theme selection after the records it can name.
 *
 * **Only one ordering here is actually load-bearing**, and it is worth saying which, because
 * "the ops can see each other's effects" is easy to over-apply. The record-last boundary is the
 * only one with a correctness argument behind it. Across types and across nodes nothing can
 * interfere: two components never write a row the other wrote, two rows are two rows, and §6.2's
 * "ids are never reused" means two components cannot even claim the same one. That is why the
 * rest is chosen for readability and determinism and nothing more, and why the same patch on
 * another engine's target is the same list in the same order — §6.2's determinism is a claim
 * about bytes, and a map walked in hash order would break it here exactly as it would in
 * `DocumentDiff`.
 *
 * ### Where `NodePatchScope` is, and where it deliberately is not
 *
 * PLAN §19.3's sketch writes `apply: (NodePatchScope) -> Unit`. The merged
 * [dev.rotalex.lutter.schema.migration.SpecMigration] writes `apply: (Node) -> Node` instead,
 * and **the merged type is the one that is right**, for three reasons that are one reason:
 *
 *  * **A migration that can only record edits cannot be *run* without one.** `(Node) -> Node` is
 *    total and testable in a single line — `assertEquals(expected, step.apply(node))` — and §19.2
 *    requires migrations to be pure and deterministic, which a callback that accumulates ops
 *    into a mutable scope is not, however carefully it is written.
 *  * **The pure form is what makes the diff meaningful.** [plan] gets two nodes and can compare
 *    them, and the comparison *is* the patch. A scope would have to be handed the ops one at a
 *    time by the migration itself, which means the migration author writes the field-by-field
 *    enumeration that belongs in one place, once, for every component contract that ever changes.
 *  * **A released migration is immutable** (§19.2), so the type cannot be changed to fix a
 *    mistake without breaking every document already migrated by the old shape.
 *
 * So [NodePatchScope] exists — its KDoc says what it is — but as **this file's** lift from a
 * pure `(Node) -> Node` to a list of ops, not as a migration's parameter. That is what
 * `SpecMigration`'s own KDoc promises: *"the editing pass lifts it into a patch, which is where
 * `NodePatchScope` will live rather than here."*
 *
 * ### Refusals, and which of them are this file's
 *
 * An **uncovered gap throws**, from [SpecMigrationRegistry.plan]'s own
 * [NoSuchElementException], and a **backwards gap throws** from its own
 * [IllegalArgumentException]. Neither is re-wrapped and neither is turned into a value: a
 * document that would be half-migrated is a document that renders as something its author never
 * wrote, and §19.3 has no "inspect" mode to degrade into. Nothing has been applied when it is
 * thrown, because this file applies nothing ever — [plan] is a pure function that returns ops.
 *
 * The one refusal **this** file adds is narrower than both: a migration whose result changes a
 * node's component type. [PatchOp] has no op for `Node.type`, so such a change has no
 * representation, and silently dropping it would leave a row whose body was migrated for one
 * component and whose type says another. See [NodePatchScope.record] for where that is caught.
 */
public object SpecMigrationPass {

    /**
     * The patch that carries [document] from its recorded contract versions to [targets], or
     * the empty patch when it is already there.
     *
     * ### The empty patch is the answer, not an absence of one
     *
     * A document that is current produces [Patch] `[]`, and [PatchApplier] applies it to
     * nothing. That is §26.2's own position on an empty patch — a legal patch, not a degenerate
     * one — and it is what makes this function total: a loader can call it on every document it
     * opens without first asking whether any of them need it, and an editor can take the result
     * to the undo stack only when it has ops, which is the rule `DocumentController.publish`
     * already enforces. **It is also what makes the second call free**: a document whose
     * `componentVersions` were moved by the first call plans to nothing, so running the pass
     * twice cannot double-apply a migration even if a step were not idempotent.
     *
     * ### What [targets] is and why it is a parameter rather than the schema
     *
     * §19.3 says these migrations *"may use the schema"*, and §19.1 makes the current contract
     * version the **component author's** to declare. So this function is told where each
     * component is now rather than asking a registry that has no opinion: [SpecMigrationRegistry]
     * knows the *steps* it holds and derives [SpecMigrationRegistry.latest] from them, but "the
     * version this engine ships" is a fact about the builtins and the plugins, and a type
     * whose contract has never changed has no steps at all and so no `latest` to return.
     *
     * A caller in the engine builds it from what it already holds — one line over
     * `SchemaView.components.all()`, reading [dev.rotalex.lutter.schema.component.ComponentSpec.version]
     * — and a caller with no schema at all can ask the registry. **A recorded type that is
     * absent from [targets] is left exactly as recorded**: the caller has said nothing about it,
     * and inventing a version for a component the document named is a claim the caller did not
     * make.
     *
     * @param document the document to migrate, as decoded. Nothing here reads it structurally
     *   beyond walking its table: §6.2's invariants are §17.1's to report and this pass is not
     *   a pass over documents, it is a producer of ops.
     * @param registry the steps to chain. Consulted once per recorded type, never cached and
     *   never asked about a type the document does not name.
     * @param targets the version each component is now at. See above for the contract.
     * @return the ops in the order documented on this object. Empty when there is nothing to do.
     * @throws NoSuchElementException if the registry has no step for some version of the gap,
     *   naming the type and the version it stopped at. This is the registry's own refusal,
     *   deliberately not caught here.
     * @throws IllegalArgumentException if a recorded type is *newer* than [targets] says. A
     *   migration is not a downgrade, and an engine that met a document written against a newer
     *   contract has no honest patch to offer for it.
     */
    public fun plan(
        document: UiDocument,
        registry: SpecMigrationRegistry,
        targets: Map<ComponentType, Int>,
    ): Patch {
        val ops: MutableList<PatchOp> = mutableListOf()
        val recorded: Map<ComponentType, Int> = document.meta.componentVersions

        for (type in recorded.keys.sortedBy { it.value }) {
            val target: Int = targets[type] ?: continue
            val steps = registry.plan(type, recorded.getValue(type), target)
            if (steps.isEmpty()) continue

            ops += nodeOps(document, type, steps)
        }

        // Last, and for the reason the class KDoc gives: the record must not claim a version
        // before the rows speak it. See also `DocumentDiff.compute`'s ordering rule, which
        // reaches the same place from the other direction.
        ops += versionOps(recorded, targets)

        return Patch(ops)
    }

    /**
     * Every node of [type]'s chain, as ops.
     *
     * **The `type` filter is the load-bearing line in this file.** [SpecMigration.apply] is a
     * bare `(Node) -> Node` and knows nothing about which component a row is an instance of —
     * [SpecMigrationRegistry.migrate] relies on its caller to hand it a node of the right type,
     * and says so in one word: *"the node's type selects the chain."* A `m3.Text` step that
     * renames `label` to `text` applied to a `core.Column` row would rename whatever the column
     * happened to have put in a property called `label`, which is a migration of a document that
     * does not exist. So the walk compares [Node.type] against the type being planned and skips
     * everything else, and §7.4's ban on branching on a component type is not what keeps that
     * honest — this is a comparison, not a dispatch, and a registry would have been the wrong
     * tool for a question with a boolean answer.
     *
     * The chain is threaded through the rows rather than applied to the document as it stands:
     * step two of a v1-to-v3 chain has to see the row step one produced, because the two steps
     * are written against consecutive versions of the same component and the second may read a
     * property the first one wrote. That is why this takes a [SpecMigrationRegistry.plan] result
     * rather than a `(node, version)` pair, and why `current` moves inside the node loop instead
     * of being re-read from [document] each time.
     *
     * A node the migration leaves alone contributes nothing at all, which is the common case and
     * the reason [NodePatchScope] compares before it writes rather than emitting a field op per
     * field. A step that changes a node by nothing still costs one `apply` call and no ops.
     */
    private fun nodeOps(
        document: UiDocument,
        type: ComponentType,
        steps: List<SpecMigration>,
    ): List<PatchOp> {
        val scope = NodePatchScope()
        for (id in document.nodes.ids()) {
            val row: Node = document.nodes.require(id)
            if (row.type != type) continue

            var current: Node = row
            for (step in steps) {
                val next: Node = step.apply(current)
                scope.record(step, current, next)
                current = next
            }
        }
        return scope.ops()
    }

    /**
     * One [PatchOp.SetComponentVersion] per recorded type whose version moves, ascending.
     *
     * **Only keys the document already holds**, for the reason [plan]'s contract gives: an entry
     * this writes for a type the document never recorded is a claim about the document that the
     * document did not make, and §19.1's record exists to say what the document *was* written
     * against, not what it might come to use.
     *
     * A type whose target already equals its recorded version produces nothing, which is what
     * makes this group agree with the node group above: a type the pass did not migrate has no
     * version to move either, and a mismatch between the two groups would be a patch that
     * rewrites rows while claiming nothing changed.
     */
    private fun versionOps(
        recorded: Map<ComponentType, Int>,
        targets: Map<ComponentType, Int>,
    ): List<PatchOp> {
        val ops: MutableList<PatchOp> = mutableListOf()

        for (type in recorded.keys.sortedBy { it.value }) {
            val previous: Int = recorded.getValue(type)
            val replacement: Int = targets[type] ?: continue
            if (previous == replacement) continue
            ops += PatchOp.SetComponentVersion(type, old = previous, new = replacement)
        }
        return ops
    }
}

/**
 * The lift from a pure node transform to the ops that describe it.
 *
 * PLAN §19.3 names this type in the signature of a migration and the merged
 * [dev.rotalex.lutter.schema.migration.SpecMigration] moved it here instead; this file's KDoc
 * says why at length. In short: **it is not what a migration is given, it is what a migration's
 * result is turned into.**
 *
 * ### Why a collector rather than a function
 *
 * Because the answer is a *list* and the comparison happens once per node, per step, and a
 * chain is several nodes deep. A function returning `List<PatchOp>` would be the same work with
 * one extra allocation per node, and the accumulating form lets `SpecMigrationPass` hold one
 * scope across a whole component type's rows instead of concatenating per-row lists.
 *
 * It is `internal` and not public on purpose: a plugin author writing a contract migration
 * writes `(Node) -> Node` and never holds one of these. The type is named in
 * [SpecMigration]'s KDoc, and that is how a reader reaches it — as the answer to a question the
 * schema asks, rather than as an extension point nobody should be using.
 *
 * ### The one thing it refuses
 *
 * **A migration that changes [Node.type].** There is no [PatchOp] for a component type — see
 * [PatchOp]'s KDoc — so such a change has no representation at all, and a scope that dropped it
 * would leave a row whose body was migrated for one component and whose type still says
 * another, which is a document no §17 pass can describe. Throwing names the node and both types,
 * and it is thrown while the patch is being *built* rather than at apply time, so nothing has
 * been applied when it happens.
 */
internal class NodePatchScope {

    /** The ops recorded so far, in the order they were recorded and will apply. */
    private val collected: MutableList<PatchOp> = mutableListOf()

    /**
     * The ops recorded so far.
     *
     * A copy, not the list itself: the caller concatenates this into another list and the scope
     * is still holding its own, and handing out the mutable backing would make "the ops so far"
     * and "the ops this scope will go on to add" the same object.
     */
    fun ops(): List<PatchOp> = collected.toList()

    /**
     * Records the ops that carry [previous] to [next], which are two versions of one row.
     *
     * [step] is named only so the refusal below can quote the migration that misbehaved; a
     * reader looking at a thrown `IllegalStateException` wants the step's `from` and `to`, not
     * an address in a list.
     *
     * ### The order, and why it is the order
     *
     * **Name, then props, then modifiers, then handlers** — the same order
     * `DocumentDiff.editedFields` uses, for the same reason: §5.3 annotates `props` "sorted by
     * key when written" while annotating `modifiers` "order is semantically significant", and a
     * list a person reads has to make that asymmetry visible rather than let the iteration order
     * of two maps decide it. Keys are sorted on their string form because
     * [dev.rotalex.lutter.model.ids.PropertyKey] and [dev.rotalex.lutter.model.ids.EventKey] are
     * value classes over `String` with no natural ordering.
     *
     * **A row whose `slots` changed is rewritten whole and nothing else is emitted for it.**
     * §5.6 puts a node's children in the *parent's* row, so a slot rename — which §19.1 lists
     * among the things a contract migration changes — is a change to that one row's own slot
     * map and not a change to any child. Emitting per-field ops alongside it would be a second
     * description of an edit the rewrite already carries, and `PatchApplier` would apply both.
     * The pair is the same `RemoveSubtree` then `PutNodes` a diff emits, and the order is the
     * same for the same reason: `PutNodes` refuses an id the table already holds.
     *
     * **A row that did not change contributes nothing.** This is not an optimisation; it is what
     * makes "one step, many nodes" produce a patch a person can read. A v2 step that renames a
     * property only four of two hundred rows declare should produce four renames and not two
     * hundred pairs of ops that set a key to the value it already holds.
     */
    fun record(step: SpecMigration, previous: Node, next: Node) {
        check(previous.type == next.type) {
            "the ${previous.type} migration from v${step.from} to v${step.to} changed " +
                "'${previous.id}' from a ${previous.type} node into a ${next.type} node, and " +
                "PatchOp has no op for a component type"
        }

        if (previous.slots != next.slots) {
            collected += PatchOp.RemoveSubtree(previous.id, RemovedSubtree.unplaced(listOf(previous)))
            collected += PatchOp.PutNodes(listOf(next))
            return
        }

        if (previous.name != next.name) {
            collected += PatchOp.SetNodeName(previous.id, previous.name, next.name)
        }

        for (key in (previous.props.keys + next.props.keys).sortedBy { it.value }) {
            val from = previous.props[key]
            val to = next.props[key]
            if (from != to) collected += PatchOp.SetProp(previous.id, key, from, to)
        }

        if (previous.modifiers != next.modifiers) {
            collected += PatchOp.SetModifiers(previous.id, previous.modifiers, next.modifiers)
        }

        for (key in (previous.events.keys + next.events.keys).sortedBy { it.value }) {
            val from = previous.events[key]
            val to = next.events[key]
            if (from != to) collected += PatchOp.SetHandler(previous.id, key, from, to)
        }
    }
}