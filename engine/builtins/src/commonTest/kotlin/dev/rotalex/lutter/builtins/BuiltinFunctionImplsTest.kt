package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.interpreter.eval.FunctionImpl
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.model.value.fixedDecimalText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The seed set's implementations: what each one answers, and the totality §10.6 asks of all of
 * them.
 *
 * Two things are being defended. **Totality**: an index a list does not have and an element it
 * does not hold are values the document does not have, which §10.6 answers with `Null` and
 * `false` — never an exception. **Agreement**: `num.format` is asserted against
 * [fixedDecimalText] rather than against a literal, because the generated call goes through the
 * same function and the two halves agreeing is the whole claim §10.6 makes.
 */
class BuiltinFunctionImplsTest {

    private val table = builtinFunctionImpls()

    @Test
    fun `a list answers its size and its two emptiness questions`() {
        assertEquals(Value.Bool(false), call("list.isEmpty", listOf(items(str("a"), str("b")))))
        assertEquals(Value.Bool(true), call("list.isNotEmpty", listOf(items(str("a"), str("b")))))
        assertEquals(Value.Int32(2), call("list.size", listOf(items(str("a"), str("b")))))
        assertEquals(Value.Bool(true), call("list.isEmpty", listOf(items())))
    }

    @Test
    fun `a missing element is a value the document does not have, not an error`() {
        // §10.6's worked example, and the reason `list.get`'s template is `getOrNull` rather than
        // an index: Kotlin's `List.get` throws where the interpreter answers Null.
        val pair = items(str("a"), str("b"))

        assertEquals(Value.Str("a"), call("list.get", listOf(pair, Value.Int32(0))))
        assertEquals(Value.Null, call("list.get", listOf(pair, Value.Int32(2))))
        assertEquals(Value.Null, call("list.get", listOf(pair, Value.Int32(-1))))
    }

    @Test
    fun `an element the list does not hold is false, not an error`() {
        assertEquals(Value.Bool(true), call("list.contains", listOf(items(str("a")), str("a"))))
        assertEquals(Value.Bool(false), call("list.contains", listOf(items(str("a")), str("z"))))
        assertEquals(Value.Bool(false), call("list.contains", listOf(items(), str("a"))))
    }

    @Test
    fun `text answers its own questions`() {
        assertEquals(Value.Bool(true), call("str.isBlank", listOf(str("  \t"))))
        assertEquals(Value.Bool(false), call("str.isBlank", listOf(str(" x "))))
        assertEquals(Value.Int32(3), call("str.length", listOf(str("abc"))))
        assertEquals(Value.Str("AB C"), call("str.uppercase", listOf(str("ab c"))))
        assertEquals(Value.Str("ab c"), call("str.lowercase", listOf(str("AB C"))))
        assertEquals(Value.Str("ab"), call("str.trim", listOf(str("  ab \n"))))
        assertEquals(Value.Bool(true), call("str.contains", listOf(str("abcd"), str("bc"))))
        assertEquals(Value.Bool(false), call("str.contains", listOf(str("abcd"), str("x"))))
    }

    @Test
    fun `a case fold is the root locale and not the platform's`() {
        // §10.6 rules locale-dependent text operations out, and this is the assertion that pins
        // it: a Turkish-locale fold answers `İ` here and the root locale answers `I`.
        assertEquals(Value.Str("I"), call("str.uppercase", listOf(str("i"))))
        assertEquals(Value.Str("i"), call("str.lowercase", listOf(str("I"))))
    }

    @Test
    fun `a formatted number keeps the width the caller asked for`() {
        // The case `canonicalText` cannot answer: at two decimals `1.2` is `1.20`, and the
        // trailing zero is the difference between a price and a fraction.
        assertEquals(Value.Str("1.20"), format(1.2, 2))
        assertEquals(Value.Str("1.23"), format(1.234, 2))
        assertEquals(Value.Str("2"), format(1.5, 0))
        assertEquals(Value.Str("2.000"), format(2.0, 3))
        assertEquals(Value.Str("-1.3"), format(-1.25, 1))
        assertEquals(Value.Str("0.00"), format(-0.001, 2))
    }

    @Test
    fun `a width outside the scale is clamped rather than refused`() {
        // §10.6 makes functions total, so a decimal count no scaled integer can hold is an answer
        // and not an exception. Both sides see the same clamp because both call one function.
        assertEquals(Value.Str(fixedDecimalText(1.5, 9)), format(1.5, 40))
        assertEquals(Value.Str("2"), format(1.5, -3))
    }

    @Test
    fun `a non finite value is spelled by hand rather than refused`() {
        assertEquals(Value.Str("NaN"), format(Double.NaN, 2))
        assertEquals(Value.Str("Infinity"), format(Double.POSITIVE_INFINITY, 2))
        assertEquals(Value.Str("-Infinity"), format(Double.NEGATIVE_INFINITY, 2))
    }

    @Test
    fun `a magnitude too large to scale saturates rather than overflowing`() {
        // §5.4 lets a runtime Float64 hold any finite double, so the scaled integer can be larger
        // than a `Long`. Saturating is total and identical on both sides; throwing is neither.
        assertEquals(Value.Str("92233720368547758.07"), format(1e308, 2))
    }

    @Test
    fun `a float formats as the double it is`() {
        // The signature is a union because §10.4 routes both floating types here, and widening a
        // Float is exact — so the two document values take one path and one answer.
        assertEquals(Value.Str(fixedDecimalText(1.5, 3)), format(Value.Float32(1.5f), 3))
    }

    @Test
    fun `every numeric type converts to a double and nothing else does`() {
        assertEquals(Value.Float64(2.0), call("num.toDouble", listOf(Value.Int32(2))))
        assertEquals(Value.Float64(2.0), call("num.toDouble", listOf(Value.Int64(2L))))
        assertEquals(Value.Float64(1.5), call("num.toDouble", listOf(Value.Float32(1.5f))))
        assertEquals(Value.Float64(1.5), call("num.toDouble", listOf(Value.Float64(1.5))))
    }

    @Test
    fun `coalesce answers the fallback only for a value the document does not have`() {
        assertEquals(Value.Str("a"), call("core.coalesce", listOf(str("a"), str("b"))))
        assertEquals(Value.Str("b"), call("core.coalesce", listOf(Value.Null, str("b"))))
        assertEquals(Value.Bool(true), call("core.isNull", listOf(Value.Null)))
        assertEquals(Value.Bool(false), call("core.isNull", listOf(str("a"))))
    }

    @Test
    fun `an argument the signature does not declare is the two halves disagreeing`() {
        // The other half of §10.6's totality row: a document that could not have got here at all
        // throws, because pass 5 refused it first and silence would let the backends differ.
        val failure = assertFailsWith<IllegalStateException> { call("str.trim", listOf(Value.Int32(1))) }

        assertTrue(failure.message?.contains("str.trim") == true, "got ${failure.message}")
    }

    private fun format(value: Double, decimals: Int): Value =
        call("num.format", listOf(Value.Float64(value), Value.Int32(decimals)))

    private fun format(value: Value.Float32, decimals: Int): Value =
        call("num.format", listOf(value, Value.Int32(decimals)))

    private fun call(id: String, args: List<Value>): Value {
        val impl: FunctionImpl = table[FunctionId(id)] ?: error("'$id' is not registered")
        return impl.invoke(args)
    }

    private fun items(vararg values: Value): Value = Value.ListOf(values.toList())

    private fun str(value: String): Value = Value.Str(value)
}
