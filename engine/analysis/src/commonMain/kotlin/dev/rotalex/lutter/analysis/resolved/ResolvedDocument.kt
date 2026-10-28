package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.schema.component.ComponentSpec

/**
 * One page with its tree built: the unit runtime screens and codegen files consume.
 *
 * [state] is §12.1's page scope. It is a parameter with a default rather than a required one
 * because the three lists a declaration can live in were added to a type every backend already
 * read, and a caller with no state to describe should not have to say so twice.
 */
public data class ResolvedPage(
    public val id: PageId,
    public val name: String,
    public val route: String,
    public val root: ResolvedNode,
    public val state: List<ResolvedState> = emptyList(),
)

/**
 * The derived typed form both backends read. Ephemeral — never serialized, never stored.
 *
 * [nodes] indexes every tree node by id for callers that address nodes rather than walk.
 * [appState] and [componentState] are §12.1's other two scopes, attached beside the page one
 * rather than gathered into one map because each is reached through a different owner.
 *
 * [hostFunctions] is §11.6's declarations, carried rather than re-read from the document: §4.5's
 * single lowering pipeline leaves codegen no route to `UiDocument`, and a generated `AppHost` is
 * a member per declaration while `host.call` must know whether each one suspends. A default keeps
 * every existing construction of this type compiling.
 *
 * [componentSpecs] is the same argument one step further on. [components] holds a declaration's
 * **body** — a node, which says what it draws and nothing about how it is called — and the spec
 * `SchemaOverlay` synthesized for it is what says which params and slots it has. Codegen needs
 * both: the body to emit the function, the signature to call it. Synthesizing the spec a second
 * time inside the generator would be a second answer to a question the analyzer already settled,
 * and a generator whose call disagreed with the pass that validated the instance would emit a
 * function the document was never checked against. A default for the same reason as above.
 */
public data class ResolvedDocument(
    public val pages: Map<PageId, ResolvedPage>,
    public val components: Map<ComponentDeclId, ResolvedNode>,
    public val nodes: Map<NodeId, ResolvedNode>,
    public val theme: ResolvedTheme,
    public val appState: List<ResolvedState> = emptyList(),
    public val componentState: Map<ComponentDeclId, List<ResolvedState>> = emptyMap(),
    public val hostFunctions: List<HostFunctionDecl> = emptyList(),
    public val componentSpecs: Map<ComponentDeclId, ComponentSpec> = emptyMap(),
)
