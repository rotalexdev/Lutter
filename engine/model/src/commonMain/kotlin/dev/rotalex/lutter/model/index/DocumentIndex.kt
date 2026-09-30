@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator` on [NodeOwner]. It has
// to be a file annotation and not a per-use `@OptIn` because Kotlin requires file annotations
// to precede the `package` declaration, and putting one after the imports is a syntax error
// rather than a warning.

package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SlotName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The derived views of a document's node table: who is a node's parent, what owns it, and how
 * to walk out from it.
 *
 * PLAN §5.6 declares four queries and calls these structures "never persisted, lazily built
 * and cached by document identity in the editing layer". The caching is the editing layer's
 * job; what is built here is the index itself, once, in the constructor — every query below
 * is a lookup.
 *
 * ### It is not serializable, and it is not a data class
 *
 * §35 lists "persisting derived data" as an anti-pattern and §5.6's own preamble says these
 * structures are never persisted. Neither this class nor [ParentRef] carries `@Serializable`,
 * and the ABI dump has to show that. `DocumentIndex` is not a `data class` either: its
 * identity is the document it was built from, not its three lookup tables, and a generated
 * `copy` would hand out an index that is a different object from the document a caller
 * believes it is looking at.
 *
 * ### A malformed document must not hang the index
 *
 * §6.2's invariants — one parent per node, no cycles, every node reachable from exactly one
 * root — are §17.1's structural pass to report, and the model checks nothing it can only see
 * with a schema. So a document with a cycle in it decodes, and an index that walked it
 * naively would never return. Every walk below carries a visited set and stops at the first
 * repeat. Which member of the document is wrong is §17.1's answer to give, with a
 * `nodeId` attached; stopping the walk is this class's only obligation.
 *
 * ### [pathTo] includes the node, and [ancestorsOf] is the reason
 *
 * §6.2:514 forms the *dirty set* for incremental re-analysis from `Patch.touchedNodes` and
 * this index, and §26.2 already types `touchedNodes` as a [Set]. A node that changed is in
 * its own dirty set — it is the first thing that has to be re-analysed — so [pathTo] ends at
 * the node rather than stopping one short, and [ancestorsOf] is the union over a batch rather
 * than a call site folding [pathTo] per id and deduplicating a [List] into the membership it
 * wanted.
 *
 * ### What the orderings are, and what they are not
 *
 * The parent index is read in ascending parent id and in each parent's own slot order, so it
 * is deterministic for a given document's bytes; a node two parents list is a §6.2 violation
 * and the *first* one read wins, which is the same rule [claim] applies to ownership. The
 * owner index is pages first in ascending page id, then components in ascending component id,
 * so a node two roots reach reads as the page's. Both choices only matter for a document that
 * is already invalid.
 */
public class DocumentIndex(private val document: UiDocument) {

    private val parents: Map<NodeId, ParentRef> = buildParents()

    private val owners: Map<NodeId, NodeOwner> = buildOwners()

    /**
     * The parent that lists [id] in one of its slots, and the slot and position it lists it
     * at, or `null` for a root or an id no node in the table holds.
     */
    public fun parentOf(id: NodeId): ParentRef? = parents[id]

    /**
     * The page or component declaration whose root reaches [id], or `null` for a node no root
     * reaches.
     */
    public fun ownerOf(id: NodeId): NodeOwner? = owners[id]

    /** The root-to-[id] path, [id] included. Empty of ancestors for a root. See the KDoc. */
    public fun pathTo(id: NodeId): List<NodeId> {
        val path = mutableListOf(id)
        val walked = mutableSetOf<NodeId>()
        var current = id
        while (true) {
            val parent = parents[current]?.parentId ?: break
            if (!walked.add(parent)) break
            path += parent
            current = parent
        }
        return path.reversed()
    }

    /**
     * Every node that has to be re-analysed because one of [ids] changed: the nodes
     * themselves plus all their ancestors, deduplicated.
     *
     * §6.2:514 names this as one of the two halves of the dirty set and §26.2's
     * `touchedNodes` is the other. A batch in and a [Set] out, because the caller wants
     * membership and folding [pathTo] per id gives it a [List] with duplicates.
     */
    public fun ancestorsOf(ids: Collection<NodeId>): Set<NodeId> {
        val dirty = mutableSetOf<NodeId>()
        for (id in ids) dirty += pathTo(id)
        return dirty
    }

    /**
     * Every node below [id], depth first, [id] itself excluded.
     *
     * Eagerly collected and returned as a [Sequence], so the walk can be written with a
     * visited set — a lazy sequence over a growing queue is the shape that turns a cycle in
     * the document into a walk that never ends.
     */
    public fun descendants(id: NodeId): Sequence<NodeId> {
        val start = document.nodes[id] ?: return emptySequence()
        val seen = mutableSetOf(id)
        val found = mutableListOf<NodeId>()
        val pending = mutableListOf<NodeId>()

        enqueueChildren(start, seen, pending)
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            found += current
            val node = document.nodes[current] ?: continue
            enqueueChildren(node, seen, pending)
        }
        return found.asSequence()
    }

    private fun buildParents(): Map<NodeId, ParentRef> {
        val found = mutableMapOf<NodeId, ParentRef>()
        for (parentId in document.nodes.ids()) {
            val parent = document.nodes[parentId] ?: continue
            for ((slot, children) in parent.slots) {
                for (index in children.indices) {
                    val child = children[index]
                    if (!found.containsKey(child)) found[child] = ParentRef(parentId, slot, index)
                }
            }
        }
        return found
    }

    private fun buildOwners(): Map<NodeId, NodeOwner> {
        val found = mutableMapOf<NodeId, NodeOwner>()
        for (id in document.pages.keys.sortedBy { it.value }) {
            claim(found, document.pages.getValue(id).root, NodeOwner.Page(id))
        }
        for (id in document.components.keys.sortedBy { it.value }) {
            claim(found, document.components.getValue(id).root, NodeOwner.Component(id))
        }
        return found
    }

    /**
     * Marks everything reachable from [root] as owned by [owner], skipping what is already
     * claimed.
     *
     * The skip is the cycle guard and the ownership rule at once: a node two roots reach
     * keeps the first owner, and a cycle in the document stops at the node that closes it.
     */
    private fun claim(found: MutableMap<NodeId, NodeOwner>, root: NodeId, owner: NodeOwner) {
        val pending = mutableListOf(root)
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.lastIndex)
            if (found.containsKey(id)) continue
            found[id] = owner
            val node = document.nodes[id] ?: continue
            for (children in node.slots.values) pending += children
        }
    }

    private fun enqueueChildren(
        node: Node,
        seen: MutableSet<NodeId>,
        pending: MutableList<NodeId>,
    ) {
        for (children in node.slots.values) {
            for (child in children) if (seen.add(child)) pending += child
        }
    }
}

/**
 * Where a node sits in its parent's slots: the parent, the slot, and the position in it.
 *
 * PLAN §5.6:444 writes this out because `parentOf`'s comment — *(parentId, slot, index)* — was
 * the whole of its specification. All three are needed: the parent alone is §5.6's reason a
 * parent index has to exist, and the slot and index are what an editor needs to know *where*
 * a node is rather than merely that it is somewhere. An `Int` index and not a sibling id
 * because the sibling can move and the index is what a removal means.
 *
 * Not `@Serializable`: §5.6's preamble says the structures in that section are never
 * persisted, and §35 lists persisting derived data as an anti-pattern. It is plain data with
 * no hierarchy, so there is no tag to state.
 */
public data class ParentRef(

    /** The node that lists the child. */
    public val parentId: NodeId,

    /** The slot of that node the child is listed in. */
    public val slot: SlotName,

    /** The child's position in that slot, counting from zero. */
    public val index: Int,
)

/**
 * What owns a node: the page or the document component whose root reaches it.
 *
 * The two cases are §5.6:452's, in the shape §10.1 already persists for a closed set of ids,
 * and every case spells its tag: a hierarchy that is `@Serializable` without tags falls back
 * to class names the first time something does serialize it, and `RefTarget` shows the cost
 * of stating them is one line each.
 *
 * `@Serializable` on something the section says is never persisted looks like a contradiction
 * and is not one: the guarantee being bought is that a hierarchy with a discriminator has a
 * *decided* discriminator, so anything that later does persist this writes `{"type":"page"}`
 * rather than a class name nobody signed up for.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface NodeOwner {

    /** A screen. */
    @Serializable
    @SerialName("page")
    public data class Page(val id: PageId) : NodeOwner

    /** A document-defined reusable component. */
    @Serializable
    @SerialName("component")
    public data class Component(val id: ComponentDeclId) : NodeOwner
}
