package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedModifier
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.analysis.resolved.ResolvedToken
import dev.rotalex.lutter.analysis.resolved.resolveToken
import dev.rotalex.lutter.analysis.resolved.selectTheme
import dev.rotalex.lutter.analysis.scope.ScopeAnalysis
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * Pass 7: lowers a clean document — defaults applied, scopes computed, tokens resolved, and
 * §12.1's state declarations attached to the scope each was declared in.
 *
 * Runs only when no pass reported an error, so every lookup below must hit; a miss is a
 * bug rather than a document fault. [checked] is pass 5's product, attached here rather than
 * re-derived: a computed property's type is settled before this pass and neither backend
 * re-derives it, and the same goes for a derived body's.
 */
internal class ResolutionPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
    private val checked: ExpressionPass.Result,
) {
    public fun run(document: UiDocument): ResolvedDocument {
        val theme = selectTheme(document)
        val scopes = ScopeAnalysis.computeScopes(document) { schema.components[it] }
        val index = LinkedHashMap<NodeId, ResolvedNode>()

        fun propOf(
            node: NodeId,
            key: PropertyKey,
            modifierIndex: Int?,
            actual: PropertyValue?,
            default: Value?,
            selected: ThemeDecl?,
        ): ResolvedProp? {
            val effective = actual ?: default?.let { PropertyValue.Const(it) } ?: return null
            val origin = if (actual != null) PropOrigin.Specified else PropOrigin.Default
            val typed = if (actual is PropertyValue.Computed) {
                checked.typed[ExprAddress(node, key, modifierIndex)]
            } else {
                null
            }
            return ResolvedProp(key, effective, origin, tokenOf(effective, selected), typed)
        }

        fun build(id: NodeId): ResolvedNode {
            index[id]?.let { return it }
            val node = checkNotNull(document.nodes[id]) { "Resolution reached node '$id' outside the table" }
            val spec = checkNotNull(schema.components[node.type]) { "Resolution reached unregistered type '${node.type}'" }
            val props = LinkedHashMap<PropertyKey, ResolvedProp>()
            for (declared in spec.properties.sortedBy { it.key.value }) {
                propOf(id, declared.key, null, node.props[declared.key], declared.default, theme)
                    ?.let { props[declared.key] = it }
            }
            val modifiers = node.modifiers.withIndex().map { (position, entry) ->
                val modifier = checkNotNull(schema.modifiers[entry.type]) {
                    "Resolution reached unregistered modifier '${entry.type}'"
                }
                val params = modifier.params
                val args = LinkedHashMap<PropertyKey, ResolvedProp>()
                for (param in params.sortedBy { it.key.value }) {
                    propOf(id, param.key, position, entry.args[param.key], param.default, theme)
                        ?.let { args[param.key] = it }
                }
                ResolvedModifier(entry.type, args)
            }
            val slots = LinkedHashMap<SlotName, List<ResolvedNode>>()
            for (slot in spec.slots) {
                slots[slot.name] = (node.slots[slot.name] ?: emptyList()).map(::build)
            }
            return ResolvedNode(node.id, node.type, props, modifiers, slots, scopes[id] ?: emptySet(), node.events)
                .also { index[id] = it }
        }

        val pages = document.pages.entries.sortedBy { it.key.value }
            .associate { (id, page) ->
                id to ResolvedPage(id, page.name, page.route, build(page.root), statesOf(page.state))
            }
        val components = LinkedHashMap<ComponentDeclId, ResolvedNode>()
        val componentState = LinkedHashMap<ComponentDeclId, List<ResolvedState>>()
        for ((id, decl) in document.components.entries.sortedBy { it.key.value }) {
            components[id] = build(decl.root)
            componentState[id] = statesOf(decl.state)
        }
        return ResolvedDocument(
            pages,
            components,
            index,
            ResolvedTheme(theme),
            statesOf(document.appState),
            componentState,
            document.hostFunctions,
        )
    }

    /**
     * §12.1's declarations, each in the list the document put it in: a page's on the page, the
     * app's on the document, a component's under its declaration. The checked body of a derived
     * one is pass 5's and is attached here rather than re-derived.
     */
    private fun statesOf(declarations: List<StateDecl>): List<ResolvedState> =
        declarations.map { ResolvedState(it, checked.derived[it.id]) }
}

private fun tokenOf(actual: PropertyValue, theme: ThemeDecl?): ResolvedToken? {
    val token = (actual as? PropertyValue.Const)?.value as? Value.Token ?: return null
    return resolveToken(token, theme)
}
