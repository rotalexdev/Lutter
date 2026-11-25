package dev.rotalex.lutter.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.rotalex.lutter.editor.ui.deepestNodeAt
import dev.rotalex.lutter.editor.ui.dropTargetAt
import dev.rotalex.lutter.editor.ui.subtreeOf
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the canvas's two hit tests answer, as values.
 *
 * The gesture itself needs a window, and this module has no Compose UI test infrastructure — but
 * neither of the questions the gesture asks is about a window: "which box contains this point"
 * and "which legal home is under it" are arithmetic over measured boxes. Keeping them here means
 * the answers are asserted without Robolectric, and the composable is left with the wiring.
 */
class CanvasHitTestTest {

    // root  ────────── 0..400 × 0..400
    //   label           0..400 × 0..100
    //   inner           0..400 × 100..400
    //     deep          0..200 × 200..300
    private val inner: NodeId = NodeId("n_inner")
    private val deep: NodeId = NodeId("n_deep")

    private val document: UiDocument = UiDocument(
        meta = DocumentMeta("CanvasFixture"),
        app = AppSpec("com.example.editor", HOME),
        pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
        components = emptyMap(),
        nodes = NodeTable.EMPTY
            .with(Node(id = ROOT, type = PANEL, slots = mapOf(CHILDREN to listOf(LEAF, inner))))
            .with(Node(id = LEAF, type = LABEL))
            .with(Node(id = inner, type = PANEL, slots = mapOf(CHILDREN to listOf(deep))))
            .with(Node(id = deep, type = LABEL)),
    )

    private val boxes: Map<NodeId, Rect> = mapOf(
        ROOT to Rect(0f, 0f, 400f, 400f),
        LEAF to Rect(0f, 0f, 400f, 100f),
        inner to Rect(0f, 100f, 400f, 400f),
        deep to Rect(0f, 200f, 200f, 300f),
    )

    @Test
    fun `a leaf under the point wins over the panel around it`() {
        assertEquals(LEAF, deepestNodeAt(document, boxes, Offset(10f, 10f)))
    }

    @Test
    fun `the deepest slot child wins, not the outermost`() {
        // Inside `inner`, inside `deep`: the author meant the label, not its container.
        assertEquals(deep, deepestNodeAt(document, boxes, Offset(10f, 250f)))
    }

    @Test
    fun `a panel whose child is elsewhere is the hit`() {
        // Inside `inner` but right of `deep`, which stops at x = 200.
        assertEquals(inner, deepestNodeAt(document, boxes, Offset(300f, 250f)))
    }

    @Test
    fun `a point outside every box hits nothing`() {
        assertNull(deepestNodeAt(document, boxes, Offset(500f, 500f)))
    }

    @Test
    fun `an unmeasured node is skipped, not guessed at the origin`() {
        // The overlay is composed before the preview's first layout, so "no box yet" is a real
        // state. It must mean "not this node", never "the node at (0, 0)": the point falls
        // through to the measured container around it.
        assertEquals(inner, deepestNodeAt(document, boxes - deep, Offset(10f, 250f)))
    }

    @Test
    fun `a point inside the root but in no child is the root`() {
        val empty = NodeTable.EMPTY.with(Node(id = ROOT, type = PANEL))
        val bare = document.copy(nodes = empty)

        assertEquals(ROOT, deepestNodeAt(bare, boxes, Offset(10f, 250f)))
    }

    @Test
    fun `the drop target is the deepest container that offers one`() {
        val targets = listOf(
            DropTarget(parent = ROOT, slot = CHILDREN, index = 0),
            DropTarget(parent = inner, slot = CHILDREN, index = 0),
        )

        assertEquals(inner, dropTargetAt(document, boxes, targets, Offset(10f, 250f))?.parent)
    }

    @Test
    fun `a leaf that offers nothing passes the drop up to its container`() {
        val targets = listOf(DropTarget(parent = ROOT, slot = CHILDREN, index = 0))

        assertEquals(ROOT, dropTargetAt(document, boxes, targets, Offset(10f, 10f))?.parent)
    }

    @Test
    fun `a container that offers nothing is not a drop`() {
        val targets = listOf(DropTarget(parent = deep, slot = CHILDREN, index = 0))

        // `deep` cannot receive one, and nothing above it offers a target either.
        assertNull(dropTargetAt(document, boxes, targets, Offset(300f, 250f)))
    }

    @Test
    fun `no legal target means no drop`() {
        assertNull(dropTargetAt(document, boxes, emptyList(), Offset(10f, 10f)))
    }

    @Test
    fun `a dragged node may not land in its own subtree`() {
        assertEquals(setOf(ROOT, LEAF, inner, deep), subtreeOf(document, ROOT))
        assertEquals(setOf(inner, deep), subtreeOf(document, inner))
        assertEquals(setOf(deep), subtreeOf(document, deep))
    }
}
