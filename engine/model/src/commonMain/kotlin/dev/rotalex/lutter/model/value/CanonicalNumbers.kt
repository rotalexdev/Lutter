package dev.rotalex.lutter.model.value

import kotlin.math.abs

/**
 * The canonical form of every number a document can carry (PLAN §5.4, rule D1).
 *
 * A document is read by people, diffed in review, and hashed in cache keys. All three
 * break if the same logical value has more than one spelling, and floating point hands out
 * such spellings freely. `0.1` is not `0.1`; it is `0.100000001490116119384765625` in a
 * `Float` and `0.1000000000000000055511151231257827` in a `Double`, and widening the first
 * to the second is exact arithmetic that prints as `0.10000000149011612`. Nothing here
 * prints a float. The text is assembled from a scaled integer, and the integer is the only
 * thing that decides it.
 *
 * ### Why not `toString()`
 *
 * `Double.toString()` is not specified to be stable, and the instability is not theoretical.
 * The JVM delegates to `java.lang.Double.toString`, and the JDK changed that algorithm in
 * JDK 19 to a shorter one — so the same Kotlin source, on the same machine, can print
 * different digits for the same value before and after a runtime upgrade. Kotlin/Native
 * carries its own port, and Kotlin/JS and Kotlin/Wasm sit on the host engine's number
 * printer. None of those is wrong; none of them is a contract, and a document format cannot
 * be built on a coincidence. A commit would hash differently on the machine that wrote it,
 * and a document round-tripped through two targets would come back as a diff nobody can
 * explain.
 *
 * kotlinx.serialization is not a way out either, which is worth knowing before anyone tries
 * it. Its JSON encoder writes a `Double` as `writer.write(value.toString())` — the number
 * text is chosen by the format, after the serializer has handed over a `Double`, so a
 * serializer that calls `encoder.encodeDouble(16.5)` has already lost the argument with
 * `toString()`. The text has to be written as text. See [CanonicalDouble] for how.
 *
 * ### The shape of the thing
 *
 * A canonical number is defined by a single integer: the value scaled by 10^4, rounded to
 * the nearest integer with ties away from zero. `16.5` is `165000`, and `165000 / 10000` is
 * `16.5` with the trailing zeros trimmed off. Two properties fall out of the definition
 * rather than being bolted on:
 *
 *  * **The text is a function of the integer, not of the float.** Every digit comes from
 *    `Long` division and remainder, and `Long.toString()` is exact base-ten conversion that
 *    every platform implements identically. There is no rounding step left in the formatting
 *    path, because the rounding already happened when the integer was made.
 *
 *  * **A value has one spelling.** There is no "16.0" and no "16.0000" and no "1.6E1",
 *    because the integer part and the fractional part are emitted separately and the
 *    fractional part is trimmed to the last non-zero digit.
 *
 * ### What is refused, and why refusing is the honest answer
 *
 * Non-finite values are refused outright. A document that could carry `NaN` would make every
 * comparison against it wrong and every diff of it unreadable, and nothing in a layout
 * document has a legitimate `NaN` in it.
 *
 * Magnitudes above [MAX_CANONICAL_MAGNITUDE] are refused too, and that one is a judgement
 * call rather than an obvious consequence — see that constant for the whole argument. In
 * short: the scaled integer has to stay small enough that rounding it a second time returns
 * the same integer, and past that point the only lossless spellings need either a libm
 * logarithm or 2-adic bignum arithmetic. Both are available. Neither belongs in a domain
 * model, and a document that genuinely needs a number that large has `Value.Int64`.
 *
 * ### What this does *not* cover
 *
 * PLAN §5.4 draws the line explicitly: a runtime `Value.Float64`
 * produced by evaluation is never serialized, so it may hold any finite double, canonical or
 * not. Canonicalization is a property of the *document*, not of the arithmetic.
 *
 * ### Fixed-width text, and why one of the two is public
 *
 * §10.6 rules a float out of a template and hands `num.format(v, n)` the job. A generated call
 * carries a template and its imports and nothing else (§10.3), so the emitted Kotlin has to
 * *call* whatever the interpreter calls. Two implementations of the same arithmetic would
 * agree by convention until one of them was edited; one implementation cannot drift at all.
 * [fixedDecimalText] is therefore public and lives here, beside the rounding rule it reuses,
 * rather than in either backend.
 *
 * @see CanonicalDouble for the serializer, and for why the text has to bypass `encodeDouble`.
 */

// ---------------------------------------------------------------------------------------
// The contract
// ---------------------------------------------------------------------------------------

/**
 * The largest magnitude that has a canonical form: `2.0e11`.
 *
 * The bound is not arbitrary and not generous-by-feel. It is the largest round decimal that
 * survives being canonicalized twice, and the proof is short:
 *
 * Let `units` be the scaled integer, so `value = units / 10^4`. Canonicalization writes
 * `fl(units / 10^4)` back out, and canonicalizing *that* divides by 10^4 and multiplies by
 * 10^4 again. Each of those two steps is one correctly-rounded IEEE operation, so each
 * contributes at most a relative error of `2^-53` and the pair contributes at most
 * `units * 2^-52`. The second pass recovers the same `units` as long as that error stays
 * under half of one scaled unit — otherwise the value drifts, and a value that drifts on
 * every round trip is not canonical, it is merely rounded:
 *
 * ```
 * units * 2^-52 < 0.5   <=>   units < 2^51   <=>   |value| < 2.25e11
 * ```
 *
 * `2.0e11` sits inside that with 11% to spare, and it is a `Double` that is exactly
 * representable, so the boundary itself is a legal value rather than a value that rounds
 * into or out of range depending on the platform.
 *
 * The obvious looser bound is `Long.MAX_VALUE / 10^4`, about `9.2e14`, and it is wrong for
 * the reason above: a value that large canonicalizes to something that does not
 * canonicalize back to itself.
 *
 * ### Why the alternative spellings were rejected
 *
 * A large number could also be *spelled* rather than refused. Both spellings that would keep
 * the value exactly are worse than the refusal, for reasons that are not about effort:
 *
 *  * **Scientific notation** needs the decimal exponent of the value. The obvious way to get
 *    it is `log10`, and `log10` is a libm call whose last bit is not guaranteed identical
 *    across platforms. Using it would reintroduce precisely the non-determinism this file
 *    exists to remove, one level up. A loop of divisions by ten is deterministic but
 *    accumulates its own rounding, and a rounding-sensitive digit count is the same class
 *    of bug with more steps.
 *
 *  * **The exact decimal expansion** of a large `Double` is its mantissa times a power of
 *    two, so printing it exactly means printing a 1024-bit number, which means carrying a
 *    bignum in a module whose entire job is to be a vocabulary. The output would also be a
 *    300-digit integer where the author wrote `1e30` — technically lossless, and unreadable
 *    in a document people review.
 *
 * A refusal is deterministic, it is testable, and it fails at the point where the value was
 * constructed rather than somewhere downstream. A silent lossless-looking approximation
 * fails none of those. Every legitimate document value is many orders of magnitude below
 * this bound: a `dp` of 16, an `sp` of 14, a fraction of 0.5, an angle of 3.14159.
 */
public const val MAX_CANONICAL_MAGNITUDE: Double = 2.0e11

/**
 * Canonicalizes a `Double`: at most four fractional digits, finite, and stable under
 * repetition.
 *
 * Rounds to the nearest multiple of `1e-4` with ties away from zero, so `0.123456` becomes
 * `0.1235` and `-0.123456` becomes `-0.1235`. A value that already has at most four
 * fractional digits comes back unchanged.
 *
 * The rounding is applied to the value **as stored**, not to the decimal the author typed,
 * and those are not the same number. `0.12345` is stored as `0.123449999999999998223…`, and
 * the scaled product of that value lands exactly on `1234.5`, so it rounds away from zero to
 * `0.1235`. This is not a rounding of the author's intent, it is the rounding of the number
 * the document actually holds, and it is the only version that is reproducible.
 *
 * A value that rounds to zero comes back as positive zero whatever its sign was, so `-0.0`
 * and `-2.7e-25` are both `0.0`. A document has one spelling for zero and it is not the one
 * with a minus sign in front of nothing.
 *
 * @throws IllegalArgumentException if [value] is NaN or infinite, or if its magnitude
 *   exceeds [MAX_CANONICAL_MAGNITUDE].
 */
public fun canonicalize(value: Double): Double {
    val units = requireUnits(value)
    val canonical = units.toDouble() / SCALE.toDouble()
    return if (isNegative(value, units)) -canonical else canonical
}

/**
 * Canonicalizes a `Float`, with the same contract as [canonicalize].
 *
 * The work is done on the widened `Double` and narrowed back, which is deliberate. Widening
 * a `Float` is exact, so the scaled integer computed from the widened value is the rounded
 * form of the float's *actual* value rather than of a re-interpretation of it, and it is
 * the narrowing step that decides whether a `Float` can hold the canonical value at all.
 *
 * That question has an honest answer, and it is worth stating plainly. A `Float`'s spacing
 * grows with its magnitude and passes `1e-4` at 1024, so a value with four fractional digits
 * is often not on a `Float`'s grid at all — `839.0001` is not a `Float`, and the nearest one
 * to it is `839.0001220703125`. Narrowing therefore lands on the nearest `Float`, which is
 * not always the `Float` that went in. What survives intact is the text, which is the
 * document's actual contract: re-canonicalizing the narrowed value produces the same scaled
 * integer, and that is what makes the round trip byte-stable.
 *
 * @throws IllegalArgumentException if [value] is NaN or infinite, or if its magnitude
 *   exceeds [MAX_CANONICAL_MAGNITUDE]. The finiteness is re-checked here so the message
 *   names the `Float` the caller actually passed rather than its widened form.
 */
public fun canonicalize(value: Float): Float {
    // Checked before delegating so the failure names the argument as it was written. The
    // Double overload checks again; a `require` is free next to the arithmetic it guards,
    // and a diagnostic that says `1.0000000150474662E30` instead of `1.0E30` costs a reader
    // more than the second check does.
    require(value.isFinite()) { "Non-finite value: '$value'" }
    return canonicalize(value.toDouble()).toFloat()
}

// ---------------------------------------------------------------------------------------
// The text
// ---------------------------------------------------------------------------------------

/**
 * The canonical text of a `Double`: what a document actually stores.
 *
 * `16.5` is `"16.5"`, `16.0` is `"16"`, and `1.5e-7` is `"0"`.
 *
 * That last one is worth reading twice, because it is the cost of the format rather than an
 * oversight. Four fractional digits is a promise about the *scale* of a document, and
 * anything below `0.00005` rounds away to nothing. A value that small is not a length, a
 * fraction or a ratio in a layout document; if one ever is, the fix is a different
 * representation and not a wider number.
 *
 * Internal rather than public because the text is a property of the serializer, not an API a
 * caller should depend on separately. A caller that wants to know what a number will be
 * written as can encode it and look, which is the same answer from the same code path.
 */
internal fun canonicalText(value: Double): String {
    val units = requireUnits(value)
    return textOf(units = units, negative = isNegative(value, units))
}

/** The canonical text of a `Float`. See [canonicalText] for the `Double` form. */
internal fun canonicalText(value: Float): String = canonicalText(value.toDouble())

/**
 * The scaled integer a value is written from, or a failure if it has none.
 *
 * This is the one place the two rules live: finite, and within [MAX_CANONICAL_MAGNITUDE].
 * Canonicalization and decoding both go through it, which is why a value that cannot be
 * canonicalized cannot be written either — there is no second path that forgot to ask.
 * [fixedDecimalText] is the one formatter that does not ask, and it has to leave it out: §10.6
 * makes functions total, and a bound a formatter may refuse is a bound it cannot have.
 */
private fun requireUnits(value: Double): Long {
    require(value.isFinite()) { "Non-finite value: '$value'" }

    // `abs` is a sign-bit clear, not a negation, so it is exact and it maps -0.0 to 0.0.
    val magnitude = abs(value)
    require(magnitude <= MAX_CANONICAL_MAGNITUDE) {
        "Value '$value' is outside the canonical range; " +
            "the largest canonical magnitude is $MAX_CANONICAL_MAGNITUDE"
    }

    return roundedUnits(magnitude, SCALE.toDouble())
}

/**
 * [magnitude] times [scale], rounded to the nearest integer with ties away from zero.
 *
 * Truncation and a comparison rather than an added `0.5`, and the comparison is what makes the
 * tie rule exact: `scaled - units` subtracts two doubles within a factor of two of each other,
 * so Sterbenz's lemma applies and the difference is the true fractional part with no rounding
 * of its own. `units.toDouble()` is exact for the same reason [MAX_CANONICAL_MAGNITUDE] is
 * where it is.
 *
 * `Double.toLong()` saturates, so a magnitude too large to scale answers the largest integer
 * instead of throwing — and the `units == Long.MAX_VALUE` guard keeps the tie from wrapping
 * that answer back to a negative one. Total arithmetic is worth exactly this much arithmetic.
 */
private fun roundedUnits(magnitude: Double, scale: Double): Long {
    val scaled = magnitude * scale
    val units = scaled.toLong()
    val fraction = scaled - units.toDouble()
    if (fraction < 0.5 || units == Long.MAX_VALUE) return units
    return units + 1
}

/**
 * Whether a canonical value is negative — the single decision behind both the sign of the
 * value and the sign of its text.
 *
 * The `units != 0L` is not a detail, it is the point. A value small enough to round to zero
 * is zero as far as a document is concerned, and a document has one spelling for zero:
 * `0`. So `-2.7e-25` canonicalizes to positive zero and writes `0`, and `-0.0` does the same.
 * Asking the sign twice instead of once is how `-2.7e-25` ends up as the value `-0.0` with
 * the text `"-0"` — a value and a text that disagree about the same number, which is the
 * whole class of bug this file exists to prevent, arriving through the sign bit.
 */
private fun isNegative(value: Double, units: Long): Boolean = units != 0L && value < 0.0

/**
 * Assembles the text from a scaled integer, and from nothing else.
 *
 * Every digit below comes out of integer division or `Long.toString()`. There is no float
 * left in this function to be formatted, which is the entire point: the caller has already
 * done the only rounding that is allowed to happen, and this only writes down the answer.
 */
private fun textOf(units: Long, negative: Boolean): String {
    val whole = units / SCALE
    val fraction = units % SCALE
    val sign = if (negative) "-" else ""

    if (fraction == 0L) return "$sign$whole"

    // `units % SCALE` is never negative and never wider than SCALE, so padding to four
    // digits and trimming the trailing zeros is the whole of the fractional formatting.
    // `1.25` comes out as `1.25` and `1.20` as `1.2` from the same two lines.
    val digits = fraction.toString().padStart(SCALE_DIGITS, '0').trimEnd('0')
    return "$sign$whole.$digits"
}

// ---------------------------------------------------------------------------------------
// Fixed-width text
// ---------------------------------------------------------------------------------------

/**
 * [value] as text carrying exactly [decimals] fractional digits, ties rounded away from zero.
 *
 * `num.format`'s arithmetic, and the one function both backends call: §10.6's reason for
 * ruling a float out of a template is that `Double.toString()` is not portable, so the digits
 * have to come out of an integer. Total, as §10.6 requires of every function — a non-finite
 * value is spelled rather than refused, because arithmetic reaches NaN even though no document
 * literal can, and a caller-chosen width wider than [MAX_DECIMALS] is clamped rather than
 * refused for the same reason.
 *
 * Unlike [canonicalText], which trims to the last non-zero digit: this answers a width the
 * caller asked for, and `1.20` at two decimals is not `1.2`.
 */
public fun fixedDecimalText(value: Double, decimals: Int): String {
    if (!value.isFinite()) return nonFiniteText(value)
    val places = decimals.coerceIn(0, MAX_DECIMALS)
    val units = roundedUnits(abs(value), DECIMAL_SCALES[places])
    // The sign is asked of the rounded integer rather than of the value, so a value that
    // rounds to zero is written `0` and not `-0`: one spelling for zero, as above.
    val sign = if (units != 0L && value < 0.0) "-" else ""
    val scale = DECIMAL_SCALES[places].toLong()
    val whole = units / scale
    if (places == 0) return "$sign$whole"
    return "$sign$whole.${(units % scale).toString().padStart(places, '0')}"
}

/**
 * The three non-finite spellings, written out instead of `toString()`ed.
 *
 * `toString()` on `Double` is the one thing §10.6's hazard row is about, so the formatter that
 * exists to avoid it cannot be the thing that reintroduces it — and a hand-written spelling is
 * also the only one that is identical on JS, JVM, Native and Wasm.
 */
private fun nonFiniteText(value: Double): String = when {
    value.isNaN() -> "NaN"
    value > 0.0 -> "Infinity"
    else -> "-Infinity"
}

/** Widest [fixedDecimalText] accepts: the scale has to be exact and the scaled integer a `Long`. */
private const val MAX_DECIMALS: Int = 9

/**
 * `10^n` for `n` in `0..MAX_DECIMALS`, all exact. A table rather than `pow`, which is a libm
 * call and would put the last bit of the scale outside this file's control.
 */
private val DECIMAL_SCALES: DoubleArray = doubleArrayOf(
    1.0, 10.0, 100.0, 1_000.0, 10_000.0,
    100_000.0, 1_000_000.0, 10_000_000.0, 100_000_000.0, 1.0e9,
)

// ---------------------------------------------------------------------------------------
// The scale
// ---------------------------------------------------------------------------------------

/** `10^SCALE_DIGITS`: the factor between a value and the integer its text is written from. */
private const val SCALE: Long = 10_000L

/** Fractional digits a canonical number carries at most. Fixes the width of [SCALE]. */
private const val SCALE_DIGITS: Int = 4
