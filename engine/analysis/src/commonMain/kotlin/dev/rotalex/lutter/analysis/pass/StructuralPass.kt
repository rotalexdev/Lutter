package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName

/**
 * Pass 1: the table invariants — roots exist, slots name live nodes, one parent, no cycles.
 *
 * Blocking: with a broken skeleton the schema and reference walks would report noise.
 */
internal class StructuralPass : AnalysisPass {
    override val name: String = "structural"
    override val blocking: Boolean = true

    override fun run(document: UiDocument): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        checkRoots(document, diags)
        val claims = collectClaims(document, diags)
        checkParents(claims, diags)
        val cyclic = checkCycles(document, diags)
        checkOrphans(document, cyclic, diags)
        return diags
    }

    private fun checkRoots(document: UiDocument, diags: MutableList<Diagnostic>) {
        val start = document.app.startPage
        if (start !in document.pages) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.StructMissingRoot, DiagnosticLocation(path = listOf("app", "startPage")),
                "App start page '$start' is not a declared page", mapOf("page" to start.value),
            )
        }
        for ((id, page) in document.pages.entries.sortedBy { it.key.value }) {
            if (page.root !in document.nodes) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.StructMissingRoot, DiagnosticLocation(pageId = id),
                    "Page '$id' names root '${page.root}' that is not in the node table",
                    mapOf("page" to id.value, "root" to page.root.value),
                )
            }
        }
        for ((id, decl) in document.components.entries.sortedBy { it.key.value }) {
            if (decl.root !in document.nodes) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.StructMissingRoot, DiagnosticLocation(componentDeclId = id),
                    "Component '$id' names root '${decl.root}' that is not in the node table",
                    mapOf("component" to id.value, "root" to decl.root.value),
                )
            }
        }
    }

    private fun collectClaims(
        document: UiDocument,
        diags: MutableList<Diagnostic>,
    ): Map<NodeId, List<Claim>> {
        val claims = mutableMapOf<NodeId, MutableList<Claim>>()
        for (parentId in document.nodes.ids()) {
            val parent = document.nodes[parentId] ?: continue
            for ((slot, children) in parent.slots) {
                for ((index, child) in children.withIndex()) {
                    if (child !in document.nodes) {
                        diags += Diagnostic(
                            Severity.Error, DiagnosticCodes.StructMissingNode,
                            DiagnosticLocation(nodeId = parentId, path = listOf(slot.value, index.toString())),
                            "Node '$parentId' slot '${slot.value}' names node '$child' that is not in the table",
                            mapOf("parent" to parentId.value, "slot" to slot.value, "node" to child.value),
                        )
                        continue
                    }
                    claims.getOrPut(child) { mutableListOf() } += Claim(parentId, slot, index)
                }
            }
        }
        return claims
    }

    private fun checkParents(claims: Map<NodeId, List<Claim>>, diags: MutableList<Diagnostic>) {
        for ((child, list) in claims.entries.sortedBy { it.key.value }) {
            if (list.size < 2) continue
            // Same slot twice is a repeated id; different slots or parents is a second parent.
            if (list.map { it.parent to it.slot }.toSet().size > 1) {
                val second = list[1]
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.StructMultipleParents,
                    DiagnosticLocation(nodeId = child, path = listOf(second.parent.value, second.slot.value)),
                    "Node '$child' is claimed by more than one parent",
                    mapOf("node" to child.value),
                )
            } else {
                val first = list[0]
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.StructDuplicateId,
                    DiagnosticLocation(nodeId = first.parent, path = listOf(first.slot.value, list[1].index.toString())),
                    "Node '${first.parent}' lists '$child' twice in slot '${first.slot.value}'",
                    mapOf("parent" to first.parent.value, "slot" to first.slot.value, "node" to child.value),
                )
            }
        }
    }

    private fun childrenOf(document: UiDocument, id: NodeId): List<NodeId> =
        document.nodes[id]?.slots?.values?.flatten() ?: emptyList()

    private fun checkCycles(document: UiDocument, diags: MutableList<Diagnostic>): Set<NodeId> {
        val cyclic = mutableSetOf<NodeId>()
        val done = mutableSetOf<NodeId>()
        val inPath = mutableSetOf<NodeId>()
        for (start in document.nodes.ids().toList()) {
            if (start in done) continue
            inPath += start
            val stack = mutableListOf(start to childrenOf(document, start).iterator())
            while (stack.isNotEmpty()) {
                val (node, edges) = stack.last()
                if (!edges.hasNext()) {
                    stack.removeAt(stack.lastIndex)
                    inPath -= node
                    done += node
                    continue
                }
                val child = edges.next()
                if (child !in document.nodes || child in done) continue
                if (child in inPath) {
                    cyclic += node
                    cyclic += child
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.StructCycle,
                        DiagnosticLocation(nodeId = node, path = listOf(child.value)),
                        "Slot edge '$node' to '$child' closes a cycle",
                        mapOf("node" to node.value, "target" to child.value),
                    )
                    continue
                }
                inPath += child
                stack += child to childrenOf(document, child).iterator()
            }
        }
        return cyclic
    }

    private fun checkOrphans(
        document: UiDocument,
        cyclic: Set<NodeId>,
        diags: MutableList<Diagnostic>,
    ) {
        val reachable = mutableSetOf<NodeId>()
        val pending = mutableListOf<NodeId>()
        for (id in document.pages.keys.sortedBy { it.value }) {
            document.pages.getValue(id).root.let { if (it in document.nodes) pending += it }
        }
        for (id in document.components.keys.sortedBy { it.value }) {
            document.components.getValue(id).root.let { if (it in document.nodes) pending += it }
        }
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.lastIndex)
            if (!reachable.add(id)) continue
            for (child in childrenOf(document, id)) if (child in document.nodes) pending += child
        }
        for (id in document.nodes.ids()) {
            if (id !in reachable && id !in cyclic) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.StructOrphan, DiagnosticLocation(nodeId = id),
                    "Node '$id' is reachable from no page or component root",
                    mapOf("node" to id.value),
                )
            }
        }
    }
}

/** One slot entry claiming a child: who lists it, where, and at which position. */
private data class Claim(val parent: NodeId, val slot: SlotName, val index: Int)
