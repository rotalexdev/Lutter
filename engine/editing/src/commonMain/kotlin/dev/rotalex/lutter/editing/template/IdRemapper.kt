package dev.rotalex.lutter.editing.template

import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.NodeId

/**
 * Mints the ids a copy of a subtree has to carry before it can be inserted.
 *
 * PLAN §25.4's clipboard row names the two halves — *"NodeTemplate.capture(doc, ids) /
 * InsertNode(...) with IdRemapper"* — and §6.2's Stable IDs row says why the second half is
 * not optional: *"Ids are assigned once, never reused, never derived from position or name.
 * Duplicate/paste generates new ids (IdRemapper). Ids are globally unique in the document
 * (across pages/components)."*
 *
 * ### The rule this type exists to keep, and why it is a type
 *
 * A copied row that kept its id would not be a copy. §5.6's normalized table is a
 * `Map<NodeId, Node>` and `NodeTable.with` answers a collision by **replacing** the row that
 * is there, so pasting a template whose ids are still live does not fail — it deletes the
 * original and replaces it with a copy, which is the one failure no existence check anywhere
 * in the engine reports. Everything else this class does is in service of not being that.
 *
 * ### Global, not local, and that is the part that is easy to get wrong
 *
 * §6.2 says ids are unique **across pages and components**, not within one subtree. So the set
 * this type will not reuse is every id the whole [document] holds, not the ids of the subtree
 * being copied and not the ids of the page the copy is being pasted into. Two documents that
 * each think they own `n_7` are two documents; one document with a page and a component both
 * holding `n_7` is a broken one, and a remapper that only knew about the subtree being copied
 * would have no way to notice.
 *
 * ### One remapper, several pastes
 *
 * The state is the point, not an implementation detail: a **bulk paste remaps twice with the
 * same instance** and is guaranteed two disjoint sets of ids. A remapper per paste would leave
 * nothing enforcing that, and a paste that renamed a subtree would be free to hand out an id
 * the previous one already took. §26.3's transaction rule is what makes the gesture undoable,
 * and the gesture is both pastes.
 *
 * ### What it trusts, and what it checks
 *
 * [IdGenerator.nextNodeId] promises an id no node in the document is using, and that promise is
 * **checked here rather than assumed** — see [fresh] for what happens when it is broken. The
 * generator is injected for the reason [dev.rotalex.lutter.editing.command.CommandPlanner.plan]
 * gives: a generator invented here would make planning the same edit twice produce two
 * different documents, which is the determinism claim §28.3 makes about scripted sessions.
 *
 * @see NodeTemplate for the rows this type rewrites, and what it takes to have been captured.
 */
public class IdRemapper(

    /**
     * The document the copies are destined for.
     *
     * Every id it holds is reserved on construction, which is the "whole document, not the
     * subtree" rule above. It is read once, here: this class never sees the document again,
     * so a remapper made against one document and used against another can only be as wrong as
     * the caller, and the id it refuses to reuse will say so.
     */
    document: UiDocument,

    /** Where the ids come from. Injected, and see the class KDoc for why. */
    private val ids: IdGenerator,
) {

    /**
     * Every id that must not be handed out again: the document's, plus what this remapper has
     * already returned.
     *
     * One set rather than two, because they are one rule — *never twice* — and two sets would
     * make a caller reason about which of them applies.
     */
    private val unavailable: MutableSet<NodeId> = document.nodes.ids().toMutableSet()

    /**
     * An id no node in the document and no earlier call to this remapper is using.
     *
     * ### When the generator breaks its contract
     *
     * [IdGenerator] promises a fresh id, and a `SequentialIdGenerator` restarted against a
     * document that already holds `n_1` breaks it. The promise is checked rather than trusted
     * because the consequence of trusting it is not a wrong answer but a wrong *document*.
     *
     * So an id this remapper has already handed out — or one the document holds — is asked
     * again, up to [MAX_ATTEMPTS] more times. After that the id is returned as it stands,
     * **without an exception and without hanging**: a caller that can turn a repeated id into a
     * report is better served by one that keeps going, and
     * [dev.rotalex.lutter.editing.command.Command.DuplicateNode]'s planner is exactly that
     * caller — it checks what it was given and refuses with `edit.duplicate_id`, which is a
     * value an editor can show. A remapper that threw here would put an exception on the path
     * between "the user pressed duplicate" and the message explaining why it did not work.
     */
    public fun fresh(): NodeId {
        var candidate: NodeId = ids.nextNodeId()
        var attempts: Int = 0
        while (candidate in unavailable && attempts < MAX_ATTEMPTS) {
            candidate = ids.nextNodeId()
            attempts++
        }
        unavailable += candidate
        return candidate
    }

    /**
     * [nodes] rewritten under fresh ids: every row's own id, and every child reference between
     * them, in one pass.
     *
     * Both halves are needed and neither is enough alone. Renaming the rows without rewriting
     * the references gives a copy whose children point at the *original's* rows, so a paste
     * would move nothing and the copied subtree would vanish into orphans. Rewriting the
     * references without renaming the rows gives two rows with one id, which `NodeTable.with`
     * resolves by keeping the last. The mapping is therefore built once and applied to both,
     * and the two lists are paired by position — [nodes] is root first and its own ids are
     * unique in anything [NodeTemplate.capture] or a subtree walk produced.
     *
     * **A reference the rows do not resolve is left exactly as it is.** That is not a branch
     * this method takes on a well-formed document — a captured template closes over itself and
     * a subtree walk returns whole subtrees — but a document with a dangling child reference
     * yields rows naming something outside them, and inventing an id for it would create a
     * node nobody asked for. Leaving it points the copy at the same gap the original has,
     * which §17.1's structural pass reports on the original too.
     *
     * @return a template whose first row is [nodes]' first row under a new id, ready for
     *   [dev.rotalex.lutter.editing.command.Command.InsertNode].
     */
    public fun remap(nodes: List<Node>): NodeTemplate {
        val minted: List<NodeId> = List(nodes.size) { fresh() }
        val mapping: Map<NodeId, NodeId> = nodes.map { it.id }.zip(minted).toMap()

        return NodeTemplate(
            nodes.map { node ->
                node.copy(
                    id = mapping.getValue(node.id),
                    slots = node.slots.mapValues { (_, children) -> children.map { mapping[it] ?: it } },
                )
            },
        )
    }

    private companion object {

        /**
         * How many more ids to ask for before giving up on a fresh one.
         *
         * Enough that a generator which repeats itself once is invisible, and finite so that
         * one which repeats forever is a refusal downstream rather than a hang here. See
         * [fresh].
         */
        private const val MAX_ATTEMPTS: Int = 8
    }
}