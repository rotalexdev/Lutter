package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.typing.ExprScope
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.index.NodeOwner
import dev.rotalex.lutter.model.type.TypeRef

/**
 * One analysis phase. Internal: callers see [dev.rotalex.lutter.analysis.Analyzer] only.
 *
 * A blocking pass with errors stops later passes; the rest accumulate unconditionally.
 */
internal interface AnalysisPass {
    public val name: String
    public val blocking: Boolean
    public fun run(document: UiDocument): List<Diagnostic>
}

/**
 * What an expression at [id] may name: app state plus its owner's, and its owner's params.
 *
 * §12.1 makes each of those a separate store at runtime — the app's is provided at the root, a
 * page's is its screen's parameter, a component's lives inside its composable — so a component
 * instance does not see the state of the page that happens to render it. [eventArgs] is the one
 * thing here that is not a store: §11.2 introduces an event argument in a handler, so only pass
 * 6 supplies it and every other caller gets none.
 *
 * Null for a node no root reaches, which is pass 1's orphan and has no owner to resolve against.
 */
internal fun scopeOf(
    document: UiDocument,
    index: DocumentIndex,
    id: NodeId,
    eventArgs: Map<String, TypeRef> = emptyMap(),
): ExprScope? {
    val owned = ownerDeclsOf(document, index, id) ?: return null
    return scopeOf(document, owned, eventArgs)
}

/** [scopeOf] for a caller that has already walked to the owner, as pass 6 does for the states. */
internal fun scopeOf(
    document: UiDocument,
    owned: OwnerDecls,
    eventArgs: Map<String, TypeRef> = emptyMap(),
): ExprScope = ExprScope(stateOf(document, owned.state), paramsOf(owned.params), eventArgs)

/**
 * The declarations the owner of [id] holds, for a caller that needs them rather than their types.
 *
 * §12.3's write rule reads `StateDecl.derived`, which a type has already thrown away, so pass 6
 * asks for the owner once and gets both. Null for a node no root reaches, as [scopeOf] is.
 */
internal fun ownerDeclsOf(document: UiDocument, index: DocumentIndex, id: NodeId): OwnerDecls? {
    val owner = index.ownerOf(id) ?: return null
    return when (owner) {
        is NodeOwner.Page -> document.pages[owner.id]?.let { page ->
            OwnerDecls(page.state, page.params)
        }

        is NodeOwner.Component -> document.components[owner.id]?.let { decl ->
            OwnerDecls(decl.state, decl.params)
        }
    }
}

/** One node's owner's state and params, unresolved against app state. */
internal class OwnerDecls(
    public val state: List<StateDecl>,
    public val params: List<ParamDecl>,
)

/**
 * App state first and the owner's last, so one id in both scopes reads as the owner's.
 *
 * That precedence is this pass package's choice, not the plan's: §12.1's two stores are separate
 * at runtime and §6.2 says an id is assigned once, so a document reaching here is one
 * NamingPass has already refused.
 *
 * Internal rather than private because both pass 5 and pass 6 read it, and two copies of §12.1's
 * store order would be two answers the day one of them moved.
 */
internal fun stateOf(document: UiDocument, owned: List<StateDecl>): Map<StateId, TypeRef> =
    storesOf(document, owned).associate { it.id to it.type }

/** [stateOf] keeping the declarations, for the rules that read a state rather than type one. */
internal fun statesOf(document: UiDocument, owned: List<StateDecl>): Map<StateId, StateDecl> =
    storesOf(document, owned).associateBy { it.id }

/** The one place §12.1's store order is written down. */
private fun storesOf(document: UiDocument, owned: List<StateDecl>): List<StateDecl> =
    document.appState.plus(owned)

internal fun paramsOf(params: List<ParamDecl>): Map<ParamName, TypeRef> =
    params.associate { it.name to it.type }
