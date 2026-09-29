package dev.rotalex.lutter.model.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The canonical number contract (PLAN §5.4, rule D1).
 *
 * Three things are being defended here, and they are separable, so they are tested
 * separately:
 *
 *  * **One spelling per value.** Rounding to four fractional digits, and trimming the
 *    fractional part to its last non-zero digit, so `16.0` is `16` and there is no second
 *    way to write a number.
 *  * **No `toString()`.** The text comes out of a scaled `Long`, so a `Float` widens into a
 *    `Double` without its binary noise ever reaching the document. The tests that matter
 *    here are the ones that put a value next to what `toString()` would have said about it.
 *  * **Refusal over approximation.** NaN, the infinities, and magnitudes past
 *    [MAX_CANONICAL_MAGNITUDE] are errors rather than something spelled approximately.
 *
 * Every expected value in this file was derived from the algorithm and then checked against
 * an independent IEEE-754 model rather than against a run of this code, so a wrong
 * expectation here means the algorithm changed and not that the arithmetic was retyped.
 */
class CanonicalNumericsTest {

    // -----------------------------------------------------------------------------------
    // Canonicalization
    // -----------------------------------------------------------------------------------

    @Test
    fun `a value with at most four fractional digits is unchanged`() {
        // The first thing a canonicalization is allowed to do is nothing. A value that
        // already fits the grid must come back bit-identical, or every write of a document
        // is a rewrite.
        val unchanged = listOf(
            16.5,
            0.1234,
            -0.4,
            1234.5678,
            0.0625,
            100.0,
            -0.0001,
        )

        for (value in unchanged) {
            assertEquals(value, canonicalize(value), "expected '$value' to be unchanged")
        }

        for (value in listOf(16.5f, 0.1234f, -0.4f, 0.0625f, 100.0f)) {
            assertEquals(value, canonicalize(value), "expected '$value' to be unchanged")
        }
    }

    @Test
    fun `a fifth fractional digit rounds to the nearest`() {
        assertEquals(0.1235, canonicalize(0.123456))
        assertEquals(0.1234, canonicalize(0.12344))
        assertEquals(-0.1235, canonicalize(-0.123456))
        assertEquals(10.0, canonicalize(9.99995))
        assertEquals(2.0, canonicalize(1.99995))

        // A Float is rounded from its stored value, not from the decimal that was typed.
        // 1.23456789f is 1.2345678806304932, whose fifth decimal digit is a 6.
        assertEquals(1.2346f, canonicalize(1.23456789f))
    }

    @Test
    fun `a value exactly half way rounds away from zero`() {
        // 0.03125 is 2^-5, so it is exactly representable and 0.03125 * 10^4 is exactly
        // 312.5. That makes it the one kind of half-way value whose answer does not depend
        // on how the binary representation happens to fall, which is what makes it worth
        // pinning: 0.00005 would be a half-way value too, but only by accident.
        assertEquals(0.0313, canonicalize(0.03125))
        assertEquals(-0.0313, canonicalize(-0.03125))
        assertEquals(0.0001, canonicalize(0.00005))
        assertEquals(0.0313f, canonicalize(0.03125f))
    }

    @Test
    fun `a value that rounds to zero loses its sign`() {
        // A document has one spelling for zero. Without this, -2.7e-25 canonicalizes to
        // the value -0.0 while its own text says "0", and a value disagreeing with its own
        // text is the exact failure this whole design is about — arrived at through the sign
        // bit, which is why it gets a test rather than a code review.
        assertEquals(0.0, canonicalize(-2.7e-25))
        assertEquals(0.0, canonicalize(-0.0))
        assertEquals(0.0f, canonicalize(-0.0f))

        assertEquals("0", canonicalText(-2.7e-25))
        assertEquals("0", canonicalText(-0.0))
        assertEquals("0", canonicalText(0.0))

        // And a value that is only just above zero keeps its digits.
        assertEquals("-0.0001", canonicalText(-0.0001))
    }

    @Test
    fun `canonicalization is idempotent`() {
        // A second pass has to be a no-op, or a value is not canonical but merely rounded,
        // and a document that is read and written repeatedly would drift forever. These are
        // the values that would break it: the exact half-way, the binade boundaries where a
        // Float's grid changes width, a value that rounds to zero, and both ends of the
        // magnitude range.
        val doubles = listOf(
            0.0, -0.0, 1.0, -1.0, 16.5, -16.5, 0.1, 0.123456, 0.03125, 0.0625,
            0.0001, 1234.5678, 839.0, 1024.0, -1024.0, 1.0e8, -1.0e8, 1.0e11,
            MAX_CANONICAL_MAGNITUDE, 99999.99999, 0.00005, 9.99995, 511.99995,
        )

        for (value in doubles) {
            val once = canonicalize(value)
            assertEquals(once, canonicalize(once), "not idempotent for '$value'")
        }

        val floats = listOf(
            0.0f, -0.0f, 1.0f, -1.0f, 16.5f, 0.1f, 0.123456f, 0.03125f, 0.1234f,
            -0.4f, 1.23456789f, 839.0f, 839.00006103515625f, 1024.0f, 2048.5f,
            1.0e8f, 99999.99f, 511.99995f,
        )

        for (value in floats) {
            val once = canonicalize(value)
            assertEquals(once, canonicalize(once), "not idempotent for '$value'")
        }
    }

    // -----------------------------------------------------------------------------------
    // The text
    // -----------------------------------------------------------------------------------

    @Test
    fun `the scaled integer is what writes the digits`() {
        // PLAN §5.4 D1 gives this example: 165000 / 10000 is 16.5. The scaled integer is the
        // whole of the value's identity; the division and the trimming below are the only
        // things that turn it into text.
        assertEquals("16.5", canonicalText(16.5))
        assertEquals("16.5", canonicalText(16.5f))
    }

    @Test
    fun `trailing fractional zeros are not written`() {
        // `16.0` as `16` is the difference between a document that diffs cleanly and one
        // where re-saving a file rewrites every number in it.
        assertEquals("16", canonicalText(16.0))
        assertEquals("16", canonicalText(16.0f))
        assertEquals("16.5", canonicalText(16.5))
        assertEquals("1.25", canonicalText(1.25))
        assertEquals("0.5", canonicalText(0.5))
        assertEquals("100", canonicalText(100.0))
        assertEquals("-16", canonicalText(-16.0))
        assertEquals("-0.5", canonicalText(-0.5))
        assertEquals("0.0625", canonicalText(0.0625))
    }

    @Test
    fun `a float is written from its exact value and not from its widened double`() {
        // 0.1f is 0.100000001490116119384765625, and widening it to a Double and printing
        // *that* is what D1 forbids: "0.10000000149011612", which is a different number that
        // happens to look like the one the author wrote. The canonical text is "0.1".
        assertEquals("0.1", canonicalText(0.1f))
        assertEquals("0.1", canonicalText(0.1))

        // The same test at the other end of the range. 1.0e8f is exactly 100000000, and
        // Float.toString() would write "1.0E8" — an exponent the canonical form never uses.
        assertEquals("100000000", canonicalText(1.0e8f))
        assertEquals("100000000", canonicalText(1.0e8))
    }

    @Test
    fun `the largest canonical value is written as a plain integer`() {
        // 1.0e11f is not 1.0e11. The nearest Float to 10^11 is 99999997952, and the
        // canonical text is that Float's actual value, expanded exactly. Float.toString()
        // would have written "1.0E11" — a value the document never held.
        assertEquals("99999997952", canonicalText(1.0e11f))
        assertEquals("100000000000", canonicalText(1.0e11))

        // None of these spellings grows an exponent or a fraction, which is what makes the
        // text safe to read, diff and compare as a string.
        for (value in listOf(1.0e8, 1.0e11, MAX_CANONICAL_MAGNITUDE, 123456789.0)) {
            val text = canonicalText(value)
            assertTrue(
                '.' !in text && 'e' !in text && 'E' !in text,
                "unexpected '$text' for '$value'",
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------------------------

    @Test
    fun `a non finite value is rejected`() {
        // A document that can hold NaN makes every comparison against it wrong and every
        // diff of it unreadable, and nothing in a layout document has a legitimate NaN.
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalize(value)
            }
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalText(value)
            }
        }

        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalize(value)
            }
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalText(value)
            }
        }
    }

    @Test
    fun `a magnitude beyond the canonical range is rejected`() {
        // This is the decision the design turns on, pinned: past MAX_CANONICAL_MAGNITUDE
        // there is no canonical form and the value is refused rather than spelled
        // approximately. 1.0e30f is the case that motivates it — widened to a Double it is
        // 1.0000000150474662E30, and every way of printing that without losing the value is
        // either a libm logarithm or a bignum. See MAX_CANONICAL_MAGNITUDE for the argument.
        for (value in listOf(1.0e30, MAX_CANONICAL_MAGNITUDE * 2, Double.MAX_VALUE, -1.0e30)) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalize(value)
            }
        }

        for (value in listOf(1.0e30f, Float.MAX_VALUE, -1.0e30f)) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                canonicalize(value)
            }
        }
    }

    @Test
    fun `the boundary of the canonical range is inside it`() {
        // The bound is inclusive, and inclusive is the part that has to be tested: a
        // boundary that is off by one either rejects a legitimate value or accepts one whose
        // scaled integer no longer round-trips.
        assertEquals(MAX_CANONICAL_MAGNITUDE, canonicalize(MAX_CANONICAL_MAGNITUDE))
        assertEquals("200000000000", canonicalText(MAX_CANONICAL_MAGNITUDE))

        assertFailsWith<IllegalArgumentException> {
            canonicalize(MAX_CANONICAL_MAGNITUDE + 1.0)
        }
    }

    // -----------------------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------------------

    @Test
    fun `a value is written as a bare json number`() {
        // Bare, not a quoted string. `"16.5"` is a string that looks like a number, and a
        // reader that has to know which of the two it is looking at is a reader this format
        // exists to spare.
        assertEquals("16.5", Json.encodeToString(CanonicalDouble(), 16.5))
        assertEquals("16", Json.encodeToString(CanonicalDouble(), 16.0))
        assertEquals("-0.4", Json.encodeToString(CanonicalDouble(), -0.4))
        assertEquals("0", Json.encodeToString(CanonicalDouble(), 0.0))
    }

    @Test
    fun `a float is written as a bare json number`() {
        assertEquals("16.5", Json.encodeToString(CanonicalFloat(), 16.5f))
        assertEquals("0.1", Json.encodeToString(CanonicalFloat(), 0.1f))
        assertEquals("16", Json.encodeToString(CanonicalFloat(), 16.0f))
    }

    @Test
    fun `a value round trips through json unchanged`() {
        val values = listOf(
            16.5, 0.1234, -0.4, 0.1, 0.0001, 100.0, 1234.5678, MAX_CANONICAL_MAGNITUDE,
        )

        for (value in values) {
            val canonical = canonicalize(value)
            val text = Json.encodeToString(CanonicalDouble(), canonical)
            val decoded = Json.decodeFromString(CanonicalDouble(), text)
            assertEquals(canonical, decoded, "lost '$value' via '$text'")
        }

        for (value in listOf(16.5f, 0.1f, 0.0001f, 100.0f)) {
            val canonical = canonicalize(value)
            val text = Json.encodeToString(CanonicalFloat(), canonical)
            val decoded = Json.decodeFromString(CanonicalFloat(), text)
            assertEquals(canonical, decoded, "lost '$value' via '$text'")
        }
    }

    @Test
    fun `encoding a value twice writes the same bytes`() {
        // The property the whole format rests on: a document that is read and written
        // unchanged must be byte-identical, or every save is a diff.
        val values = listOf(16.5, -0.4, 0.1, 1234.5678, 1.0e11, 0.0001)

        for (value in values) {
            val once = Json.encodeToString(CanonicalDouble(), canonicalize(value))
            val twice = Json.encodeToString(CanonicalDouble(), canonicalize(value))
            assertEquals(once, twice, "encoding '$value' twice wrote two different documents")
        }

        // And through a decode in the middle, which is the round trip that matters: a
        // document read from disk and written back has to come out the way it went in.
        for (value in values) {
            val text = Json.encodeToString(CanonicalDouble(), canonicalize(value))
            val reencoded = Json.encodeToString(
                CanonicalDouble(),
                Json.decodeFromString(CanonicalDouble(), text),
            )
            assertEquals(text, reencoded, "round trip rewrote '$text' as '$reencoded'")
        }
    }

    @Test
    fun `encoding canonicalizes the value it is given`() {
        // A value that reached the serializer without being canonicalized must still be
        // written canonically. Canonicalizing at one end only means trusting one end, and
        // this is the cheap end to defend.
        assertEquals("0.1235", Json.encodeToString(CanonicalDouble(), 0.123456))
        assertEquals(
            Json.encodeToString(CanonicalDouble(), canonicalize(0.123456)),
            Json.encodeToString(CanonicalDouble(), canonicalize(canonicalize(0.123456))),
        )
        assertEquals("1.2346", Json.encodeToString(CanonicalFloat(), 1.23456789f))
    }

    @Test
    fun `decoding refuses what canonicalization refuses`() {
        // A finite number past the bound is a well-formed JSON number, so the guard has to
        // be the one that stops it rather than the parser.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(CanonicalDouble(), "1e30")
        }

        // The non-finite case only reaches the guard on a reader configured to accept
        // special floating-point values; the default Json rejects `NaN` as a token before a
        // serializer ever sees it. Both are the same refusal, and the message says which
        // number was refused.
        val lenient = Json { allowSpecialFloatingPointValues = true }
        assertFailsWith<IllegalArgumentException> {
            lenient.decodeFromString(CanonicalDouble(), "NaN")
        }
        assertFailsWith<IllegalArgumentException> {
            lenient.decodeFromString(CanonicalDouble(), "Infinity")
        }
        assertFailsWith<IllegalArgumentException> {
            lenient.decodeFromString(CanonicalFloat(), "NaN")
        }
    }
}
