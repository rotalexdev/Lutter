package dev.rotalex.lutter.editor

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two closed forms, checked against what they claim rather than against a recording.
 *
 * Every case here is a property of the motion: where it starts, which way it goes, what it
 * approaches, and whether it overshoots. A frame loop that integrated velocity would pass none of
 * these on both hosts at once, which is the reason they are functions of time.
 */
class PhysicsTest {

    private val tolerance: Float = 0.001f

    @Test
    fun `decay starts at zero displacement`() {
        assertEquals(0f, Physics.decay(velocity = 900f, seconds = 0f), tolerance)
    }

    @Test
    fun `decay approaches the distance the friction allows and never passes it`() {
        val limit: Float = 900f / Physics.Friction

        val far: Float = Physics.decay(velocity = 900f, seconds = 2f)

        assertEquals(limit, far, 0.05f)
        assertTrue(far < limit)
    }

    @Test
    fun `decay covers half the distance at one half-life`() {
        val halfLife: Float = ln(2f) / Physics.Friction
        val limit: Float = 900f / Physics.Friction

        assertEquals(limit / 2f, Physics.decay(velocity = 900f, seconds = halfLife), 0.01f)
    }

    @Test
    fun `decay moves further for a harder fling`() {
        val gentle: Float = Physics.decay(velocity = 300f, seconds = 0.1f)
        val hard: Float = Physics.decay(velocity = 900f, seconds = 0.1f)

        assertTrue(hard > gentle)
    }

    @Test
    fun `decay duration is the time to cover all but the remainder`() {
        val seconds: Float = Physics.decayDuration(remaining = 0.01f)
        val limit: Float = 900f / Physics.Friction

        assertEquals(limit * 0.99f, Physics.decay(velocity = 900f, seconds = seconds), 0.02f)
    }

    @Test
    fun `spring starts where it was released`() {
        assertEquals(120f, Physics.spring(from = 120f, to = 40f, velocity = 0f, seconds = 0f), tolerance)
    }

    @Test
    fun `spring approaches its target without overshooting`() {
        var previous: Float = 120f
        for (index in 1..60) {
            val at: Float = Physics.spring(from = 120f, to = 40f, velocity = 0f, seconds = index / 60f)
            assertTrue(at <= previous + tolerance, "moved backwards at sample $index")
            assertTrue(at >= 40f - tolerance, "overshot the target at sample $index")
            previous = at
        }
        assertEquals(40f, previous, 0.05f)
    }

    @Test
    fun `spring leaves at the velocity it was given`() {
        val first: Float = Physics.spring(from = 0f, to = 100f, velocity = 0f, seconds = 1f / 240f)
        val second: Float = Physics.spring(from = 0f, to = 100f, velocity = 600f, seconds = 1f / 240f)

        assertTrue(second > first, "a faster release did not move further in the same frame")
    }

    @Test
    fun `a release already moving towards the target is closer to it at the same moment`() {
        val still: Float = Physics.spring(from = 0f, to = 100f, velocity = 0f, seconds = 0.7f)
        val thrown: Float = Physics.spring(from = 0f, to = 100f, velocity = 400f, seconds = 0.7f)

        assertTrue(abs(thrown - 100f) < abs(still - 100f))
    }

    @Test
    fun `spring duration leaves the motion inside the tolerance`() {
        val seconds: Float = Physics.springDuration(from = 0f, to = 100f, velocity = 0f, tolerance = 0.5f)

        assertTrue(abs(Physics.spring(0f, 100f, 0f, seconds) - 100f) <= 0.5f)
    }

    @Test
    fun `a release that starts inside the tolerance needs no motion`() {
        assertEquals(0f, Physics.springDuration(from = 0f, to = 0.005f, velocity = 0f, tolerance = 0.01f))
    }
}
