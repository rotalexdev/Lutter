package dev.rotalex.lutter.editor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The two motions a canvas owes a gesture: a fling that coasts to a stop, and a release that
 * settles into the place a snap chose.
 *
 * ### Closed forms, not integrators
 *
 * Both are written as functions of elapsed time rather than as per-frame steps. A frame loop that
 * integrates velocity would give a different answer at 60 and 120 Hz, and the same drag would end
 * in a different place on the two hosts — which is the class of bug §32 Phase 18.5 exists to
 * prevent (D37). A closed form has one answer per `(t, parameters)` pair, so a `commonTest` can
 * pin it and the UI only has to ask "where are we at this frame".
 *
 * ### Why these two models
 *
 * Decay is exponential with a friction coefficient: velocity falls by a constant factor per unit
 * time, which is what a physical drag through a medium does and what makes the stop feel
 * proportional to how hard the fling was. The spring is critically damped — `damping = 2√k` — so
 * it approaches its target as fast as it can without overshooting: a snap that overshot would
 * draw the node past the guide and back, which reads as a wobble rather than a placement.
 *
 * A new press cancels both. The UI does that by dropping the frame loop it started; nothing here
 * holds state to cancel, which is the reason these are functions and not objects.
 */
internal object Physics {

    /** How quickly a fling loses speed, in reciprocal seconds. */
    const val Friction = 6f

    /** The spring's stiffness, in reciprocal seconds squared. */
    const val Stiffness = 220f

    /**
     * How far a fling has travelled [seconds] after release, from an initial [velocity].
     *
     * The integral of `v(t) = v₀e^(−ft)`: the distance grows towards `v₀/f` and never past it, so
     * a hard fling goes further and takes longer to look stopped, and a gentle one stops soon.
     */
    fun decay(velocity: Float, seconds: Float, friction: Float = Friction): Float {
        if (friction <= 0f) return velocity * seconds
        return velocity / friction * (1f - exp(-friction * seconds))
    }

    /**
     * Where a critically damped spring is [seconds] after being released from [from] towards [to]
     * with [velocity].
     *
     * `x(t) = to + (A + Bt)e^(−ωt)` with `ω = √k`, `A = from − to` and `B = v₀ + ωA`. Critical
     * damping is the case where the two roots of the characteristic equation coincide, which is
     * exactly what makes the `Bt` term appear; a stiffer spring settles sooner and never overshoots
     * for a release with no velocity.
     */
    fun spring(
        from: Float,
        to: Float,
        velocity: Float,
        seconds: Float,
        stiffness: Float = Stiffness,
    ): Float {
        val omega: Float = sqrt(stiffness)
        val offset: Float = from - to
        val slope: Float = velocity + omega * offset
        return to + (offset + slope * seconds) * exp(-omega * seconds)
    }

    /**
     * How long a fling needs to cover all but [remaining] of the distance it will ever cover.
     *
     * `x(t) = (v₀/f)(1 − e^(−ft))`, so the fraction left is `e^(−ft)` and the answer is
     * `ln(1/remaining)/f` — a duration the UI can hand to a frame loop instead of watching for a
     * speed threshold that would depend on the frame rate.
     */
    fun decayDuration(remaining: Float = 0.01f, friction: Float = Friction): Float {
        if (friction <= 0f) return 0f
        return ln(1f / remaining.coerceIn(0.0001f, 1f)) / friction
    }

    /**
     * How long a critically damped spring needs to come within [tolerance] of its target.
     *
     * There is no closed form for the crossing, and a thrown release can leave the tolerance,
     * come back and leave again, so this is not a bisection: it walks [limit] in [SAMPLES] steps
     * and reports the first sample after the last one still outside. The answer is therefore an
     * upper bound with a resolution of `limit / SAMPLES`, which at the defaults is under a frame —
     * what a frame loop needs, without a formula that would only be right for a release with no
     * velocity.
     */
    fun springDuration(
        from: Float,
        to: Float,
        velocity: Float,
        tolerance: Float = 0.01f,
        stiffness: Float = Stiffness,
        limit: Float = 2f,
    ): Float {
        if (abs(to - from) <= tolerance && abs(velocity) <= tolerance) return 0f
        val step: Float = limit / SAMPLES
        var last: Float = 0f
        for (index in 1..SAMPLES) {
            val seconds: Float = step * index
            if (abs(spring(from, to, velocity, seconds, stiffness) - to) > tolerance) last = seconds
        }
        // The first sample after the last one still outside: the sample that *is* inside is what
        // the caller needs, so the answer never lands on a moment the motion was still moving.
        return (last + step).coerceAtMost(limit)
    }

    /** Enough samples that the reported duration is under a frame at 60 Hz. */
    private const val SAMPLES: Int = 240
}
