package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `DocumentIndex` contract: parent, owner, path, ancestors and descendants.
 *
 * Two claims are worth more than the queries themselves. **A malformed document must not hang
 * the index** — §6.2's invariants are §17.1's structural pass to report, the model checks
 * nothing it can only see with a schema, and an index that walked a cycle naively would never
 * return. And **`pathTo` includes the node**, because §6.2:514 forms the *dirty set* for
 * incremental re-analysis out of `touchedNodes` and this index, and a node that changed is in
 * its own dirty set.
 *
 * The document below is deliberately malformed in two ways, and both have to be answered rather
 * than avoided: a node two parents list, and a cycle.
 */
class DocumentIndexTest {

    private fun column(id: String, vararg children: String) = Node(
        id = NodeId(id),
        type = ComponentType("core.Column"),
        slots = if (children.isEmpty()) {
            emptyMap()
        } else {
            mapOf(SlotName("children") to children.map(::NodeId))
        },
    )

    private val label = Node(id = NodeId("n_label"), type = ComponentType("m3.Text"))

    private val button = Node(
        id = NodeId("n_button"),
        type = ComponentType("m3.Button"),
        slots = mapOf(SlotName("content") to listOf(NodeId("n_label"))),
    )

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(
                id = PageId("p_home"),
                name = "HomeScreen",
                route = "/home",
                params = listOf(ParamDecl(ParamName("q"), TypeRef.Str)),
                root = NodeId("n_root"),
            ),
        ),
        components = mapOf(
            ComponentDeclId("c_card") to ComponentDecl(
                id = ComponentDeclId("c_card"),
                name = "Card",
                params = emptyList(),
                slots = emptyList(),
                root = NodeId("n_card"),
            ),
        ),
        nodes = NodeTable.EMPTY
            .with(column("n_root", "n_button"))
            .with(button)
            .with(label)
            .with(column("n_card")),
    )

    private val index = DocumentIndex(document)

    @Test
    fun `parentOf answers the parent, the slot and the position`() {
        // All three are needed: the parent alone is §5.6's reason a parent index has to exist,
        // and the slot and index are what an editor needs to know *where* a node is. The index
        // is the child's position in its parent's slot, counted from zero, because a sibling can
        // move and the index is what a removal means.
        assertEquals(
            ParentRef(NodeId("n_root"), SlotName("children"), 0),
            index.parentOf(NodeId("n_button")),
        )
        assertEquals(
            ParentRef(NodeId("n_button"), SlotName("content"), 0),
            index.parentOf(NodeId("n_label")),
        )
        assertNull(index.parentOf(NodeId("n_root")), "a root has no parent")
        assertNull(index.parentOf(NodeId("n_absent")), "an id nothing holds has no parent")
    }

    @Test
    fun `ownerOf answers the page or the component, by tag`() {
        // §5.6's two cases. The tags are read back out of encoded JSON rather than off the
        // annotation: a hierarchy that is `@Serializable` without tags falls back to class names
        // the first time something does persist it.
        assertEquals(NodeOwner.Page(PageId("p_home")), index.ownerOf(NodeId("n_root")))
        assertEquals(NodeOwner.Page(PageId("p_home")), index.ownerOf(NodeId("n_label")))
        assertEquals(NodeOwner.Component(ComponentDeclId("c_card")), index.ownerOf(NodeId("n_card")))
        // A node no root reaches — the table is global, so this is the ordinary case for a node
        // an editor has just created and not yet attached.
        assertNull(index.ownerOf(NodeId("n_absent")))

        val text = Json.encodeToString<NodeOwner>(NodeOwner.Component(ComponentDeclId("c_card")))
        assertEquals("""{"type":"component","id":"c_card"}""", text)
        assertEquals("component", Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            NodeOwner.Component(ComponentDeclId("c_card")),
            Json.decodeFromString<NodeOwner>(text),
        )
        assertIs<NodeOwner.Component>(Json.decodeFromString<NodeOwner>(text))

        val pageText = Json.encodeToString<NodeOwner>(NodeOwner.Page(PageId("p_home")))
        assertEquals("""{"type":"page","id":"p_home"}""", pageText)
        assertEquals(NodeOwner.Page(PageId("p_home")), Json.decodeFromString<NodeOwner>(pageText))

        // The union is closed, and a tag it does not have does not decode.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NodeOwner>("""{"type":"theme","id":"t_dark"}""")
        }
    }

    @Test
    fun `pathTo runs root first and includes the node itself`() {
        assertEquals(
            listOf(NodeId("n_root"), NodeId("n_button"), NodeId("n_label")),
            index.pathTo(NodeId("n_label")),
        )
        assertEquals(listOf(NodeId("n_root"), NodeId("n_button")), index.pathTo(NodeId("n_button")))
        // A root is its own path, which is what makes the union below a set rather than a list
        // of paths: a touched root contributes exactly itself.
        assertEquals(listOf(NodeId("n_root")), index.pathTo(NodeId("n_root")))
        // A node no parent lists is a one-element path, and §6.2's "exactly one parent"
        // invariant is §17.1's to report.
        assertEquals(listOf(NodeId("n_card")), index.pathTo(NodeId("n_card")))
        assertEquals(listOf(NodeId("n_absent")), index.pathTo(NodeId("n_absent")))
    }

    @Test
    fun `ancestorsOf takes a batch and returns membership`() {
        // §6.2:514 forms the dirty set out of `Patch.touchedNodes` and this, and §26.2 already
        // types `touchedNodes` as a Set. Folding `pathTo` per id at the call site gives a List
        // with duplicates and the deduplication written once per call site; this does it once.
        assertEquals(
            setOf(NodeId("n_root"), NodeId("n_button"), NodeId("n_label")),
            index.ancestorsOf(listOf(NodeId("n_button"), NodeId("n_label"))),
        )
        // One node's path is already a set, and the two touched nodes share an ancestor, so the
        // result is smaller than the sum of the two paths.
        assertEquals(
            setOf(NodeId("n_root"), NodeId("n_label")),
            index.ancestorsOf(listOf(NodeId("n_label"))),
        )
        assertEquals(setOf<NodeId>(), index.ancestorsOf(emptyList()))
        assertEquals(setOf<NodeId>(), index.ancestorsOf(listOf(NodeId("n_absent"))))
        // Two roots in one document do not bleed into each other.
        assertEquals(setOf(NodeId("n_card")), index.ancestorsOf(listOf(NodeId("n_card"))))
    }

    @Test
    fun `descendants is depth first, excludes the node asked about, and terminates`() {
        assertEquals(
            listOf(NodeId("n_button"), NodeId("n_label")),
            index.descendants(NodeId("n_root")).toList(),
        )
        assertEquals(listOf(NodeId("n_label")), index.descendants(NodeId("n_button")).toList())
        assertEquals(emptyList<NodeId>(), index.descendants(NodeId("n_label")).toList())
        assertEquals(emptyList<NodeId>(), index.descendants(NodeId("n_absent")).toList())
    }

    @Test
    fun `a cycle in the document does not hang the index`() {
        // `n_loop` lists itself. §6.2's "no cycles" is §17.1's to report with a nodeId; what is
        // this class's obligation is that answering a question about it terminates, so every
        // walk carries a visited set. A naive walk here is an editor that stops responding
        // rather than a diagnostic.
        val looped = NodeTable.EMPTY
            .with(column("n_loop", "n_leaf", "n_loop"))
            .with(Node(id = NodeId("n_leaf"), type = ComponentType("m3.Text")))
        val loopedIndex = DocumentIndex(
            document.copy(
                nodes = looped,
                pages = mapOf(
                    PageId("p_home") to Page(
                        PageId("p_home"),
                        "HomeScreen",
                        "/home",
                        root = NodeId("n_loop"),
                    ),
                ),
            ),
        )

        assertEquals(listOf(NodeId("n_loop"), NodeId("n_leaf")), loopedIndex.pathTo(NodeId("n_leaf")))
        assertEquals(
            listOf(NodeId("n_leaf")),
            loopedIndex.descendants(NodeId("n_loop")).toList(),
        )
        assertEquals(NodeOwner.Page(PageId("p_home")), loopedIndex.ownerOf(NodeId("n_leaf")))
    }

    @Test
    fun `a node two parents list is read from one of them, deterministically`() {
        // A §6.2 violation — "exactly one parent" — that the model does not check. The parent
        // index is read in ascending parent id, so the answer does not depend on the order the
        // table happened to be built in; which of the two wins is arbitrary and only matters
        // for a document that is already wrong.
        val shared = NodeTable.EMPTY
            .with(column("n_b", "n_shared"))
            .with(column("n_a", "n_shared"))
            .with(Node(id = NodeId("n_shared"), type = ComponentType("m3.Text")))
        val sharedIndex = DocumentIndex(document.copy(nodes = shared))

        assertEquals(ParentRef(NodeId("n_a"), SlotName("children"), 0), sharedIndex.parentOf(NodeId("n_shared")))

        val rebuilt = DocumentIndex(
            document.copy(
                nodes = NodeTable.EMPTY
                    .with(column("n_a", "n_shared"))
                    .with(column("n_b", "n_shared"))
                    .with(Node(id = NodeId("n_shared"), type = ComponentType("m3.Text"))),
            ),
        )
        assertEquals(sharedIndex.parentOf(NodeId("n_shared")), rebuilt.parentOf(NodeId("n_shared")))
    }

    @Test
    fun `a ParentRef is plain data and is not part of the document`() {
        // §5.6's preamble says these structures are never persisted and §35 lists persisting
        // derived data as an anti-pattern, so `ParentRef` carries no `@Serializable` and no
        // `@SerialName` — plain data with no hierarchy, so there is no tag to state.
        val ref = ParentRef(NodeId("n_root"), SlotName("children"), 0)

        assertEquals(ref, ref.copy())
        assertEquals(ref.hashCode(), ref.copy().hashCode())
        assertEquals(
            "ParentRef(parentId=n_root, slot=children, index=0)",
            ref.toString(),
            "the generated toString moved, or an id stopped reading as itself",
        )
        assertEquals(1, ref.index)
    }
}
