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
            out[id] = scopes
        }
        return out
    }
}
