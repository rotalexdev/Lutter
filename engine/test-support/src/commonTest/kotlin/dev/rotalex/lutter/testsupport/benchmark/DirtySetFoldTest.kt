package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.editing.patch.PatchOp
import dev.rotalex.lutter.editing.patch.Placement
import dev.rotalex.lutter.editing.patch.RemovedSubtree
import dev.rotalex.lutter.editing.patch.RecordRef
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The caller-side fold §6.2:537 hands the dirty-set factory: the ids a patch names, the
 * placements its removals and moves vacate, and the record signal — folded from real
 * [PatchOp]s, which is the only place that can see both the patch and the model.
 *
 * [dev.rotalex.lutter.model.index.DirtyNodesTest] pins the closure itself; this file pins the
 * bridge in front of it, because a wrong fold produces a wrong *set* for a perfectly correct
 * factory, and nothing downstream could tell the difference.
 */
class DirtySetFoldTest {

    private val children = SlotName("children")
    private val content = SlotName("content")

    private fun column(id: String, vararg children: String) = Node(
        id = NodeId(id),
        type = ComponentType("core.Column"),
        slots = if (children.isEmpty()) {
            emptyMap()
        } else {
            mapOf(SlotName("children") to children.map(::NodeId))
        },
    )

    private val button = Node(
        id = NodeId("n_button"),
        type = ComponentType("m3.Button"),
        slots = mapOf(SlotName("content") to listOf(NodeId("n_label"))),
    )

    private val label = Node(id = NodeId("n_label"), type = ComponentType("m3.Text"))

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
        components = emptyMap(),
        nodes = NodeTable.EMPTY
            .with(column("n_root", "n_button", "n_other"))
            .with(button)
            .with(label)
            .with(column("n_other")),
    )

    @Test
    fun `a property edit dirties the node and the chain above it`() {
        val patch = Patch(
            listOf(
                PatchOp.SetProp(
                    node = NodeId("n_label"),
                    key = PropertyKey("label"),
                    old = PropertyValue.Const(Value.Str("gen")),
                    new = PropertyValue.Const(Value.Str("incremental")),
                ),
            ),
        )
        val dirty = dirtySetFor(patch, PatchApplier.apply(patch, document))

        assertTrue(NodeId("n_label") in dirty, "the edited node re-analyses first")
        assertTrue(NodeId("n_button") in dirty, "the parent embeds the child's resolved result")
        assertTrue(NodeId("n_root") in dirty, "the chain runs to the root")
        assertFalse(NodeId("n_other") in dirty, "an untouched sibling stays reusable")
    }

    @Test
    fun `a removal dirties the parent the subtree left behind`() {
        val patch = Patch(
            listOf(
                PatchOp.RemoveSubtree(
                    root = NodeId("n_button"),
                    captured = RemovedSubtree(
                        parent = NodeId("n_root"),
                        slot = children,
                        index = 0,
                        nodes = listOf(button, label),
                    ),
                    slotExisted = true,
                ),
            ),
        )
        val dirty = dirtySetFor(patch, PatchApplier.apply(patch, document))

        assertTrue(NodeId("n_root") in dirty, "the vacated parent's slot list changed")
        assertFalse(NodeId("n_button") in dirty, "a removed id has nothing left to re-analyse")
        assertFalse(NodeId("n_label") in dirty)
        assertFalse(NodeId("n_other") in dirty, "the surviving sibling stays reusable")
    }

    @Test
    fun `a move dirties the parent it left and the chain it joined`() {
        val patch = Patch(
            listOf(
                PatchOp.MoveNode(
                    id = NodeId("n_label"),
                    from = Placement(parent = NodeId("n_button"), slot = content, index = 0),
                    to = Placement(parent = NodeId("n_root"), slot = children, index = 1),
                ),
            ),
        )
        val dirty = dirtySetFor(patch, PatchApplier.apply(patch, document))

        assertTrue(NodeId("n_label") in dirty, "the moved node re-analyses at its new slot path")
        assertTrue(NodeId("n_button") in dirty, "the vacated parent's slot list changed")
        assertTrue(NodeId("n_root") in dirty, "the joined parent's chain closes over the move")
        assertFalse(NodeId("n_other") in dirty)
    }

    @Test
    fun `the record kinds dirty every node`() {
        val recordKinds = listOf<PatchOp>(
            PatchOp.PutRecord(RecordRef.Theme(ThemeId("t_moved")), null, null),
            PatchOp.SetTheme(old = null, new = ThemeId("t_moved")),
            PatchOp.SetComponentVersion(ComponentType("m3.Text"), null, 2),
        )

        for (op in recordKinds) {
            val dirty = dirtySetFor(Patch(listOf(op)), document)
            assertTrue(
                dirty.isEverything,
                "${op::class.simpleName} changes what every node resolves through, so the fold " +
                    "owes the factory the conservative signal rather than a node set",
            )
        }
    }

    @Test
    fun `a batch mixes node kinds and record kinds into the conservative answer`() {
        val patch = Patch(
            listOf(
                PatchOp.SetProp(
                    node = NodeId("n_label"),
                    key = PropertyKey("label"),
                    old = null,
                    new = PropertyValue.Const(Value.Str("incremental")),
                ),
                PatchOp.PutRecord(RecordRef.Component(ComponentDeclId("hero")), null, null),
            ),
        )

        assertTrue(dirtySetFor(patch, document).isEverything)
    }
}
