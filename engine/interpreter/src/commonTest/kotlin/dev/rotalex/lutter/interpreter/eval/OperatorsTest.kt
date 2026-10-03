package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §10.6's operator rows, reached through [Evaluator] rather than through the tables.
 *
 * The tables are `internal` and this is deliberately not their unit test: the promise §10.6
 * makes is about what a document observes, and a row exercised through the evaluator is the
 * row a document reaches.
 */
class OperatorsTest {

    private val evaluator: Evaluator = Evaluator()

    private val scope: EvalScope = MapEvalScope(MapStateStore())

    private fun eval(expr: Expr, type: TypeRef = TypeRef.Int32): Value =
        evaluator.eval(TypedExpr(expr, ExprType.Of(type), emptySet()), scope)

    private fun arithmetic(op: BinaryOp, left: Value, right: Value): Expr =
        Expr.Binary(op, Expr.Const(left), Expr.Const(right))

    // ---------------------------------------------------------------------------------
    // §10.6: no implicit numeric conversion, so each pair has its own row
    // ---------------------------------------------------------------------------------

    @Test
    fun `each numeric pair stays in its own type`() {
        assertEquals(Value.Int32(3), eval(arithmetic(BinaryOp.Add, Value.Int32(1), Value.Int32(2))))
        assertEquals(Value.Int64(3), eval(arithmetic(BinaryOp.Add, Value.Int64(1), Value.Int64(2))))
        assertEquals(
            Value.Float32(3f),
            eval(arithmetic(BinaryOp.Add, Value.Float32(1f), Value.Float32(2f)), TypeRef.Float32),
        )
        assertEquals(
            Value.Float64(3.0),
            eval(arithmetic(BinaryOp.Add, Value.Float64(1.0), Value.Float64(2.0)), TypeRef.Float64),
        )
    }

    @Test
    fun `an int and a float have no row between them`() {
        val failure = assertFailsWith<IllegalStateException> {
            eval(arithmetic(BinaryOp.Add, Value.Int32(1), Value.Float64(1.5)))
        }

        val message = failure.message
        assertTrue(message.contains("no implicit numeric conversion") == true, message.orEmpty())
        assertTrue(message.contains("num.toDouble") == true, message.orEmpty())
    }

    @Test
    fun `dp and sp are quantities rather than numbers to add`() {
        assertFailsWith<IllegalStateException> {
            eval(arithmetic(BinaryOp.Add, Value.Dp(1f), Value.Dp(2f)), TypeRef.Dp)
        }
    }

    @Test
    fun `neg takes a number and refuses text`() {
        assertEquals(Value.Int32(-5), eval(Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Int32(5)))))
        assertFailsWith<IllegalStateException> {
            eval(Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Str("x"))), TypeRef.Str)
        }
    }

    // ---------------------------------------------------------------------------------
    // §10.2: Str + Str is concatenation, and it is the only row that takes text
    // ---------------------------------------------------------------------------------

    @Test
    fun `str plus str concatenates`() {
        val expr = arithmetic(BinaryOp.Add, Value.Str("Hi "), Value.Str("there"))

        assertEquals(Value.Str("Hi there"), eval(expr, TypeRef.Str))
    }

    @Test
    fun `mixing text and a number has no row`() {
        assertFailsWith<IllegalStateException> {
            eval(arithmetic(BinaryOp.Add, Value.Str("n="), Value.Int32(1)))
        }
    }

    @Test
    fun `ordering has no text arm`() {
        val failure = assertFailsWith<IllegalStateException> {
            eval(arithmetic(BinaryOp.Lt, Value.Str("a"), Value.Str("b")), TypeRef.Bool)
        }

        val message = failure.message
        assertTrue(message.contains("text comparison is limited to ==") == true, message.orEmpty())
    }

    // ---------------------------------------------------------------------------------
    // §10.6: equality is total, floats compare, and overflow is Kotlin's
    // ---------------------------------------------------------------------------------

    @Test
    fun `equality and inequality compare text`() {
        assertEquals(Value.Bool(true), eval(arithmetic(BinaryOp.Eq, Value.Str("a"), Value.Str("a")), TypeRef.Bool))
        assertEquals(Value.Bool(true), eval(arithmetic(BinaryOp.Neq, Value.Str("a"), Value.Str("b")), TypeRef.Bool))
    }

    @Test
    fun `equality holds for two nulls`() {
        val expr = arithmetic(BinaryOp.Eq, Value.Null, Value.Null)

        assertEquals(Value.Bool(true), eval(expr, TypeRef.Bool))
    }

    @Test
    fun `ordering answers a bool over numbers`() {
        val expr = arithmetic(BinaryOp.Lt, Value.Int32(1), Value.Int32(2))

        assertEquals(Value.Bool(true), eval(expr, TypeRef.Bool))
    }

    @Test
    fun `int arithmetic wraps the way Kotlin does`() {
        val expr = arithmetic(BinaryOp.Add, Value.Int32(Int.MAX_VALUE), Value.Int32(1))

        assertEquals(Value.Int32(Int.MIN_VALUE), eval(expr), "§10.6: both sides run Kotlin")
    }

    @Test
    fun `a float result is not rounded`() {
        val expr = arithmetic(BinaryOp.Add, Value.Float64(0.1), Value.Float64(0.2))

        val found = eval(expr, TypeRef.Float64) as Value.Float64

        assertTrue(found.v != 0.3, "§5.4: an evaluated value is never serialized and never rounded")
    }
}
