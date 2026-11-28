package dev.rotalex.lutter.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.editor.subtreeOf
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.runtime.RenderHooks

/**
 * Where every node on the canvas ended up, as the runtime measured it.
 *
 * ### Why the canvas needs this at all
 *
 * A document is a layout, not a coordinate space: nodes have no `x`/`y`, and the position of a
 * `m3.Text` inside a `core.Column` is decided by Compose at measure time. So "which node did the
 * author tap" and "which container is under the dragged node" are questions only answers about
 * measured boxes can settle — and only the runtime knows them.
 *
 * ### Why it is a hook and not a wrapper
 *
 * `RenderHooks.Decorate` would let us wrap each node, but wrapping inserts a layout node between a
 * container and its child, and a slot child's `Modifier.weight` is parent data the container reads
 * from its own child. The bounds arrive through [onNodeMeasured] instead, appended to each node's
 * own modifier chain, which measures the node's box and leaves the tree alone.
 *
 * ### Why a snapshot map
 *
 * The overlay that draws the selection ring reads these boxes during composition. A plain map
 * would be measured once and drawn stale; a `SnapshotStateMap` invalidates only the overlay when a
 * box moves, which is why an edit that reflows the preview still draws its ring around the node
 * rather than where it used to be.
 */
internal class NodeBounds : RenderHooks {

    /** Node → its box in the composition's root coordinates. Written at measure time. */
    val measured: Map<NodeId, Rect> get() = boxes

    private val boxes = mutableStateMapOf<NodeId, Rect>()

    /**
     * Written only when the box actually changed.
     *
     * `onGloballyPositioned` fires on every layout pass, including passes this overlay caused by
     * recomposing itself. Comparing first is what stops that from being a loop: an equal box
     * writes nothing, so nothing observes a change and no further pass is scheduled.
     */
    override fun onNodeMeasured(id: NodeId, bounds: Rect) {
        if (boxes[id] != bounds) boxes[id] = bounds
    }

    /** The interface's no-op default, restated because this is the only abstract member left. */
    @Composable
    override fun Decorate(node: ResolvedNode, content: @Composable () -> Unit) {
        content()
    }
}

/**
 * The deepest node whose measured box contains [point], or null when nothing does.
 *
 * [point] is in **root coordinates** — the same space [NodeBounds] records — so a caller with a
 * gesture position local to the canvas adds the canvas's own origin first.
 *
 * "Deepest" is answered by descending, not by area: a `core.Row` and its only child can have the
 * same box, and the child is what the author meant. Later siblings win over earlier ones for the
 * same reason Compose draws them on top.
 */
internal fun deepestNodeAt(
    document: UiDocument,
    bounds: Map<NodeId, Rect>,
    point: Offset,
): NodeId? {
    val root: NodeId = document.pages[document.app.startPage]?.root ?: return null

    fun descend(id: NodeId): NodeId? {
        val box: Rect = bounds[id] ?: return null
        if (!box.contains(point)) return null
        val node = document.nodes[id] ?: return id
        var winner: NodeId? = null
        for (children in node.slots.values) {
            for (child in children) {
                descend(child)?.let { winner = it }
            }
        }
        return winner ?: id
    }

    return descend(root)
}

/**
 * The drop target the author is over, or null.
 *
 * [targets] is `EditorSession.dropTargets(type)` — the engine's own answer to "where may this
 * type go" — so this function only decides *which* of them is under the pointer: the deepest
 * container that both contains [point] and offers a target. A container that contains the point
 * but admits nothing is not a drop: descending into its children finds a valid one only if one of
 * them offers it.
 *
 * The index stays the engine's (`DropTarget` appends); a caller that wants a precise index
 * computes one from the child boxes, and that index is then its own claim to check.
 */
internal fun dropTargetAt(
    document: UiDocument,
    bounds: Map<NodeId, Rect>,
    targets: List<DropTarget>,
    point: Offset,
): DropTarget? {
    if (targets.isEmpty()) return null
    val byParent: Map<NodeId, DropTarget> = targets.associateBy { it.parent }
    val root: NodeId = document.pages[document.app.startPage]?.root ?: return null

    fun descend(id: NodeId): DropTarget? {
        val box: Rect = bounds[id] ?: return null
        if (!box.contains(point)) return null
        val node = document.nodes[id] ?: return byParent[id]
        var winner: DropTarget? = null
        for (children in node.slots.values) {
            for (child in children) {
                descend(child)?.let { winner = it }
            }
        }
        return winner ?: byParent[id]
    }

    return descend(root)
}
