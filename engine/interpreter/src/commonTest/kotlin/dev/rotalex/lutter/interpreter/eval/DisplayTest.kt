package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §10.4's four template part types have exactly one text each, and a fifth has none.
 *
 * The absence is the assertion. §10.6's first hazard row is `Double.toString()` differing
 * between hosts, so the fix is not a better number printer here — it is that a floating value
 * never reaches a template, and `num.format` writes it with integer arithmetic on both sides.
 */
class DisplayTest {

    @Test
    fun `the four permitted parts each have their own text`() {
        assertEquals("Hi", Value.Str("Hi").toDisplayString())
        assertEquals("3", Value.Int32(3).toDisplayString())
        assertEquals("4000000000000000001", Value.Int64(4_000_000_000_000_000_001L).toDisplayString())
        assertEquals("true", Value.Bool(true).toDisplayString())
        assertEquals("false", Value.Bool(false).toDisplayString())
    }

    @Test
    fun `an integer past a double's exact range prints every digit`() {
        // `Long.toString()` is exact base ten on every target; a `Double` would have lost
        // digits here. The whole reason integers take this path and floats do not.
        val past: Long = 4_000_000_000_000_000_001L

        assertTrue(past > MAX_EXACT_DOUBLE, "the value has to be one a double cannot hold")
        assertEquals(past.toString(), Value.Int64(past).toDisplayString())
    }

    @Test
    fun `a floating part has no text and names num.format`() {
        val failure = assertFailsWith<IllegalStateException> { Value.Float64(1.5).toDisplayString() }

        val message = failure.message
        assertTrue(message?.contains("num.format") == true, message.orEmpty())
        assertTrue(message?.contains("str, i32, i64 and bool") == true, message.orEmpty())
    }

    @Test
    fun `a value outside the four refuses naming the four`() {
        assertFailsWith<IllegalStateException> { Value.Dp(8f).toDisplayString() }
        assertFailsWith<IllegalStateException> { Value.Null.toDisplayString() }
    }
}

/** `2^53`: the largest integer a `Double` holds exactly. */
private const val MAX_EXACT_DOUBLE: Double = 9007199254740992.0
