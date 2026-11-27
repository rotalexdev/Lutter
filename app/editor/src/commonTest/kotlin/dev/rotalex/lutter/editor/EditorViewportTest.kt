package dev.rotalex.lutter.editor

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the canvas's coordinate mapping answers, as arithmetic.
 *
 * A gesture needs a window; the mapping does not. The properties pinned here are the ones a
 * gesture leans on: the inverse pair is exact, a zoom holds its anchor still, and a clamped zoom
 * leaves the viewport untouched instead of drifting.
 */
class EditorViewportTest {

    private fun assertPoint(expected: ViewPoint, actual: ViewPoint, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun `the identity viewport maps a point to itself`() {
        val viewport = EditorViewport()
        assertPoint(ViewPoint(12f, 34f), viewport.canvasToScreen(ViewPoint(12f, 34f)))
        assertPoint(ViewPoint(12f, 34f), viewport.screenToCanvas(ViewPoint(12f, 34f)))
    }

    @Test
    fun `screen to canvas undoes canvas to screen at every zoom`() {
        for (scale in listOf(0.25f, 0.5f, 1f, 1.75f, 4f)) {
            val viewport = EditorViewport(scale = scale, translationX = -120f, translationY = 64f)
            val canvas = ViewPoint(318f, -42f)
            assertPoint(canvas, viewport.screenToCanvas(viewport.canvasToScreen(canvas)))
        }
    }

    @Test
    fun `a zoom keeps the canvas point under the anchor`() {
        val viewport = EditorViewport(scale = 1.5f, translationX = 40f, translationY = -16f)
        val anchor = ViewPoint(220f, 180f)
        val before = viewport.screenToCanvas(anchor)

        val zoomed = viewport.zoomedAt(anchor, EditorViewport.Step)

        assertPoint(before, zoomed.screenToCanvas(anchor))
        assertEquals(1.5f * EditorViewport.Step, zoomed.scale, 1e-4f)
    }

    @Test
    fun `a zoom out keeps the canvas point under the anchor too`() {
        val viewport = EditorViewport(scale = 2f, translationX = -300f, translationY = 90f)
        val anchor = ViewPoint(96f, 512f)
        val before = viewport.screenToCanvas(anchor)

        val zoomed = viewport.zoomedAt(anchor, 1f / EditorViewport.Step)

        assertPoint(before, zoomed.screenToCanvas(anchor))
    }

    @Test
    fun `a zoom past a limit keeps the viewport unchanged`() {
        val atMaximum = EditorViewport(scale = EditorViewport.MaxScale)
        assertSame(atMaximum, atMaximum.zoomedAt(ViewPoint(10f, 10f), EditorViewport.Step))

        val atMinimum = EditorViewport(scale = EditorViewport.MinScale)
        assertSame(atMinimum, atMinimum.zoomedAt(ViewPoint(10f, 10f), 0.5f))
    }

    @Test
    fun `a zoom that reaches a limit stops exactly there`() {
        val viewport = EditorViewport(scale = EditorViewport.MaxScale / 1.5f)
        val zoomed = viewport.zoomedAt(ViewPoint(0f, 0f), 2f)
        assertEquals(EditorViewport.MaxScale, zoomed.scale)
    }

    @Test
    fun `a pan adds in screen units and leaves the zoom alone`() {
        val viewport = EditorViewport(scale = 2f, translationX = 10f, translationY = 20f)

        val panned = viewport.panned(byX = -4f, byY = 6f)

        assertEquals(EditorViewport(scale = 2f, translationX = 6f, translationY = 26f), panned)
    }

    @Test
    fun `a scale outside the range cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { EditorViewport(scale = 0f) }
        assertFailsWith<IllegalArgumentException> { EditorViewport(scale = 8f) }
    }
}
