package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.TypeId
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
    public val resources: Map<ResourceId, ResourceDecl> = emptyMap(),

    /**
     * §5.4's document-declared object types, carried rather than re-read: §16.6 emits each one as
     * a data class in `model/Models.kt`, and §4.5's single lowering pipeline leaves codegen no
     * route to the [dev.rotalex.lutter.model.doc.UiDocument] that declares them. Carriage is what
     * makes D27's reserved-name check possible at all — a name nobody can see is a name nobody
     * can refuse.
     *
     * A default for the same reason as [hostFunctions]: every construction of this type that
     * predates models keeps compiling, and a document that declares none says nothing.
     */
    public val dataModels: Map<DataModelId, DataModelDecl> = emptyMap(),

    /**
     * §5.4's document-declared enums, in the same `TypeId` value space as a model's id. They share
     * `model/Models.kt` with [dataModels], so they are carried beside them and checked by the same
     * pass.
     */
    public val enums: Map<TypeId, EnumTypeDecl> = emptyMap(),

    /**
     * The schema context this lowering ran against, §29.1:2048's third cache component.
     * [SchemaRevision.NONE] — the default, for hand-built documents — means the context is
     * unknown, and the carry rule answers that the same way it answers any revision it does
     * not recognize: recompute rather than trust.
     */
    public val schemaRevision: SchemaRevision = SchemaRevision.NONE,
)
