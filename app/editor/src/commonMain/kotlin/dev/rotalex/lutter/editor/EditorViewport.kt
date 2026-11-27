package dev.rotalex.lutter.editor

/**
 * A point in one of the canvas's two coordinate spaces.
 *
 * The canvas has two: the document's own space, where a node's offset is authored, and the
 * screen's, where a pointer arrives. Every question a gesture asks crosses between them, so the
 * arithmetic lives here instead of inside a modifier where it could only be tested through a
 * window.
 */
internal data class ViewPoint(val x: Float, val y: Float)

/**
 * Where the canvas is looking: a zoom and a pan.
 *
 * ### Why a value and not mutable state
 *
 * A gesture computes the next viewport from the current one and commits it once, so a drag that
 * ends in the same place leaves the state equal and nothing recomposes. It also makes the
 * inverse pair testable without a pointer: [screenToCanvas] of [canvasToScreen] is the identity
 * for every point, which is the property a hit test depends on.
 *
 * ### Why translation is in screen units
 *
 * `scale * canvas + translation` is the mapping Compose's `graphicsLayer` applies when the
 * transform origin is the top-left corner, so the viewport needs no conversion at the call site
 * and a pan is a plain addition in the units the pointer reports.
 */
internal data class EditorViewport(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
) {

    init {
        require(scale in MinScale..MaxScale) { "scale $scale is outside $MinScale..$MaxScale" }
    }

    /** The canvas point [point] lands on, in screen units. */
    fun canvasToScreen(point: ViewPoint): ViewPoint =
        ViewPoint(point.x * scale + translationX, point.y * scale + translationY)

    /** The canvas point under [point], in the document's own units. */
    fun screenToCanvas(point: ViewPoint): ViewPoint =
        ViewPoint((point.x - translationX) / scale, (point.y - translationY) / scale)

    /** The same zoom, moved by a drag delta in screen units. */
    fun panned(byX: Float, byY: Float): EditorViewport =
        copy(translationX = translationX + byX, translationY = translationY + byY)

    /**
     * A new zoom that keeps the canvas point under [anchor] under it.
     *
     * Clamped rather than refused: a wheel at either limit keeps the view where it is, because
     * zooming past a limit and back would otherwise move the document for no reason.
     */
    fun zoomedAt(anchor: ViewPoint, factor: Float): EditorViewport {
        val target = (scale * factor).coerceIn(MinScale, MaxScale)
        if (target == scale) return this
        val fixed = screenToCanvas(anchor)
        return EditorViewport(
            scale = target,
            translationX = anchor.x - fixed.x * target,
            translationY = anchor.y - fixed.y * target,
        )
    }

    companion object {
        /** A quarter size, far enough out to see a whole page on a phone. */
        const val MinScale = 0.25f

        /** Four times, enough to place a spacer to the pixel. */
        const val MaxScale = 4f

        /** One wheel notch, about ten percent. */
        const val Step = 1.1f
    }
}
