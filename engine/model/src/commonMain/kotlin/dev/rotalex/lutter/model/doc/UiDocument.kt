package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import kotlinx.serialization.Serializable

/**
 * The document: the root record everything else in the engine is written against.
 *
 * PLAN §5.5 declares fourteen fields and this class declares fourteen. Four of them are maps
 * keyed by a typed id and none of them is keyed by a name, which is §6.2's rule that ids are
 * assigned once and never derived from a position or a name — a rename touches [name] and
 * nothing else.
 *
 * ### The table is the document
 *
 * [nodes] is one global [NodeTable] and not a per-page table. ADR-001 and §5.6 chose that
 * over the nested tree for the reasons §5.6's own row tabulates, and the choice costs
 * invariants somebody has to check: every node reachable from exactly one root, exactly one
 * parent, no cycles, every id in a slot naming a node that exists. Those are §17.1's
 * structural pass, not this record's — no single node can see the table it belongs to.
 *
 * ### `enums` and `dataModels` are one value space
 *
 * Both are keyed by a `TypeId`-shaped id — `DataModelId` and `TypeId` are the same value
 * class over the same id syntax (§5.2) — and §9.1 references an enum type and an object type
 * through the same `TypeId`, so the two maps are two halves of one namespace and the two maps
 * are disjoint. `dataModels` keeps its `DataModelId` key rather than being retyped because
 * they are interchangeable on the wire, and retyping a stored key to fix a distinction nothing
 * can observe would break every document.
 *
 * ### `theme` selects, `themes` declares
 *
 * A nullable id with a default, §14.2 makes the resolution a total function of it, and the
 * rule is worth knowing before reading the field: `null` with exactly one theme selects that
 * theme, because `themes = { "t": … }` is the shape any first document has and it should not
 * have to restate itself. `null` with two or more resolves only against Material's base, so
 * every custom `Value.Token` becomes `token.unknown` — a document that has themes and
 * selects none is a mistake, and it is answered loudly, once per token, rather than with a
 * new diagnostic code to keep in step.
 */
@Serializable
public data class UiDocument(

    /** What the document calls itself and what it was authored against. */
    public val meta: DocumentMeta,

    /** The package, the start page and the navigation hints. */
    public val app: AppSpec,

    /** The screens, keyed by id. */
    public val pages: Map<PageId, Page>,

    /** The document-defined reusable components, keyed by id. */
    public val components: Map<ComponentDeclId, ComponentDecl>,

    /** Every node in the document, in one table (ADR-001). No default: a document has one. */
    public val nodes: NodeTable,

    /** State scoped to the app, not to a page or a component (§12.1). */
    public val appState: List<StateDecl> = emptyList(),

    /** Object types the document declares, keyed by `DataModelId`. */
    public val dataModels: Map<DataModelId, DataModelDecl> = emptyMap(),

    /** Enum types the document declares, keyed by the same value space as [dataModels]. */
    public val enums: Map<TypeId, EnumTypeDecl> = emptyMap(),

    /** Functions the embedding application implements (§11.6). */
    public val hostFunctions: List<HostFunctionDecl> = emptyList(),

    /** The themes the document declares, keyed by id. */
    public val themes: Map<ThemeId, ThemeDecl> = emptyMap(),

    /** Which of [themes] is selected; `null` is the rule in §14.2 and in this type's KDoc. */
    public val theme: ThemeId? = null,

    /** The resources the document ships, keyed by id. */
    public val resources: Map<ResourceId, ResourceDecl> = emptyMap(),
)
