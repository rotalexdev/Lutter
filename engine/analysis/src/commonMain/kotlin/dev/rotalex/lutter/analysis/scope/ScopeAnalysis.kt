package dev.rotalex.lutter.analysis.scope

import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.model.index.DocumentIndex

/**
 * The scope set at each node: the union of `provides` on the slots above it.
 *
 * Unknown ancestors contribute nothing — their slots are already a diagnostic elsewhere.
 */
internal object ScopeAnalysis {
    /**
     * @param sharedIndex an index of the same document, when the caller already built one and
     *   several passes are about to ask the same questions of it. The index is immutable, so
     *   sharing it changes no answer and saves one full walk of the document per caller that
     *   would otherwise build its own.
     */
    public fun computeScopes(
        document: UiDocument,
        specOf: (ComponentType) -> ComponentSpec?,
        sharedIndex: DocumentIndex? = null,
    ): Map<NodeId, Set<ScopeId>> {
        val index = sharedIndex ?: DocumentIndex(document)
        val out = LinkedHashMap<NodeId, Set<ScopeId>>()
        for (id in document.nodes.ids()) {
            out[id] = scopesAt(id, document, specOf, index)
        }
        return out
    }

    /**
     * [computeScopes] as a lookup that computes one node's set when it is first asked for.
     *
     * The answer is the same for every id the full map covers, and the memoized set is the
     * same instance every time it is asked for again. An id the table does not hold answers
     * with an empty set, matching what a caller reading the full map would get from a missing
     * key, and it is memoized like any other. The walk still climbs the ancestor chain per
     * node — a root DFS would answer differently for a node no root reaches, and those are
     * exactly the nodes the incremental path can still ask about.
     *
     * @param sharedIndex as in [computeScopes].
     */
    public fun lazyScopes(
        document: UiDocument,
        specOf: (ComponentType) -> ComponentSpec?,
        sharedIndex: DocumentIndex? = null,
    ): (NodeId) -> Set<ScopeId> {
        val index = sharedIndex ?: DocumentIndex(document)
        val cache = HashMap<NodeId, Set<ScopeId>>()
        return { id -> cache.getOrPut(id) { scopesAt(id, document, specOf, index) } }
    }

    /** One node's answer, by climbing its ancestor chain and collecting what each edge provides. */
    private fun scopesAt(
        id: NodeId,
        document: UiDocument,
        specOf: (ComponentType) -> ComponentSpec?,
        index: DocumentIndex,
    ): Set<ScopeId> {
        val scopes = linkedSetOf<ScopeId>()
        val seen = mutableSetOf(id)
        var current = index.parentOf(id)
        while (current != null && seen.add(current.parentId)) {
            val parent = document.nodes[current.parentId]
            val provided = parent?.let { specOf(it.type) }
                ?.slots?.firstOrNull { it.name == current.slot }?.provides
            if (provided != null) scopes += provided
            current = index.parentOf(current.parentId)
        }
        return scopes
    }
}
