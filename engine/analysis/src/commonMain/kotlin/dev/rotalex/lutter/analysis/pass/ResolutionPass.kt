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
import dev.rotalex.lutter.analysis.resolved.SchemaRevision
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
import dev.rotalex.lutter.model.index.DirtyNodes
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SpecOrigin
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * Pass 7: lowers a clean document — defaults applied, scopes computed, tokens resolved, and
 * §12.1's state declarations attached to the scope each was declared in.
 *
 * Runs only when no pass reported an error, so every lookup below must hit; a miss is a
 * bug rather than a document fault. [checked] is pass 5's product, attached here rather than
 * re-derived: a computed property's type is settled before this pass and neither backend
 * re-derives it, and the same goes for a derived body's.
 *
 * With [dirty] and [previous] both present the pass is §6.2:537's reuse half as well: a node
 * outside the dirty set is not lowered at all — [previous]'s result is carried through at
 * reference identity, the untouched subtree below it included, which is the property
 * §29.1:2048's cache exists to buy. A carried subtree is also registered in the new index, so
 * [ResolvedDocument.nodes] keeps answering for every node the document holds. The carry is
 * additionally keyed on §29.1:2048's third component, [previous]'s schema revision: a
 * declaration edit or a version note changes the spec every instance resolves against
 * without touching a node, and a carry from a different revision — or from an unknown one —
 * is refused wholesale, because what changed is exactly the input no per-node guard can see.
 * Two per-node guards keep what survives honest, each covering an input the dirty set cannot
 * see: the recomputed scope set, because a placement change alters the slot path of every
 * node *below* the moved one ([ScopeAnalysis] computes scopes by walking up), and pass 5's
 * just-computed typings, because an expression's scope also carries its owner's state and
 * the item bindings of an iterating slot above it. A carried node failing either guard is
 * lowered again, and the output stays equal to a full run.
 */
internal class ResolutionPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
    private val checked: ExpressionPass.Result,
    private val revision: SchemaRevision,
) {
    public fun run(
        document: UiDocument,
        dirty: DirtyNodes? = null,
        previous: ResolvedDocument? = null,
    ): ResolvedDocument {
        val theme = selectTheme(document)
        val scopes = ScopeAnalysis.computeScopes(document) { schema.components[it] }
        val index = LinkedHashMap<NodeId, ResolvedNode>()

        // A carried node is carried with the subtree below it, and the document's index must
        // still answer for every node: [ResolvedDocument.nodes] is the contract callers
        // address nodes by, and a subtree that skips [build] would otherwise leave its
        // members out of it. Registration is bookkeeping only — the members ride at reference
        // identity, and their own guards held when the run that produced them lowered them:
        // an honest dirty set that leaves a carried root untouched leaves every node below it
        // untouched too, because any edit inside the subtree names the node it edits and the
        // chain above it.
        fun register(node: ResolvedNode) {
            for (children in node.slots.values) {
                for (child in children) {
                    index[child.id] = child
                    register(child)
                }
            }
        }

        fun propOf(
            node: NodeId,
            key: PropertyKey,
            modifierIndex: Int?,
            actual: PropertyValue?,
            default: Value?,
            themeDefault: PropertyValue?,
            selected: ThemeDecl?,
        ): ResolvedProp? {
            // §14.2's tier order: the document's own value, then the theme's default for the
            // node's component type, then the spec's. The theme tier is its own origin so the
            // generator can tell a value it must emit from one Compose already carries.
            val effective = actual ?: themeDefault ?: default?.let { PropertyValue.Const(it) } ?: return null
            val origin = when {
                actual != null -> PropOrigin.Specified
                themeDefault != null -> PropOrigin.Theme
                else -> PropOrigin.Default
            }
            val typed = if (actual is PropertyValue.Computed) {
                checked.typed[ExprAddress(node, key, modifierIndex)]
            } else {
                null
            }
            return ResolvedProp(key, effective, origin, tokenOf(effective, selected), typed)
        }

        fun build(id: NodeId): ResolvedNode {
            index[id]?.let { return it }
            // §6.2:537's reuse half: a node the dirty set does not name is not lowered at all —
            // `previous`'s result is carried through at reference identity, and with it the whole
            // untouched subtree below. The revision check above the per-node guards is §29.1:2048's
            // third cache component: a run may only reuse from its own schema context, because a
            // declaration edit or a version note changes what every instance resolves against
            // without touching a node. The two guards below are the inputs the dirty set cannot
            // see: the recomputed scope set (a placement change alters the slot path of every node
            // below the moved one), and pass 5's typings (an expression's scope also carries the
            // owner's state and the item bindings above it). A carried node failing any of the
            // three is lowered again, so the output stays equal to a full run.
            val carried = previous
                ?.takeIf { dirty == null || id !in dirty }
                ?.takeIf { it.schemaRevision == revision }
                ?.nodes
                ?.get(id)
            if (
                carried != null &&
                carried.scopes == scopes[id].orEmpty() &&
                typingsUnchanged(id, carried)
            ) {
                index[id] = carried
                register(carried)
                return carried
            }
            val node = checkNotNull(document.nodes[id]) { "Resolution reached node '$id' outside the table" }
            val spec = checkNotNull(schema.components[node.type]) { "Resolution reached unregistered type '${node.type}'" }
            val props = LinkedHashMap<PropertyKey, ResolvedProp>()
            // §14.2's defaults are keyed by the component type, so one lookup covers the node.
            val themeDefaults = theme?.componentDefaults[spec.type]
            for (declared in spec.properties.sortedBy { it.key.value }) {
                propOf(
                    id, declared.key, null,
                    node.props[declared.key], declared.default, themeDefaults?.get(declared.key),
                    theme,
                )?.let { props[declared.key] = it }
            }
            val modifiers = node.modifiers.withIndex().map { (position, entry) ->
                val modifier = checkNotNull(schema.modifiers[entry.type]) {
                    "Resolution reached unregistered modifier '${entry.type}'"
                }
                val params = modifier.params
                val args = LinkedHashMap<PropertyKey, ResolvedProp>()
                for (param in params.sortedBy { it.key.value }) {
                    // Theme defaults answer to the component's own properties; a modifier's
                    // argument map is a different surface, keyed by the modifier, not the type.
                    propOf(id, param.key, position, entry.args[param.key], param.default, null, theme)
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
        val componentSpecs = LinkedHashMap<ComponentDeclId, ComponentSpec>()
        // §8.4's specs, read back out of the view rather than synthesized a second time: this is
        // the same object passes 3 to 7 validated against, and re-deriving it here would let the
        // signature codegen emits drift from the one the document was checked against. `origin` is
        // what says which declaration a `doc.*` spec belongs to, so the lookup is keyed, not guessed.
        val specs: Map<ComponentDeclId, ComponentSpec> = schema.components.all()
            .mapNotNull { spec -> (spec.origin as? SpecOrigin.Document)?.let { it.id to spec } }
            .toMap()
        for ((id, decl) in document.components.entries.sortedBy { it.key.value }) {
            components[id] = build(decl.root)
            componentState[id] = statesOf(decl.state)
            componentSpecs[id] = checkNotNull(specs[id]) {
                "Declaration '$id' has no synthesized spec in the analyzed view"
            }
        }
        return ResolvedDocument(
            pages,
            components,
            index,
            ResolvedTheme(theme),
            statesOf(document.appState),
            componentState,
            document.hostFunctions,
            componentSpecs,
            document.resources,
        )
    }

    /**
     * §12.1's declarations, each in the list the document put it in: a page's on the page, the
     * app's on the document, a component's under its declaration. The checked body of a derived
     * one is pass 5's and is attached here rather than re-derived.
     */
    private fun statesOf(declarations: List<StateDecl>): List<ResolvedState> =
        declarations.map { ResolvedState(it, checked.derived[it.id]) }

    /**
     * Whether [carried]'s computed properties still hold what pass 5 just computed at their
     * addresses.
     *
     * [checked] is this run's typing of the whole document, and the address is the only key the
     * comparison can be made at: `ResolvedProp` carries no modifier position of its own, so the
     * walk recovers it from the carried modifier's index. A computed value that turned invalid
     * reads as a missing entry, which is a mismatch like any other — the carried node is lowered
     * again rather than trusted with a stale type.
     */
    private fun typingsUnchanged(id: NodeId, carried: ResolvedNode): Boolean {
        for ((key, prop) in carried.props) {
            if (prop.typed != checked.typed[ExprAddress(id, key)]) return false
        }
        for ((position, modifier) in carried.modifiers.withIndex()) {
            for ((key, prop) in modifier.args) {
                if (prop.typed != checked.typed[ExprAddress(id, key, position)]) return false
            }
        }
        return true
    }
}

private fun tokenOf(actual: PropertyValue, theme: ThemeDecl?): ResolvedToken? {
    val token = (actual as? PropertyValue.Const)?.value as? Value.Token ?: return null
    return resolveToken(token, theme)
}
