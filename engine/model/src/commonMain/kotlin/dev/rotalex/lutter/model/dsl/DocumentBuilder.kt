package dev.rotalex.lutter.model.dsl

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.IdGenerator
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.ids.SlotName
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
 */
public fun buildDocument(
    name: String,
    ids: IdGenerator = SequentialIdGenerator(),
    packageName: String = "com.example.app",
    startPage: PageId? = null,
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
    )
}

/**
 * The receiver of the [buildDocument] block: pages, components, and the node table.
 *
 * One global table, per §5.6: every `node` in every page and component lands in the same
 * [NodeTable], starting from [NodeTable.EMPTY]. Registration refuses a duplicated node id
 * outright — `NodeTable.with` replaces silently, which is right for a patch and wrong for
 * a builder, where it would mean two declarations collided.
 */
public class DocumentScope internal constructor(private val ids: IdGenerator) {

    internal var nodes: NodeTable = NodeTable.EMPTY

    internal val pages: LinkedHashMap<PageId, Page> = LinkedHashMap()

    internal val components: LinkedHashMap<ComponentDeclId, ComponentDecl> = LinkedHashMap()

    internal val pageOrder: MutableList<PageId> = mutableListOf()

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
        if (pages.containsKey(pageId)) {
            throw IllegalArgumentException("duplicate page id '$pageId': two pages share one id")
        }
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
        if (components.containsKey(id)) {
            throw IllegalArgumentException("duplicate component id '$id': two components share one id")
        }
        val body = PageScope(this)
        block(body)
        components[id] = ComponentDecl(id, name, params, slots, state, body.resolveRoot(name))
        return id
    }

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
