package dev.rotalex.lutter.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a drag is corrected to, as arithmetic.
 *
 * The gesture needs a window; the correction does not. Each test states one of the promises the
 * canvas makes: a neighbour's edge wins, the nearest line wins, each axis decides alone, the grid
 * only fills an axis no line claimed, and a gap is measured to the nearest neighbour that
 * actually shares the dragged box's row or column.
 */
class SnappingTest {

    private fun box(left: Float, top: Float, width: Float = 60f, height: Float = 40f): CanvasBox =
        CanvasBox(left, top, left + width, top + height)

    @Test
    fun `a near edge snaps and draws its guide`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(200f, 100f)),
            delta = ViewPoint(96f, 0f),
        )

        assertEquals(100f, snap.dx)
        assertEquals(0f, snap.dy)
        val vertical = snap.guides.single { it.vertical }
        assertEquals(200f, vertical.at)
        assertEquals(100f, vertical.from)
        assertEquals(140f, vertical.to)
    }

    @Test
    fun `a delta beyond the threshold is left alone`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(200f, 100f)),
            delta = ViewPoint(50f, 0f),
            threshold = 8f,
        )

        assertEquals(50f, snap.dx)
        assertTrue(snap.guides.none { it.vertical }, "a line 50 px away must not claim the axis")
    }

    @Test
    fun `a centre alignment is a candidate like an edge`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(230f, 100f, width = 100f)),
            delta = ViewPoint(147f, 0f),
            threshold = 8f,
        )

        assertEquals(150f, snap.dx)
        assertEquals(280f, snap.guides.single { it.vertical }.at)
    }

    @Test
    fun `each axis snaps on its own`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(200f, 300f)),
            delta = ViewPoint(97f, 40f),
            threshold = 8f,
        )

        assertEquals(100f, snap.dx)
        assertEquals(40f, snap.dy)
        assertEquals(1, snap.guides.size)
        assertTrue(snap.guides.single().vertical)
    }

    @Test
    fun `the grid fills an axis no line claimed`() {
        val snap = Snapping.snap(
            moving = box(101f, 99f),
            others = emptyList(),
            delta = ViewPoint(0f, 0f),
            grid = 8f,
        )

        assertEquals(3f, snap.dx)
        assertEquals(-3f, snap.dy)
    }

    @Test
    fun `a neighbour's edge beats the grid`() {
        val snap = Snapping.snap(
            moving = box(101f, 100f),
            others = listOf(box(103f, 100f)),
            delta = ViewPoint(0f, 0f),
            grid = 8f,
        )

        assertEquals(2f, snap.dx)
        assertEquals(103f, snap.guides.single { it.vertical }.at)
    }

    @Test
    fun `a gap is measured to the nearest neighbour in the same row`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(0f, 100f, width = 80f)),
            delta = ViewPoint(0f, 0f),
        )

        val gap = snap.gaps.single { it.vertical }
        assertEquals(20f, gap.size)
        assertEquals(90f, gap.at)
        assertEquals(120f, gap.along)
    }

    @Test
    fun `a neighbour that shares no row is not measured`() {
        val snap = Snapping.snap(
            moving = box(100f, 100f),
            others = listOf(box(0f, 400f, width = 80f)),
            delta = ViewPoint(0f, 0f),
        )

        assertTrue(snap.gaps.none { it.vertical }, "a box 300 px below has no horizontal gap")
    }
}
