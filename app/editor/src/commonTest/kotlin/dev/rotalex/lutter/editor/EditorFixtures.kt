package dev.rotalex.lutter.editor

import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec

// The one document and the one schema every test in this module starts from.
//
// Hand-built, as `:engine:editing`'s planner fixtures are, because the alternative — reaching
// for `:engine:test-support` — is an edge `ModuleGraphRules.ALLOWED_IN_TESTS` has no row for,
// and because a generated document would make each assertion a statement about the generator.
// Three components are enough to reach every question this slice answers: a layout with a
// `Many` slot (the palette's `Layout` section and a parent to insert into), a leaf with a
// required string (the ordinary property row), and a leaf with a bounded number (a value the
// *schema* refuses, which is the only way to reach `PropertyWrite.Refused`).

/** A layout with an unbounded slot. */
internal val PANEL: ComponentType = ComponentType("test.Panel")

/** A leaf with a required string and an optional number. */
internal val LABEL: ComponentType = ComponentType("test.Label")

/** Declared by no schema, so the inspector's "cannot describe this node" is reachable. */
internal val MYSTERY: ComponentType = ComponentType("test.Mystery")

internal val CHILDREN: SlotName = SlotName("children")
internal val TEXT: PropertyKey = PropertyKey("text")
internal val WEIGHT: PropertyKey = PropertyKey("weight")

/** The document's one page and the nodes it holds. */
internal val HOME: PageId = PageId("p_home")
internal val ROOT: NodeId = NodeId("n_root")
internal val LEAF: NodeId = NodeId("n_leaf")
internal val ABSENT: NodeId = NodeId("n_absent")

/** What the leaf says before any test edits it. */
internal val HELLO: PropertyValue = PropertyValue.Const(Value.Str("Hello"))

/**
 * A document whose page root is a panel holding one label.
 *
 * `rootType` is a parameter so the "unregistered component" case needs no second fixture: the
 * root is the node every structural edit reads.
 */
internal fun editorDocument(
    rootType: ComponentType = PANEL,
    leaf: Node = Node(id = LEAF, type = LABEL, props = mapOf(TEXT to HELLO)),
): UiDocument {
    val table: NodeTable = NodeTable.EMPTY
        .with(Node(id = ROOT, type = rootType, slots = mapOf(CHILDREN to listOf(LEAF))))
        .with(leaf)
    return UiDocument(
        meta = DocumentMeta("EditorFixture"),
        app = AppSpec("com.example.editor", HOME),
        pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
        components = emptyMap(),
        nodes = table,
    )
}

/** The schema the palette and the inspector read. */
internal fun editorSchema(): Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
        component(
            componentSpec(PANEL, 1) {
                metadata("Panel", Category.Layout, description = "A vertical group.")
                slot(SlotSpec(CHILDREN, Cardinality.Many))
                intrinsic()
            },
        )
        // Registered second and in `Basic`, so the palette's section order (`Basic` before
        // `Layout`) is distinguishable from its registration order.
        component(
            componentSpec(LABEL, 1) {
                metadata("Label", Category.Basic, keywords = listOf("text", "caption"))
                property(prop<String>(TEXT.value, TypeRef.Str, required = true, doc = "The line of text."))
                property(prop<Int>(WEIGHT.value, TypeRef.Int32))
                intrinsic()
            },
        )
    }

/**
 * A [dev.rotalex.lutter.serialization.storage.DocumentStorage] over a map.
 *
 * The storage seam is the point: a session record and a preview can be driven with no filesystem,
 * and the one desktop test that needs real bytes uses `FileDocumentStorage` explicitly.
 */
internal class MapStorage : dev.rotalex.lutter.serialization.storage.DocumentStorage {
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun read(location: String): ByteArray =
        files[location] ?: throw IllegalStateException("no document at '$location'")

    override suspend fun write(location: String, bytes: ByteArray) {
        files[location] = bytes
    }

    /** The bytes written at [location], or null. */
    internal fun bytesAt(location: String): ByteArray? = files[location]
}

/** A session over [editorDocument] and [editorSchema]. */
internal fun session(
    document: UiDocument = editorDocument(),
    schema: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> = editorSchema(),
): EditorSession = EditorSession.open(document, schema)
