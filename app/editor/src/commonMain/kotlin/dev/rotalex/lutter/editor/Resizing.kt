package dev.rotalex.lutter.editor

import kotlin.math.abs
import kotlin.math.max

/**
 * Which of the eight grips a resize drag took.
 *
 * [left], [right], [top] and [bottom] say which edges the grip moves, so the arithmetic below is
 * written once instead of eight times: a corner moves two edges, an edge grip moves one, and the
 * opposite edge never moves — which is the whole reason the offset has to shift.
 */
internal enum class Handle(
    val tag: String,
    val left: Boolean = false,
    val right: Boolean = false,
    val top: Boolean = false,
    val bottom: Boolean = false,
) {
    TopLeft("top-left", left = true, top = true),
    Top("top", top = true),
    TopRight("top-right", right = true, top = true),
    Right("right", right = true),
    BottomRight("bottom-right", right = true, bottom = true),
    Bottom("bottom", bottom = true),
    BottomLeft("bottom-left", left = true, bottom = true),
    Left("left", left = true),
}

/** What a resize produces: a new size, and how far the box's origin has to move to keep still. */
internal data class Resize(
    val width: Float,
    val height: Float,
    val shiftX: Float,
    val shiftY: Float,
)

/**
 * How a handle drag changes a box.
 *
 * ### Why the shift exists
 *
 * Resizing from the right edge changes the width and nothing else. Resizing from the left edge
 * changes the width *and* moves the origin, because the right edge is what stays put — and a box
 * whose origin did not move would appear to grow in the wrong direction. The shift is expressed
 * as a delta so the caller adds it to whatever offset the node already had, which is what makes a
 * second resize compose with the first.
 *
 * ### Why everything is in dp
 *
 * The document stores dp, the inspector shows dp, and a zoomed canvas measures pixels. Converting
 * once, at the edge of the gesture, keeps this arithmetic independent of both the zoom and the
 * density, which is also what makes it testable without a window.
 *
 * ### Aspect lock and the minimum
 *
 * A locked corner keeps the ratio by letting the axis the person moved more decide, and a locked
 * edge grip derives the other dimension from it. The minimum is applied last and is a hard floor:
 * a box dragged smaller than it stops at it rather than inverting, because an inverted box is a
 * size the document would store and no renderer could show.
 */
internal object Resizing {

    /** The smallest side a resize may produce, in dp. */
    const val Minimum = 8f

    fun apply(
        width: Float,
        height: Float,
        handle: Handle,
        delta: ViewPoint,
        aspectLock: Boolean = false,
        minimum: Float = Minimum,
    ): Resize {
        val ratio: Float = if (height > 0f) width / height else 1f

        var w: Float = width
        var h: Float = height
        if (handle.right) w += delta.x
        if (handle.left) w -= delta.x
        if (handle.bottom) h += delta.y
        if (handle.top) h -= delta.y

        val fromWidth: Boolean = handle.left || handle.right
        val fromHeight: Boolean = handle.top || handle.bottom

        if (aspectLock) {
            when {
                fromWidth && fromHeight -> {
                    if (abs(delta.x) >= abs(delta.y * ratio)) {
                        w = max(w, minimum)
                        h = w / ratio
                    } else {
                        h = max(h, minimum)
                        w = h * ratio
                    }
                }
                fromWidth -> {
                    w = max(w, minimum)
                    h = w / ratio
                }
                fromHeight -> {
                    h = max(h, minimum)
                    w = h * ratio
                }
                else -> {
                    w = max(w, minimum)
                    h = max(h, minimum)
                }
            }
        } else {
            w = max(w, minimum)
            h = max(h, minimum)
        }

        return Resize(
            width = w,
            height = h,
            shiftX = if (handle.left) width - w else 0f,
            shiftY = if (handle.top) height - h else 0f,
        )
    }
}
