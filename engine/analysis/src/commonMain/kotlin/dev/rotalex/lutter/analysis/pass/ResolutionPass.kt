package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedModifier
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.analysis.resolved.ResolvedToken
import dev.rotalex.lutter.analysis.resolved.resolveToken
import dev.rotalex.lutter.analysis.resolved.selectTheme
import dev.rotalex.lutter.analysis.scope.ScopeAnalysis
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * Pass 7: lowers a clean document — defaults applied, scopes computed, tokens resolved.
 *
 * Runs only when no pass reported an error, so every lookup below must hit; a miss is a
 * bug rather than a document fault.
 */
internal class ResolutionPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) {
    public fun run(document: UiDocument): ResolvedDocument {
        val theme = selectTheme(document)
        val scopes = ScopeAnalysis.computeScopes(document) { schema.components[it] }
        val index = LinkedHashMap<NodeId, ResolvedNode>()

        fun propOf(key: PropertyKey, actual: PropertyValue?, default: Value?, selected: ThemeDecl?): ResolvedProp? {
            val effective = actual ?: default?.let { PropertyValue.Const(it) } ?: return null
            val origin = if (actual != null) PropOrigin.Specified else PropOrigin.Default
            return ResolvedProp(key, effective, origin, tokenOf(effective, selected))
        }

        fun build(id: NodeId): ResolvedNode {
            index[id]?.let { return it }
            val node = checkNotNull(document.nodes[id]) { "Resolution reached node '$id' outside the table" }
            val spec = checkNotNull(schema.components[node.type]) { "Resolution reached unregistered type '${node.type}'" }
            val props = LinkedHashMap<PropertyKey, ResolvedProp>()
            for (declared in spec.properties.sortedBy { it.key.value }) {
                propOf(declared.key, node.props[declared.key], declared.default, theme)?.let { props[declared.key] = it }
            }
            val modifiers = node.modifiers.map { entry ->
                val params = checkNotNull(schema.modifiers[entry.type]) { "Resolution reached unregistered modifier '${entry.type}'" }.params
                val args = LinkedHashMap<PropertyKey, ResolvedProp>()
                for (param in params.sortedBy { it.key.value }) {
                    propOf(param.key, entry.args[param.key], param.default, theme)?.let { args[param.key] = it }
                }
                ResolvedModifier(entry.type, args)
            }
            val slots = LinkedHashMap<SlotName, List<ResolvedNode>>()
            for (slot in spec.slots) {
                slots[slot.name] = (node.slots[slot.name] ?: emptyList()).map(::build)
            }
            return ResolvedNode(node.id, node.type, props, modifiers, slots, scopes[id] ?: emptySet())
                .also { index[id] = it }
        }

        val pages = document.pages.entries.sortedBy { it.key.value }
            .associate { (id, page) -> id to ResolvedPage(id, page.name, page.route, build(page.root)) }
        val components = document.components.entries.sortedBy { it.key.value }
            .associate { (id, decl) -> id to build(decl.root) }
        return ResolvedDocument(pages, components, index, ResolvedTheme(theme))
    }
}

private fun tokenOf(actual: PropertyValue, theme: ThemeDecl?): ResolvedToken? {
    val token = (actual as? PropertyValue.Const)?.value as? Value.Token ?: return null
    return resolveToken(token, theme)
}
