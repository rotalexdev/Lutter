package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SlotName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §6.2:537's dirty set: the touched ids closed over their ancestors, and the record edit's
 * conservative answer.
 *
 * The document is [DocumentIndexTest]'s shape minus the declarations the closure does not read:
 * one root holding two children, the first of which holds a leaf, so a touched leaf has a chain
 * to close over and a sibling that has to stay out of the set.
 */
class DirtyNodesTest {

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
                root = NodeId("n_root"),
            ),
        ),
        nodes = NodeTable.EMPTY
            .with(column("n_root", "n_button", "n_other"))
            .with(button)
            .with(label)
            .with(column("n_other")),
    )

    private val index = DocumentIndex(document)

    /**
     * The same document with the button subtree removed — the shape a removal leaves behind,
     * whose touched ids the document no longer holds.
     */
    private val withoutButton = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(
                id = PageId("p_home"),
                name = "HomeScreen",
                route = "/home",
                root = NodeId("n_root"),
            ),
        ),
        nodes = NodeTable.EMPTY
            .with(column("n_root", "n_other"))
            .with(column("n_other")),
    )

    @Test
    fun `a touched leaf closes over its ancestors and leaves a sibling out`() {
        val dirty = DirtyNodes.of(setOf(NodeId("n_label")), index)

        assertTrue(NodeId("n_label") in dirty, "a changed node is the first thing re-analysed")
        assertTrue(NodeId("n_button") in dirty, "the parent embeds the child's resolved result")
        assertTrue(NodeId("n_root") in dirty, "the chain runs all the way to the root")
        assertFalse(NodeId("n_other") in dirty, "an untouched sibling is outside the set")
    }

    @Test
    fun `a touched parent does not dirty its subtree`() {
        val dirty = DirtyNodes.of(setOf(NodeId("n_button")), index)

        assertTrue(NodeId("n_button") in dirty)
        assertTrue(NodeId("n_root") in dirty)
        assertFalse(NodeId("n_label") in dirty, "a leaf's resolution reads nothing of its parent's")
    }

    @Test
    fun `an id no node holds contributes nothing`() {
        // The ordinary shape of a removal: the ops name the id, the document no longer holds it,
        // and there is nothing left to re-analyse. This is `ancestorsOf`'s own rule surfacing
        // through the factory.
        val dirty = DirtyNodes.of(setOf(NodeId("n_absent")), index)

        assertFalse(NodeId("n_absent") in dirty)
        assertFalse(NodeId("n_root") in dirty)
    }

    @Test
    fun `a removal dirties the parent through the vacated placement the caller folds in`() {
        // The index is over the document as the edits left it, so the removed ids contribute
        // nothing and the parent they were lifted from is dirty only because the caller put its
        // id in the touched set. That fold is the factory's documented seam contract, and this
        // is the closure the folded set buys: the parent whose slot list changed re-analyses,
        // the surviving sibling does not, and nothing names a node the document no longer holds.
        val dirty = DirtyNodes.of(setOf(NodeId("n_root")), DocumentIndex(withoutButton))

        assertTrue(NodeId("n_root") in dirty)
        assertFalse(NodeId("n_other") in dirty)
        assertFalse(NodeId("n_button") in dirty)
        assertFalse(NodeId("n_label") in dirty)
    }

    @Test
    fun `a batch of edits that names nothing dirties nothing`() {
        val dirty = DirtyNodes.of(emptySet(), index)

        for (id in listOf("n_root", "n_button", "n_label", "n_other")) {
            assertFalse(NodeId(id) in dirty)
        }
    }

    @Test
    fun `a record edit dirties every node`() {
        val dirty = DirtyNodes.of(emptySet(), index, recordsAffectAllNodes = true)

        assertTrue(dirty.isEverything)
        for (id in listOf("n_root", "n_button", "n_label", "n_other")) {
            assertTrue(NodeId(id) in dirty)
        }
    }

    @Test
    fun `the record signal answers everything without consulting the touched ids`() {
        val dirty = DirtyNodes.of(setOf(NodeId("n_label")), index, recordsAffectAllNodes = true)

        assertTrue(dirty.isEverything)
        assertTrue(NodeId("n_other") in dirty)
    }
}
