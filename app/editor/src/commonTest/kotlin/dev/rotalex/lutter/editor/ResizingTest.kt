package dev.rotalex.lutter.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a handle drag does to a size, as arithmetic.
 *
 * The gesture needs a window; the size change does not. Each case states one promise: the dragged
 * edge moves, the opposite one does not, a left or top grip moves the origin by exactly what it
 * took from the size, the minimum is a floor, and a locked corner keeps its ratio.
 */
class ResizingTest {

    private fun delta(x: Float = 0f, y: Float = 0f) = ViewPoint(x, y)

    @Test
    fun `the right edge grows the width and leaves the origin alone`() {
        val resize = Resizing.apply(width = 100f, height = 40f, handle = Handle.Right, delta = delta(x = 30f))

        assertEquals(130f, resize.width)
        assertEquals(40f, resize.height)
        assertEquals(0f, resize.shiftX)
        assertEquals(0f, resize.shiftY)
    }

    @Test
    fun `the left edge moves the origin by what it took from the width`() {
        val resize = Resizing.apply(width = 100f, height = 40f, handle = Handle.Left, delta = delta(x = -20f))

        assertEquals(120f, resize.width)
        assertEquals(-20f, resize.shiftX)
        assertEquals(0f, resize.shiftY)
    }

    @Test
    fun `the top edge moves the origin on the other axis only`() {
        val resize = Resizing.apply(width = 100f, height = 40f, handle = Handle.Top, delta = delta(y = -10f))

        assertEquals(100f, resize.width)
        assertEquals(50f, resize.height)
        assertEquals(0f, resize.shiftX)
        assertEquals(-10f, resize.shiftY)
    }

    @Test
    fun `a corner moves both edges it owns`() {
        val resize = Resizing.apply(
            width = 100f,
            height = 40f,
            handle = Handle.BottomLeft,
            delta = delta(x = -15f, y = 25f),
        )

        assertEquals(115f, resize.width)
        assertEquals(65f, resize.height)
        assertEquals(-15f, resize.shiftX)
        assertEquals(0f, resize.shiftY)
    }

    @Test
    fun `the minimum is a floor and the origin stops with it`() {
        val resize = Resizing.apply(
            width = 100f,
            height = 40f,
            handle = Handle.Left,
            delta = delta(x = 500f),
        )

        assertEquals(Resizing.Minimum, resize.width)
        assertEquals(100f - Resizing.Minimum, resize.shiftX)
    }

    @Test
    fun `a locked corner keeps the ratio of the axis that moved more`() {
        val resize = Resizing.apply(
            width = 200f,
            height = 100f,
            handle = Handle.BottomRight,
            delta = delta(x = 100f, y = 1f),
            aspectLock = true,
        )

        assertEquals(300f, resize.width)
        assertEquals(150f, resize.height)
    }

    @Test
    fun `a locked edge grip derives the other dimension`() {
        val resize = Resizing.apply(
            width = 200f,
            height = 100f,
            handle = Handle.Right,
            delta = delta(x = 100f),
            aspectLock = true,
        )

        assertEquals(300f, resize.width)
        assertEquals(150f, resize.height)
    }

    @Test
    fun `an unlocked edge grip leaves the other dimension alone`() {
        val resize = Resizing.apply(
            width = 200f,
            height = 100f,
            handle = Handle.Right,
            delta = delta(x = 100f),
        )

        assertEquals(300f, resize.width)
        assertEquals(100f, resize.height)
        assertTrue(resize.shiftX == 0f && resize.shiftY == 0f)
    }
}
