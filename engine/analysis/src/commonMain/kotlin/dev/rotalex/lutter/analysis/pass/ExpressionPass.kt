package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.typing.ExprScope
import dev.rotalex.lutter.analysis.typing.TypeChecker
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.index.NodeOwner
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * Pass 5: typecheck every computed value, and resolve what it names (§17.1).
 *
 * Not an `AnalysisPass` because [AnalysisPass.run] answers with findings alone and this pass
 * has a product: pass 7 attaches a [TypedExpr] to every `ResolvedProp`, and walking the
 * expressions a second time to recover them would typecheck them twice. Non-blocking in
 * §17.1's sense — `Analyzer` runs no later pass once anything has errored.
 *
 * The expected type comes from the declaration the document's own property or modifier
 * argument names, so §10.4's bidirectional check is a lookup rather than an inference step:
 * a property the schema does not declare is pass 3's finding and is skipped here rather than
 * reported twice.
 */
internal class ExpressionPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) {

    /**
     * Findings plus the checked expressions, which is the whole of what this pass produces.
     *
     * [typed] is keyed by address because a property is addressed; [derived] by [StateId]
     * because a declaration is not, and §6.2 already makes one id one declaration.
     */
    class Result(
        public val diagnostics: List<Diagnostic>,
        public val typed: Map<ExprAddress, TypedExpr>,
        public val derived: Map<StateId, TypedExpr>,
    ) {
        public companion object {

            /** The answer for a document an earlier blocking pass has already refused. */
            public val NONE: Result = Result(emptyList(), emptyMap(), emptyMap())
        }
    }

    public fun check(document: UiDocument): Result {
        val found = mutableListOf<Diagnostic>()
        val typed = LinkedHashMap<ExprAddress, TypedExpr>()
        val derived = LinkedHashMap<StateId, TypedExpr>()
        val index = DocumentIndex(document)
        val checker = TypeChecker(schema, document.dataModels)
        for (id in document.nodes.ids()) {
            val node = document.nodes[id] ?: continue
            val scope = scopeOf(document, index, id) ?: continue
            checkNode(node, checker, scope, found, typed)
        }
        checkState(document, checker, found, derived)
        return Result(found, typed, derived)
    }

    private fun checkNode(
        node: Node,
        checker: TypeChecker,
        scope: ExprScope,
        found: MutableList<Diagnostic>,
        typed: MutableMap<ExprAddress, TypedExpr>,
    ) {
        val spec = schema.components[node.type] ?: return
        for ((key, actual) in node.props) {
            val declared = spec.properties.firstOrNull { it.key == key } ?: continue
            visit(node, key, null, declared.type, actual, checker, scope, found, typed)
        }
        for ((position, entry) in node.modifiers.withIndex()) {
            val params = schema.modifiers[entry.type]?.params ?: continue
            for ((key, actual) in entry.args) {
                val param = params.firstOrNull { it.key == key } ?: continue
                visit(node, key, position, param.type, actual, checker, scope, found, typed)
            }
        }
    }

    /**
     * Checks one computed value against the type its declaration promises.
     *
     * A `PropertyValue.Const` returns immediately, which is what keeps constants resolving
     * exactly as they did: pass 5 adds a typed expression and changes nothing else.
     */
    private fun visit(
        node: Node,
        key: PropertyKey,
        modifierIndex: Int?,
        expected: TypeRef,
        actual: PropertyValue,
        checker: TypeChecker,
        scope: ExprScope,
        found: MutableList<Diagnostic>,
        typed: MutableMap<ExprAddress, TypedExpr>,
    ) {
        val computed = (actual as? PropertyValue.Computed) ?: return
        val at = DiagnosticLocation(nodeId = node.id, property = key, modifierIndex = modifierIndex)
        val outcome = checker.check(computed.expr, expected, scope, at)
        found += outcome.diagnostics
        if (outcome.type != null) {
            typed[ExprAddress(node.id, key, modifierIndex)] =
                TypedExpr(computed.expr, outcome.type, outcome.refs)
        }
    }

    /**
     * The state declarations themselves, in §12.1's three scopes.
     *
     * A derived body is the one computed expression no node owns, so nothing else in the
     * pipeline walks it: without this its `Ref`s would first be resolved by the evaluator, at
     * render time, where a throw reads as a runtime fault rather than a document fault.
     */
    private fun checkState(
        document: UiDocument,
        checker: TypeChecker,
        found: MutableList<Diagnostic>,
        derived: MutableMap<StateId, TypedExpr>,
    ) {
        // App state has no owner, so it names app state and nothing else; §12.1 puts the app
        // store at the root, above any page or component's own.
        val appScope = ExprScope(stateOf(document, emptyList()), emptyMap())
        checkBodies(document.appState, appScope, DiagnosticLocation(), checker, found, derived)
        for ((id, page) in document.pages.entries.sortedBy { it.key.value }) {
            val scope = ExprScope(stateOf(document, page.state), paramsOf(page.params))
            checkBodies(page.state, scope, DiagnosticLocation(pageId = id), checker, found, derived)
        }
        for ((id, decl) in document.components.entries.sortedBy { it.key.value }) {
            val scope = ExprScope(stateOf(document, decl.state), paramsOf(decl.params))
            checkBodies(decl.state, scope, DiagnosticLocation(componentDeclId = id), checker, found, derived)
        }
    }

    /**
     * Each derived body in [states], checked against the type it declares.
     *
     * A held declaration has no body and is skipped, so "exactly one of `initial` and `derived`"
     * stays the model's to state rather than something this pass infers.
     */
    private fun checkBodies(
        states: List<StateDecl>,
        scope: ExprScope,
        at: DiagnosticLocation,
        checker: TypeChecker,
        found: MutableList<Diagnostic>,
        derived: MutableMap<StateId, TypedExpr>,
    ) {
        for (state in states) {
            val body = state.derived ?: continue
            val outcome = checker.check(body, state.type, scope, at)
            found += outcome.diagnostics
            if (outcome.type != null) derived[state.id] = TypedExpr(body, outcome.type, outcome.refs)
        }
    }

    /**
     * What an expression at [node] may name: app state plus its owner's, and its owner's params.
     *
     * §12.1 makes each of those a separate store at runtime — the app's is provided at the root,
     * a page's is its screen's parameter, a component's lives inside its composable — so a
     * component instance does not see the state of the page that happens to render it. A node
     * no root reaches is pass 1's orphan and has no owner to resolve against.
     */
    private fun scopeOf(document: UiDocument, index: DocumentIndex, node: NodeId): ExprScope? {
        val owner = index.ownerOf(node) ?: return null
        return when (owner) {
            is NodeOwner.Page -> {
                val page = document.pages[owner.id] ?: return null
                ExprScope(stateOf(document, page.state), paramsOf(page.params))
            }

            is NodeOwner.Component -> {
                val decl = document.components[owner.id] ?: return null
                ExprScope(stateOf(document, decl.state), paramsOf(decl.params))
            }
        }
    }

    /**
     * App state first and the owner's last, so one id in both scopes reads as the owner's.
     *
     * That precedence is this pass's choice, not the plan's: §12.1's two stores are separate
     * at runtime and §6.2 says an id is assigned once, so a document reaching this is one
     * NamingPass has already refused.
     */
    private fun stateOf(document: UiDocument, owned: List<StateDecl>): Map<StateId, TypeRef> =
        document.appState.plus(owned).associate { it.id to it.type }

    private fun paramsOf(params: List<ParamDecl>): Map<ParamName, TypeRef> =
        params.associate { it.name to it.type }
}

/**
 * One checked property: the node, the key, and which modifier's arguments it is when it is one.
 *
 * A modifier argument and a node property share a `PropertyKey` and nothing else, so the
 * position is part of the address — the same three of `DiagnosticLocation`'s fields pass 5
 * reports every one of its findings against.
 */
internal data class ExprAddress(
    public val node: NodeId,
    public val property: PropertyKey,
    public val modifierIndex: Int? = null,
)
