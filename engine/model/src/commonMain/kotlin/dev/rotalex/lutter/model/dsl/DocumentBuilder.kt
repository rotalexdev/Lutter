package dev.rotalex.lutter.model.dsl

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ColorRole
import dev.rotalex.lutter.model.doc.ColorSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.Qualifier
import dev.rotalex.lutter.model.doc.ResourceDecl
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.doc.ResourceSource
import dev.rotalex.lutter.model.doc.ResourceVariant
import dev.rotalex.lutter.model.doc.ShapeRole
import dev.rotalex.lutter.model.doc.ShapeSpec
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.TextStyleSpec
import dev.rotalex.lutter.model.doc.ThemeBase
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value

/**
 * The generic builder DSL: the programmatic way to construct a [UiDocument].
 *
 * PLAN §33.1:2400 names three types — [buildDocument], [NodeScope], [PageScope] — and
 * §24.2:1780 fixes the layering: this is the generic layer (`node(type) { prop; slot }`),
 * and typed sugar over it lives in `:engine:builtins`. Nothing here names a real component,
 * modifier or action, so adding one never touches this file.
 *
 * Ids come from [IdGenerator]; the default counts (`n_1`, `n_2`), which is what makes
 * a built document reviewable. No second minting mechanism exists.
 *
 * What this refuses, and what it does not: a missing root, a duplicated id and an empty
 * document are refused here, because the records give them no default. A dangling
 * reference decodes, because §17.1's passes own that verdict and a builder that refused
 * it would be a second validator to keep in step.
 *
 * ### Scalars are parameters, aggregates are calls
 *
 * [UiDocument] declares twelve fields, and they divide along one line rather than by
 * convenience. A fact the document *is* is a parameter of this function; a fact the document
 * *declares* — a collection, however the record stores it — is a call on [DocumentScope]. So
 * [theme] is a parameter next to [startPage], because both are selections the document makes
 * among things it contains rather than things it contains itself, while app state, the object
 * types, the enum types, the host functions, the themes and the resources are the six
 * aggregate calls. Pages, components and the node table keep the calls they already had.
 *
 * The same line runs one level down, which is why [DocumentScope.page] takes `params` and
 * `state` as parameters rather than a block for each: they are that page's facts, not the
 * document's, and a page with neither is the common case. A caller who writes a theme block
 * and a `state =` list in the same document is using one rule, not two.
 *
 * ### The selection is not checked, and why that is not a gap
 *
 * [theme] defaults to `null` and is **not** tested against what the block declared. Naming a
 * theme the document does not declare is a dangling reference, and it decodes for the reason the
 * paragraph above gives: [startPage] naming an absent page and [PageScope.root] pinning an absent
 * node both decode, and refusing a third kind here would be a second validator for a class of rule
 * that belongs to analysis rather than to a builder.
 *
 * What §14.2 makes total is the *resolution*, not the reference, and that is why leaving it alone
 * is loud rather than silent: a selection that resolves to nothing leaves every custom token as
 * `token.unknown`, which is exactly the answer a document with two themes and no selection already
 * gets, and it is an answer a reader sees in a diagnostic rather than a file that will not open.
 * No pass asserts today that the selection resolves, and that is a gap in analysis to close there
 * — a rule closed twice is two rules to keep in step, which is what this function exists to avoid.
 */
public fun buildDocument(
    name: String,
    ids: IdGenerator = SequentialIdGenerator(),
    packageName: String = "com.example.app",
    startPage: PageId? = null,
    theme: ThemeId? = null,
    block: DocumentScope.() -> Unit,
): UiDocument {
    val scope = DocumentScope(ids)
    block(scope)
    if (scope.pageOrder.isEmpty()) {
        throw IllegalStateException(
            "buildDocument('$name') declares no pages, so AppSpec.startPage has nothing " +
                "to point at; declare at least one page",
        )
    }
    return UiDocument(
        meta = DocumentMeta(name),
        app = AppSpec(packageName, startPage ?: scope.pageOrder.first()),
        pages = scope.pages,
        components = scope.components,
        nodes = scope.nodes,
        appState = scope.declaredAppState,
        dataModels = scope.dataModels,
        enums = scope.enums,
        hostFunctions = scope.declaredHostFunctions,
        themes = scope.themes,
        theme = theme,
        resources = scope.resources,
    )
}

/**
 * The receiver of the [buildDocument] block: the document's declarations, and the node table.
 *
 * One global table, per §5.6: every `node` in every page and component lands in the same
 * [NodeTable], starting from [NodeTable.EMPTY]. Registration refuses a duplicated node id
 * outright — `NodeTable.with` replaces silently, which is right for a patch and wrong for
 * a builder, where it would mean two declarations collided.
 *
 * ### Which declarations are here
 *
 * Everything `UiDocument` declares past its meta and app: pages and components, then the six
 * aggregates [buildDocument]'s own KDoc puts here rather than in its parameter list. The five
 * that carry a nested block of their own — [dataModel], [enumType], [hostFunction], [theme],
 * [resource] — each builds a record from a scope of this file, and each takes its id two ways,
 * a typed one and the same id as a string.
 *
 * ### Where a duplicated id is refused, and where it is not
 *
 * In the four that are stored as a map keyed by an id, which is [page] and [component] plus
 * [dataModel], [enumType], [theme] and [resource]. The rule is the one [register] already
 * applies and the reason is the same one: a map that quietly kept the last of two colliding
 * declarations would produce a document whose author cannot see the collision, and this is a
 * builder rather than a patch.
 *
 * **Not** in [appState] and [hostFunction], because those are lists and not maps — exactly the
 * ground on which [page]'s own `state` parameter has always been allowed to hold two of the same
 * [StateId]. A list has no key to collide on and no way to lose an entry silently, so the two
 * cases are not the same case.
 *
 * The two type maps are additionally one namespace: `dataModels` and `enums` are two halves of
 * a single `TypeId` value space, so a model and an enum sharing an id is refused too. Neither
 * record can see the other map, and no pass in the engine checks the pair, so a builder that let
 * it through would write a document whose ambiguity nothing downstream could name.
 */
public class DocumentScope internal constructor(private val ids: IdGenerator) {

    internal var nodes: NodeTable = NodeTable.EMPTY

    internal val pages: LinkedHashMap<PageId, Page> = LinkedHashMap()

    internal val components: LinkedHashMap<ComponentDeclId, ComponentDecl> = LinkedHashMap()

    internal val pageOrder: MutableList<PageId> = mutableListOf()

    // The accumulators the calls below fill are `declaredX` where `x` is a call's own name, and
    // that is one convention for the whole file rather than a per-class choice: `appState` and
    // `hostFunction` are singular, so the obvious field name would be the call's own, and a
    // reader who writes `appState` wants the call.
    internal val declaredAppState: MutableList<StateDecl> = mutableListOf()

    internal val dataModels: LinkedHashMap<DataModelId, DataModelDecl> = LinkedHashMap()

    internal val enums: LinkedHashMap<TypeId, EnumTypeDecl> = LinkedHashMap()

    internal val declaredHostFunctions: MutableList<HostFunctionDecl> = mutableListOf()

    internal val themes: LinkedHashMap<ThemeId, ThemeDecl> = LinkedHashMap()

    internal val resources: LinkedHashMap<ResourceId, ResourceDecl> = LinkedHashMap()

    /** A screen. The id defaults to the name, which is the derivation its KDoc sanctions. */
    public fun page(
        name: String,
        route: String,
        id: PageId? = null,
        params: List<ParamDecl> = emptyList(),
        state: List<StateDecl> = emptyList(),
        block: PageScope.() -> Unit,
    ): PageId {
        val pageId = id ?: PageId(name)
        refuseDuplicateId(pages, pageId, "page")
        val body = PageScope(this)
        block(body)
        pages[pageId] = Page(pageId, name, route, params, state, body.resolveRoot(name))
        pageOrder.add(pageId)
        return pageId
    }

    /** A reusable component. The id stays explicit: it is the tail of `doc.<id>` instances. */
    public fun component(
        id: ComponentDeclId,
        name: String,
        params: List<ParamDecl> = emptyList(),
        slots: List<SlotDecl> = emptyList(),
        state: List<StateDecl> = emptyList(),
        block: PageScope.() -> Unit,
    ): ComponentDeclId {
        refuseDuplicateId(components, id, "component")
        val body = PageScope(this)
        block(body)
        components[id] = ComponentDecl(id, name, params, slots, state, body.resolveRoot(name))
        return id
    }

    /** State scoped to the app, in declaration order. */
    public fun appState(decls: List<StateDecl>): Unit {
        declaredAppState.addAll(decls)
    }

    /**
     * A structured type the document declares for itself, keyed as `UiDocument.dataModels`.
     *
     * The id stays explicit, the way [component]'s does and [page]'s does not: a page's id is
     * reachable from the route and from `AppSpec.startPage`, while a data model's is the target
     * of every `TypeRef.Object` and every `Value.Obj` that mentions the type — so deriving it
     * from the Kotlin class name would make the §16.6:1307 rename a change to every reference in
     * the document, which is the failure §6.2's "ids are assigned once" exists to prevent.
     */
    public fun dataModel(id: DataModelId, name: String, block: DataModelScope.() -> Unit = {}): DataModelId {
        refuseDuplicateId(dataModels, id, "data model")
        refuseForeignType(TypeId(id.value), "an enum type")
        val body = DataModelScope(id, name)
        block(body)
        dataModels[id] = body.finish()
        return id
    }

    /** A data model, spelling its id as a string. */
    public fun dataModel(id: String, name: String, block: DataModelScope.() -> Unit = {}): DataModelId =
        dataModel(DataModelId(id), name, block)

    /** An enum type the document declares, keyed in the same `TypeId` space as [dataModel]. */
    public fun enumType(id: TypeId, name: String, block: EnumTypeScope.() -> Unit = {}): TypeId {
        refuseDuplicateId(enums, id, "enum type")
        refuseForeignType(TypeId(id.value), "a data model")
        val body = EnumTypeScope(id, name)
        block(body)
        enums[id] = body.finish()
        return id
    }

    /** An enum type, spelling its id as a string. */
    public fun enumType(id: String, name: String, block: EnumTypeScope.() -> Unit = {}): TypeId =
        enumType(TypeId(id), name, block)

    /**
     * A function the embedding application implements.
     *
     * [returns] has no default, which is §11.6's position and not an oversight: a function that
     * returns nothing has to say `returns = null`, and a declaration that quietly defaulted it
     * would let a whole class of typo decode silently. Appending rather than refusing a
     * repeated [name] is deliberate too — §11.6 declares no uniqueness rule, and §13.1 hands
     * `Page.route`'s to analysis rather than to a builder, so inventing one here would be a
     * second validator for a rule the plan does not state.
     */
    public fun hostFunction(
        name: String,
        returns: TypeRef?,
        suspend: Boolean = false,
        block: HostFunctionScope.() -> Unit = {},
    ): Unit {
        val body = HostFunctionScope(name, returns, suspend)
        block(body)
        declaredHostFunctions.add(body.finish())
    }

    /**
     * A theme, keyed as `UiDocument.themes`.
     *
     * This call plus its block covers eight of [ThemeDecl]'s nine fields. `base` is a parameter
     * because it is a selection like `buildDocument`'s `theme`; the five token maps are calls
     * because they are collections; and `componentDefaults` is the one left out, on purpose —
     * §14.2 marks it post-MVP, its payload is a `PropertyValue` union keyed by a component type
     * rather than a token role, and no pass reads it, so a call for it would claim support the
     * engine does not have. The five maps that are here are the five token resolution consults.
     */
    public fun theme(
        id: ThemeId,
        name: String,
        base: ThemeBase = ThemeBase.Material3,
        block: ThemeScope.() -> Unit = {},
    ): ThemeId {
        refuseDuplicateId(themes, id, "theme")
        val body = ThemeScope(id, name, base)
        block(body)
        themes[id] = body.finish()
        return id
    }

    /** A theme, spelling its id as a string. */
    public fun theme(
        id: String,
        name: String,
        base: ThemeBase = ThemeBase.Material3,
        block: ThemeScope.() -> Unit = {},
    ): ThemeId = theme(ThemeId(id), name, base, block)

    /** Something the document ships that is not code: a string, an image, a font, an icon, a file. */
    public fun resource(
        id: ResourceId,
        name: String,
        kind: ResourceKind,
        block: ResourceScope.() -> Unit = {},
    ): ResourceId {
        refuseDuplicateId(resources, id, "resource")
        val body = ResourceScope(id, name, kind)
        block(body)
        resources[id] = body.finish()
        return id
    }

    /** A resource, spelling its id as a string. */
    public fun resource(
        id: String,
        name: String,
        kind: ResourceKind,
        block: ResourceScope.() -> Unit = {},
    ): ResourceId = resource(ResourceId(id), name, kind, block)

    internal fun nextId(): NodeId = ids.nextNodeId()

    internal fun register(node: Node): NodeId {
        if (nodes.contains(node.id)) {
            throw IllegalArgumentException(
                "duplicate node id '${node.id}': two nodes share one id; ids are never reused (§6.2)",
            )
        }
        nodes = nodes.with(node)
        return node.id
    }

    /**
     * Refuses an id a map already holds, naming the kind so the message is actionable.
     *
     * One function rather than five copies because the rule is one rule, and a rule with five
     * copies is a rule whose fifth message will say something the other four do not.
     */
    private fun <K> refuseDuplicateId(declared: Map<K, *>, id: K, kind: String) {
        if (declared.containsKey(id)) {
            throw IllegalArgumentException("duplicate $kind id '$id': two ${kind}s share one id")
        }
    }

    /** Refuses an id the *other* type map already holds, because the two are one namespace. */
    private fun refuseForeignType(id: TypeId, heldAs: String) {
        if (dataModels.containsKey(DataModelId(id.value)) || enums.containsKey(id)) {
            throw IllegalArgumentException(
                "duplicate type id '$id': $heldAs already declares it, and a data model and an " +
                    "enum type are one TypeId namespace",
            )
        }
    }
}

/**
 * One data model's fields, in declaration order.
 *
 * The order is §16.6:1307's — the generated `data class` takes its properties in this order —
 * so appending is the only thing this scope does, and there is no way to reorder a field after
 * the fact without changing the shape of the type it generates.
 */
public class DataModelScope internal constructor(
    private val id: DataModelId,
    private val name: String,
) {

    private val fields: MutableList<FieldDecl> = mutableListOf()

    /** One field. The name is the key a `Value.Obj` writes it under, so it is checked. */
    public fun field(name: PropertyKey, type: TypeRef): Unit {
        fields.add(FieldDecl(name, type))
    }

    /** One field, spelling the name as a string. */
    public fun field(name: String, type: TypeRef): Unit = field(PropertyKey(name), type)

    internal fun finish(): DataModelDecl = DataModelDecl(id, name, fields)
}

/**
 * One enum type's entries, in declaration order.
 *
 * An entry is a name and nothing else, because `Value.Enum` is the whole value a document can
 * write against an enum; a per-entry payload would be a second fact with nowhere to live.
 */
public class EnumTypeScope internal constructor(
    private val id: TypeId,
    private val name: String,
) {

    private val entries: MutableList<EnumEntryDecl> = mutableListOf()

    /** One entry, whose name is also the symbol the generated enum uses. */
    public fun entry(name: String): Unit {
        entries.add(EnumEntryDecl(name))
    }

    internal fun finish(): EnumTypeDecl = EnumTypeDecl(id, name, entries)
}

/**
 * One host function's typed inputs, in declaration order.
 *
 * A list rather than a map for the reason `HostFunctionDecl.params` is one: §16.4's generated
 * interface has the parameters in declaration order and a call site has to name them the same
 * way, so reordering two of different types is a different function rather than a cosmetic
 * change.
 */
public class HostFunctionScope internal constructor(
    private val name: String,
    private val returns: TypeRef?,
    private val suspend: Boolean,
) {

    private val params: MutableList<ParamDecl> = mutableListOf()

    /** One typed input. `required` defaults to the record's, which is `true`. */
    public fun param(name: ParamName, type: TypeRef, required: Boolean = true): Unit {
        params.add(ParamDecl(name, type, required))
    }

    /** One typed input, spelling the name as a string. */
    public fun param(name: String, type: TypeRef, required: Boolean = true): Unit =
        param(ParamName(name), type, required)

    internal fun finish(): HostFunctionDecl = HostFunctionDecl(name, params, returns, suspend)
}

/**
 * One theme's five token maps.
 *
 * Five calls and not one, because a call that took all five could not say which map a token
 * belongs to, and the map it belongs to is the whole difference between a colour and a gap.
 * Each is keyed by an open role string, so a second call for a key this theme already carries
 * is refused the way a second node id is: replacing would hide a collision rather than report
 * it.
 *
 * The accumulators are named apart from the calls for the same reason `DocumentScope`'s are — one
 * convention, one place to look — and because `typography` and `custom` are singular, so the
 * obvious map name would be the call's own.
 *
 * `componentDefaults` is absent; `DocumentScope.theme` says why, and the five that are here are
 * the five token resolution actually consults.
 */
public class ThemeScope internal constructor(
    private val id: ThemeId,
    private val name: String,
    private val base: ThemeBase,
) {

    private val declaredColors: LinkedHashMap<ColorRole, ColorSpec> = LinkedHashMap()

    private val declaredTypography: LinkedHashMap<TextRole, TextStyleSpec> = LinkedHashMap()

    private val declaredShapes: LinkedHashMap<ShapeRole, ShapeSpec> = LinkedHashMap()

    private val declaredDimensions: LinkedHashMap<TokenName, Value.Dp> = LinkedHashMap()

    private val declaredCustom: LinkedHashMap<TokenName, Value> = LinkedHashMap()

    /** One colour role, as the light/dark pair §14.1 declares it. */
    public fun color(role: ColorRole, spec: ColorSpec): Unit {
        refuseDuplicate(declaredColors, role, "colour role")
        declaredColors[role] = spec
    }

    /**
     * One colour role, spelled as a string and with the pair written inline.
     *
     * Inline rather than a `ColorSpec` argument because the pair is the only payload, so asking
     * a caller to wrap two values in a record to hand them back is the ceremony the DSL exists
     * to avoid — and `prop("text", Value.Str("Welcome"))` is the same call, made the same way.
     */
    public fun color(role: String, light: ColorArgb, dark: ColorArgb? = null): Unit =
        color(ColorRole(role), ColorSpec(light, dark))

    /** One typography role, as the open property map §14.1 declares rather than a closed record. */
    public fun typography(role: TextRole, style: TextStyleSpec): Unit {
        refuseDuplicate(declaredTypography, role, "typography role")
        declaredTypography[role] = style
    }

    /** One typography role, spelling the role as a string. */
    public fun typography(role: String, style: TextStyleSpec): Unit = typography(TextRole(role), style)

    /** One shape role, as the same open property map a text style is. */
    public fun shape(role: ShapeRole, spec: ShapeSpec): Unit {
        refuseDuplicate(declaredShapes, role, "shape role")
        declaredShapes[role] = spec
    }

    /** One shape role, spelling the role as a string. */
    public fun shape(role: String, spec: ShapeSpec): Unit = shape(ShapeRole(role), spec)

    /** One entry of the spacing scale. Typed `Value.Dp` because a dimension scale has one unit. */
    public fun dimension(token: TokenName, value: Value.Dp): Unit {
        refuseDuplicate(declaredDimensions, token, "dimension token")
        declaredDimensions[token] = value
    }

    /** One entry of the spacing scale, spelling the token as a string. */
    public fun dimension(token: String, value: Value.Dp): Unit = dimension(TokenName(token), value)

    /** One brand token, of any kind: a colour and a gap are both legal here. */
    public fun custom(token: TokenName, value: Value): Unit {
        refuseDuplicate(declaredCustom, token, "custom token")
        declaredCustom[token] = value
    }

    /** One brand token, spelling the token as a string. */
    public fun custom(token: String, value: Value): Unit = custom(TokenName(token), value)

    internal fun finish(): ThemeDecl =
        ThemeDecl(
            id = id,
            name = name,
            base = base,
            colors = declaredColors,
            typography = declaredTypography,
            shapes = declaredShapes,
            dimensions = declaredDimensions,
            custom = declaredCustom,
        )

    /** Refuses a key this theme already carries, naming the map it was declared in. */
    private fun <K, V> refuseDuplicate(declared: Map<K, V>, key: K, kind: String) {
        if (declared.containsKey(key)) {
            throw IllegalArgumentException(
                "duplicate $kind '$key' in theme '$id': a theme gives one key one value",
            )
        }
    }
}

/**
 * One resource's variants: the ways of reaching it.
 *
 * Two variants answering to the same qualifiers are refused, because §20 selects a variant by
 * set membership against the environment and two members would make that choice arbitrary —
 * which is the only reason [ResourceVariant.qualifiers] stays a `Set` in the first place.
 */
public class ResourceScope internal constructor(
    private val id: ResourceId,
    private val name: String,
    private val kind: ResourceKind,
) {

    private val variants: MutableList<ResourceVariant> = mutableListOf()

    /** One way of reaching the resource. The empty qualifier set is the one that matches anything. */
    public fun variant(qualifiers: Set<Qualifier>, source: ResourceSource): Unit {
        if (variants.any { it.qualifiers == qualifiers }) {
            throw IllegalArgumentException(
                "duplicate variant in resource '$id': two variants answer to the same qualifiers, " +
                    "and selection is set membership",
            )
        }
        variants.add(ResourceVariant(qualifiers, source))
    }

    internal fun finish(): ResourceDecl = ResourceDecl(id, name, kind, variants)
}

/**
 * A page or component body: top-level nodes, and which one is the root.
 *
 * The root is explicit `root(id)` or the single top-level node. Zero nodes, or several
 * with no pin, are refused: the records give `root` no default, and guessing among many
 * would orphan subtrees the structural pass then has to explain.
 */
public class PageScope internal constructor(private val documents: DocumentScope) {

    private val tops: MutableList<NodeId> = mutableListOf()

    private var pinnedRoot: NodeId? = null

    /** A top-level node of this body. The id is minted unless given. */
    public fun node(
        type: ComponentType,
        id: NodeId? = null,
        name: String? = null,
        block: NodeScope.() -> Unit = {},
    ): NodeId {
        val scope = NodeScope(documents, type, id ?: documents.nextId(), name)
        block(scope)
        val built = scope.finish()
        tops.add(built)
        return built
    }

    /** A top-level node, spelling the type as a string. A dotless type is refused. */
    public fun node(
        type: String,
        id: NodeId? = null,
        name: String? = null,
        block: NodeScope.() -> Unit = {},
    ): NodeId = node(ComponentType(type), id, name, block)

    /** Pins the root explicitly. A dangling id decodes; §17.1 reports it. */
    public fun root(id: NodeId): Unit {
        pinnedRoot = id
    }

    internal fun resolveRoot(owner: String): NodeId {
        val pinned = pinnedRoot
        if (pinned != null) return pinned
        if (tops.size == 1) return tops.single()
        throw IllegalStateException(
            "page or component '$owner' has ${tops.size} top-level nodes and no root(id) pin; " +
                "declare exactly one, or pin one",
        )
    }
}

/**
 * One node under construction: its props, modifiers, slots and events.
 *
 * Children reach a slot only through [slot]: a `node` whose id is never slotted is an
 * orphan, and orphans are §17.1's to report, not this scope's to forbid. Every id-typed
 * argument validates its own syntax in its constructor, so an ill-formed name fails here
 * with the record's own message rather than somewhere downstream.
 */
public class NodeScope internal constructor(
    private val documents: DocumentScope,
    private val nodeType: ComponentType,
    private val nodeId: NodeId,
    private val nodeName: String?,
) {

    private val props: LinkedHashMap<PropertyKey, PropertyValue> = LinkedHashMap()

    private val modifiers: MutableList<ModifierEntry> = mutableListOf()

    private val slots: LinkedHashMap<SlotName, MutableList<NodeId>> = LinkedHashMap()

    private val events: LinkedHashMap<EventKey, ActionSequence> = LinkedHashMap()

    /** A child node. Minted unless given; attach it with [slot]. */
    public fun node(
        type: ComponentType,
        id: NodeId? = null,
        name: String? = null,
        block: NodeScope.() -> Unit = {},
    ): NodeId {
        val scope = NodeScope(documents, type, id ?: documents.nextId(), name)
        block(scope)
        return scope.finish()
    }

    /** A child node, spelling the type as a string. */
    public fun node(
        type: String,
        id: NodeId? = null,
        name: String? = null,
        block: NodeScope.() -> Unit = {},
    ): NodeId = node(ComponentType(type), id, name, block)

    /** Fills a slot with children, in render order. A list and not a `vararg`: Kotlin
     * forbids a `vararg` of a value class, and [NodeId] is one. */
    public fun slot(name: SlotName, children: List<NodeId>): Unit {
        slots.getOrPut(name) { mutableListOf() }.addAll(children)
    }

    /** Fills a slot, spelling its name as a string. */
    public fun slot(name: String, children: List<NodeId>): Unit = slot(SlotName(name), children)

    /** A literal property. The value is wrapped as a constant. */
    public fun prop(key: PropertyKey, value: Value): Unit {
        props[key] = PropertyValue.Const(value)
    }

    /** A literal property, spelling the key as a string. */
    public fun prop(key: String, value: Value): Unit = prop(PropertyKey(key), value)

    /** A computed property. Whether it typechecks is §17.1's ExpressionPass, not this. */
    public fun computed(key: PropertyKey, expr: Expr): Unit {
        props[key] = PropertyValue.Computed(expr)
    }

    /** A computed property, spelling the key as a string. */
    public fun computed(key: String, expr: Expr): Unit = computed(PropertyKey(key), expr)

    /** A modifier entry. Order is application order; this appends. */
    public fun modifier(type: ModifierType, args: Map<PropertyKey, PropertyValue> = emptyMap()): Unit {
        modifiers.add(ModifierEntry(type, args))
    }

    /** A modifier entry, spelling the type as a string. */
    public fun modifier(type: String, args: Map<PropertyKey, PropertyValue> = emptyMap()): Unit =
        modifier(ModifierType(type), args)

    /** An event handler. Unknown keys decode; §17.1 reports them. */
    public fun event(key: EventKey, sequence: ActionSequence): Unit {
        events[key] = sequence
    }

    /** An event handler, spelling the key as a string. */
    public fun event(key: String, sequence: ActionSequence): Unit = event(EventKey(key), sequence)

    internal fun finish(): NodeId = documents.register(
        Node(nodeId, nodeType, nodeName, props, modifiers, slots, events),
    )
}
