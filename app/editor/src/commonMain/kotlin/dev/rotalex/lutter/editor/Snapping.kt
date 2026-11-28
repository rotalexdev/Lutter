package dev.rotalex.lutter.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * A box in canvas screen coordinates.
 *
 * The canvas measures in Compose's `Rect`, and this is the same four numbers without the Compose
 * type: the snap arithmetic is asked to be testable without a window, and a `Rect` cannot be
 * built in this module's tests without pulling the UI toolkit into the assertion.
 */
internal data class CanvasBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val centerX: Float get() = (left + right) / 2f

    val centerY: Float get() = (top + bottom) / 2f

    val width: Float get() = right - left

    val height: Float get() = bottom - top

    fun movedBy(dx: Float, dy: Float): CanvasBox = CanvasBox(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * One alignment line to draw.
 *
 * [vertical] names the line's own direction, so a vertical guide pins an x and spans y — the
 * convention a person reads off a drawing.
 */
internal data class Guide(
    val vertical: Boolean,
    val at: Float,
    val from: Float,
    val to: Float,
)

/**
 * The distance between two neighbouring boxes.
 *
 * [vertical] is the measurement's direction: a vertical measurement runs between a left and a
 * right neighbour, so it pins an x at [at] and places its label at [along] on that line. The
 * second coordinate is what lets the canvas draw the number beside the gap instead of guessing
 * a centre.
 */
internal data class Gap(
    val vertical: Boolean,
    val at: Float,
    val along: Float,
    val size: Float,
)

/** What a drag should become: the delta after snapping, and the guides and gaps to draw. */
internal data class Snap(
    val dx: Float,
    val dy: Float,
    val guides: List<Guide> = emptyList(),
    val gaps: List<Gap> = emptyList(),
)

/**
 * Where a dragged box wants to land, given the boxes already on the page.
 *
 * ### Three anchors per axis, and the nearest wins
 *
 * A box can align by its left, its centre or its right — and the same three on the other box —
 * so nine pairings per axis are candidates. The nearest one within [threshold] wins, per axis
 * independently: a drag that lines up horizontally and not vertically keeps the horizontal snap
 * and moves freely on the other axis, which is what makes a two-axis drag feel controlled
 * instead of sticky.
 *
 * ### The grid is a fallback, not a competitor
 *
 * A neighbour's edge beats the grid, because the person is aiming at the neighbour. The grid
 * decides only an axis no line claimed, and it snaps the leading edge rather than the centre,
 * which is the convention a grid of squares implies.
 *
 * ### Thresholds are in screen pixels
 *
 * The caller converts its dp threshold with its own density, so a zoomed-in canvas is not
 * stickier than a zoomed-out one: the same physical distance is the same snap.
 */
internal object Snapping {

    /** How close, in screen pixels, an anchor has to come before it snaps. */
    const val Threshold = 8f

    /** The default grid step, in screen pixels, used when a grid is asked for. */
    const val Grid = 8f

    /**
     * The drag [delta] for [moving], corrected to the nearest alignment.
     *
     * [others] are the boxes the drag may align to — the page's other nodes, in the same
     * coordinate space as [moving] — and [grid] is the fallback step, or `null` for no grid.
     */
    fun snap(
        moving: CanvasBox,
        others: List<CanvasBox>,
        delta: ViewPoint,
        threshold: Float = Threshold,
        grid: Float? = null,
    ): Snap {
        val dragged = moving.movedBy(delta.x, delta.y)

        val x = nearest(
            anchors = listOf(dragged.left, dragged.centerX, dragged.right),
            candidates = others.flatMap { other ->
                listOf(other.left, other.centerX, other.right).map { it to other }
            },
            threshold = threshold,
        )
        val y = nearest(
            anchors = listOf(dragged.top, dragged.centerY, dragged.bottom),
            candidates = others.flatMap { other ->
                listOf(other.top, other.centerY, other.bottom).map { it to other }
            },
            threshold = threshold,
        )

        val dx = delta.x + (x?.correction ?: gridCorrection(dragged.left, grid))
        val dy = delta.y + (y?.correction ?: gridCorrection(dragged.top, grid))

        val placed = moving.movedBy(dx, dy)
        val guides = buildList {
            if (x != null) {
                add(
                    Guide(
                        vertical = true,
                        at = x.at,
                        from = min(placed.top, x.partner.top),
                        to = max(placed.bottom, x.partner.bottom),
                    ),
                )
            }
            if (y != null) {
                add(
                    Guide(
                        vertical = false,
                        at = y.at,
                        from = min(placed.left, y.partner.left),
                        to = max(placed.right, y.partner.right),
                    ),
                )
            }
        }

        return Snap(dx = dx, dy = dy, guides = guides, gaps = gaps(placed, others))
    }

    /** One candidate line, the distance that would reach it, and the box it belongs to. */
    private class Match(val correction: Float, val at: Float, val partner: CanvasBox)

    private fun nearest(
        anchors: List<Float>,
        candidates: List<Pair<Float, CanvasBox>>,
        threshold: Float,
    ): Match? {
        var best: Match? = null
        for ((line, partner) in candidates) {
            for (anchor in anchors) {
                val correction = line - anchor
                if (abs(correction) > threshold) continue
                val current = best
                if (current == null || abs(correction) < abs(current.correction)) {
                    best = Match(correction, line, partner)
                }
            }
        }
        return best
    }

    /** How far the leading edge has to move to sit on the grid, or zero when there is no grid. */
    private fun gridCorrection(edge: Float, grid: Float?): Float {
        if (grid == null || grid <= 0f) return 0f
        return round(edge / grid) * grid - edge
    }

    /**
     * The nearest neighbour on each side, and the distance to it.
     *
     * Only neighbours that overlap the dragged box on the other axis count: a box far above the
     * page has no horizontal gap with the dragged one, and labelling that distance would be a
     * measurement of nothing.
     */
    private fun gaps(placed: CanvasBox, others: List<CanvasBox>): List<Gap> = buildList {
        val left = others
            .filter { it.right <= placed.left && it.bottom > placed.top && it.top < placed.bottom }
            .maxByOrNull { it.right }
        val right = others
            .filter { it.left >= placed.right && it.bottom > placed.top && it.top < placed.bottom }
            .minByOrNull { it.left }
        val above = others
            .filter { it.bottom <= placed.top && it.right > placed.left && it.left < placed.right }
            .maxByOrNull { it.bottom }
        val below = others
            .filter { it.top >= placed.bottom && it.right > placed.left && it.left < placed.right }
            .minByOrNull { it.top }

        left?.let {
            add(
                Gap(
                    vertical = true,
                    at = (it.right + placed.left) / 2f,
                    along = overlapCenter(it.top, it.bottom, placed.top, placed.bottom),
                    size = placed.left - it.right,
                ),
            )
        }
        right?.let {
            add(
                Gap(
                    vertical = true,
                    at = (placed.right + it.left) / 2f,
                    along = overlapCenter(it.top, it.bottom, placed.top, placed.bottom),
                    size = it.left - placed.right,
                ),
            )
        }
        above?.let {
            add(
                Gap(
                    vertical = false,
                    at = (it.bottom + placed.top) / 2f,
                    along = overlapCenter(it.left, it.right, placed.left, placed.right),
                    size = placed.top - it.bottom,
                ),
            )
        }
        below?.let {
            add(
                Gap(
                    vertical = false,
                    at = (placed.bottom + it.top) / 2f,
                    along = overlapCenter(it.left, it.right, placed.left, placed.right),
                    size = it.top - placed.bottom,
                ),
            )
        }
    }

    private fun overlapCenter(fromA: Float, toA: Float, fromB: Float, toB: Float): Float =
        (max(fromA, fromB) + min(toA, toB)) / 2f
}
