package dev.rotalex.lutter.editing.command

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
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
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierSpec

// Fixtures for the command planners.
//
// Hand-built for the reason `PatchRoundTripTest` builds its own rather than reaching for a
// harness: an expected document derived from the same helpers the base document came from is
// legible, and a helper with seventeen parameters is not. Two files need this vocabulary — the
// rejection table and the round-trip table — so it is written down once rather than twice.
//
// The component vocabulary is chosen so that every §25.3 policy is reachable from it without a
// second schema: a `Many` slot with no filter, a `ZeroOrOne` slot with a closed filter, and an
// `ExactlyOne` slot with a closed filter. A suite that only had the first would pass with the
// two checks deleted.

// ---------------------------------------------------------------------------------------
// The component vocabulary
// ---------------------------------------------------------------------------------------

/** A layout with an unbounded slot and no filter: the case where everything is legal. */
internal val COLUMN: ComponentType = ComponentType("core.Column")

/** A leaf with a declared property and a declared event. */
internal val TEXT: ComponentType = ComponentType("m3.Text")

/** One optional child, from a closed set of types: `ZeroOrOne` plus a filter. */
internal val CARD: ComponentType = ComponentType("core.Card")

/** The other type [CARD] admits, so a filter refusal is about the child and not the set. */
internal val IMAGE: ComponentType = ComponentType("core.Image")

/** Exactly one child, from a closed set: a slot that is both full and closed. */
internal val BANNER: ComponentType = ComponentType("m3.Banner")

/** Registered by no schema, so a parent of this type cannot have its slots read. */
internal val UNREGISTERED: ComponentType = ComponentType("m3.Widget")

internal val CHILDREN: SlotName = SlotName("children")
internal val CONTENT: SlotName = SlotName("content")
internal val TOP_BAR: SlotName = SlotName("topBar")

/** A `Many` slot with a *negative* filter, so a child refusal is never also a fullness one. */
internal val FOOTER: SlotName = SlotName("footer")

internal val TEXT_KEY: PropertyKey = PropertyKey("text")
internal val ON_CLICK: EventKey = EventKey("onClick")
internal val PADDING: ModifierEntry = ModifierEntry(ModifierType("layout.padding"))
internal val BACKGROUND: ModifierEntry = ModifierEntry(ModifierType("layout.background"))
internal val CLICK: ActionSequence = ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
internal val ALPHA_TEXT: PropertyValue = PropertyValue.Const(Value.Str("Alpha"))
internal val OMEGA_TEXT: PropertyValue = PropertyValue.Const(Value.Str("Omega"))

// ---------------------------------------------------------------------------------------
// The node vocabulary
// ---------------------------------------------------------------------------------------

internal val ROOT: NodeId = NodeId("n_root")
internal val ALPHA: NodeId = NodeId("n_a")
internal val BETA: NodeId = NodeId("n_b")
internal val GAMMA: NodeId = NodeId("n_c")
internal val DELTA: NodeId = NodeId("n_d")
internal val ECHO: NodeId = NodeId("n_e")
internal val FOXTROT: NodeId = NodeId("n_f")
internal val GOLF: NodeId = NodeId("n_g")
internal val HOTEL: NodeId = NodeId("n_h")
internal val INDIA: NodeId = NodeId("n_i")
internal val ABSENT: NodeId = NodeId("n_absent")
internal val JULIET: NodeId = NodeId("n_j")
internal val KILO: NodeId = NodeId("n_k")
internal val LIMA: NodeId = NodeId("n_l")

internal val HOME: PageId = PageId("p_home")
internal val NEW: PageId = PageId("p_new")

/** The ids a `SequentialIdGenerator` hands out in a document that uses none of them. */
internal val MINTED_ONE: NodeId = NodeId("n_1")
internal val MINTED_TWO: NodeId = NodeId("n_2")
internal val MINTED_THREE: NodeId = NodeId("n_3")

/**
 * The one node every field setter is planned against, because it is the only one carrying all
 * four writable fields: a label, a property, a modifier chain and a handler. Every parameter
 * is a field one of the four commands writes, so a row of the round-trip table names the one
 * thing it changes and nothing else has to be repeated.
 */
internal fun alphaNode(
    name: String? = "Alpha",
    text: PropertyValue? = ALPHA_TEXT,
    modifiers: List<ModifierEntry> = listOf(PADDING),
    events: Map<EventKey, ActionSequence> = mapOf(ON_CLICK to CLICK),
): Node = Node(
    id = ALPHA,
    type = TEXT,
    name = name,
    props = if (text == null) emptyMap() else mapOf(TEXT_KEY to text),
    modifiers = modifiers,
    events = events,
)

/** A nested column, so "a subtree" and "a node with children" are not the same thing. */
internal fun betaNode(): Node = Node(id = BETA, type = COLUMN, slots = mapOf(CHILDREN to listOf(DELTA, ECHO)))

internal fun gammaNode(name: String? = null): Node = Node(id = GAMMA, type = TEXT, name = name)

/** One child already in [CONTENT], so the card's `ZeroOrOne` is one away from full. */
internal fun cardNode(): Node = Node(id = FOXTROT, type = CARD, slots = mapOf(CONTENT to listOf(HOTEL)))

/** [TOP_BAR] holds its one child, so this is the node that refuses a second. */
internal fun bannerNode(): Node = Node(id = GOLF, type = BANNER, slots = mapOf(TOP_BAR to listOf(INDIA)))

internal fun deltaNode(): Node = Node(id = DELTA, type = TEXT)
internal fun echoNode(): Node = Node(id = ECHO, type = TEXT)
internal fun hotelNode(): Node = Node(id = HOTEL, type = TEXT)
internal fun indiaNode(): Node = Node(id = INDIA, type = TEXT)

/**
 * A column whose children are themselves columns, so unwrapping it into a slot that admits
 * only leaves is refused by the filter alone.
 */
internal fun julietNode(): Node = Node(id = JULIET, type = COLUMN, slots = mapOf(CHILDREN to listOf(KILO, LIMA)))

internal fun kiloNode(): Node = Node(id = KILO, type = COLUMN)
internal fun limaNode(): Node = Node(id = LIMA, type = COLUMN)

/** A second screen, for the rows that add one. Its root is a row the base document lacks. */
internal fun newPage(): Page = Page(id = NEW, name = "NewScreen", route = "/new", root = MINTED_ONE)

internal fun homePage(): Page = Page(id = HOME, name = "HomeScreen", route = "/home", root = ROOT)

/** A single row under an id the base document does not hold. */
internal fun freeText(): Node = Node(id = MINTED_ONE, type = TEXT)

/** A two-node subtree, root first, for an insertion that carries more than a row. */
internal fun nestNode(): Node = Node(id = MINTED_ONE, type = COLUMN, slots = mapOf(CHILDREN to listOf(MINTED_TWO)))

internal fun nestLeaf(): Node = Node(id = MINTED_TWO, type = TEXT)

/** The nine nodes the base document holds, in a fixed order. */
internal fun baseRows(): List<Node> = listOf(
    alphaNode(),
    betaNode(),
    gammaNode(),
    cardNode(),
    bannerNode(),
    deltaNode(),
    echoNode(),
    hotelNode(),
    indiaNode(),
    julietNode(),
    kiloNode(),
    limaNode(),
)

/**
 * The base document every case starts from.
 *
 * [rootType] is a parameter so that "the parent's component type is not registered" is
 * reachable without a second fixture: the root is the node whose slots every structural
 * planner has to read, and swapping its type is the whole of that refusal.
 *
 * [rows] rather than a list of booleans, so a case that adds a row and a case that removes
 * one are both one argument — and so that a case can build a document with *only* the rows it
 * needs, which the malformed-document refusals do.
 */
internal fun plannerDocument(
    rootType: ComponentType = COLUMN,
    rootChildren: List<NodeId> = listOf(ALPHA, BETA, GAMMA, FOXTROT, GOLF),
    rows: List<Node> = baseRows(),
    pages: Map<PageId, Page> = mapOf(HOME to homePage()),
    components: Map<ComponentDeclId, ComponentDecl> = emptyMap(),
): UiDocument {
    var table: NodeTable = NodeTable.EMPTY.with(
        Node(id = ROOT, type = rootType, slots = mapOf(CHILDREN to rootChildren)),
    )
    for (node in rows) table = table.with(node)

    return UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", HOME),
        pages = pages,
        components = components,
        nodes = table,
    )
}

// ---------------------------------------------------------------------------------------
// The schema
// ---------------------------------------------------------------------------------------

/**
 * A schema with real specs, so every legality question goes through the registry and none is
 * answered by a branch on a component type — which §7.4 forbids anywhere in the engine.
 */
internal fun plannerSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
    Schema.build<ComponentSpec, ModifierSpec, String, String, String> { plannerVocabulary() }

/**
 * Declared once and generic in the three registries the planners never read.
 *
 * The components and the modifiers do not depend on what the types slot holds, and
 * duplicating them per binding is a copy that can drift.
 */
internal fun <A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, ModifierSpec, A, F, T>.plannerVocabulary(): Unit {
    component(
        componentSpec(COLUMN, 1) {
            metadata("Column", Category.Layout)
            property(prop<String>("spacing", TypeRef.Str))
            slot(SlotSpec(CHILDREN, Cardinality.Many))
            // A negative filter is the only way to separate "this slot will not take that"
            // from "this slot is full": both can be true of the same edit, and a refusal test
            // that cannot tell them apart proves nothing.
            slot(SlotSpec(FOOTER, Cardinality.Many, ChildFilter.Except(setOf(TEXT))))
            intrinsic()
        },
    )
    component(
        componentSpec(TEXT, 1) {
            metadata("Text", Category.Basic)
            property(prop<String>("text", TypeRef.Str, required = true))
            event(ON_CLICK.value)
            intrinsic()
        },
    )
    component(
        componentSpec(CARD, 1) {
            metadata("Card", Category.Layout)
            slot(SlotSpec(CONTENT, Cardinality.ZeroOrOne, ChildFilter.Only(setOf(TEXT, IMAGE))))
            intrinsic()
        },
    )
    component(
        componentSpec(IMAGE, 1) {
            metadata("Image", Category.Basic)
            intrinsic()
        },
    )
    component(
        componentSpec(BANNER, 1) {
            metadata("Banner", Category.Layout)
            slot(SlotSpec(TOP_BAR, Cardinality.ExactlyOne, ChildFilter.Only(setOf(TEXT))))
            intrinsic()
        },
    )
}

// ---------------------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------------------

/** A fresh generator. Its first three ids are [MINTED_ONE], [MINTED_TWO], [MINTED_THREE]. */
internal fun freshIds(): IdGenerator = SequentialIdGenerator()

/** A generator that hands out one id forever, for the case where the contract is broken. */
internal fun fixedIds(id: NodeId): IdGenerator = IdGenerator { id }