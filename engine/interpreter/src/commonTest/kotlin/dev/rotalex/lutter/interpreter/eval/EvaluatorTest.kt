package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.interpreter.MapEvalScope
import dev.rotalex.lutter.interpreter.MapStateStore
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.kind.ValueKinds
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The nine §10.1 variants, and the two kinds of failure that are not the same thing.
 *
 * §10.6 makes evaluation total, so a value the document does not have is `Value.Null`. What it
 * does *not* make total is a document pass 5 refused, and those throw: answering `Null` there
 * would let the checker and the evaluator disagree without either of them saying so.
 */
class EvaluatorTest {

    private val count: StateId = StateId("s_count")

    /** §10.2's seed set arrives with `:engine:builtins`; this is the smallest one. */
    private val echo: FunctionId = FunctionId("test.echo")

    private val evaluator: Evaluator = Evaluator()

    private val scope: EvalScope = MapEvalScope(MapStateStore(mapOf(count to Value.Int32(3))))

    private fun typed(expr: Expr, type: TypeRef): TypedExpr = TypedExpr(expr, ExprType.Of(type), emptySet())

    private fun eval(expr: Expr, type: TypeRef): Value = evaluator.eval(typed(expr, type), scope)

    /**
     * An expression that cannot evaluate at all, so a row that skips its right side is the only
     * way to produce an answer. A side-effect counter would prove the same thing and would also
     * pass if evaluation were reordered; a side that throws cannot be survived.
     */
    private fun unreachable(): Expr = Expr.Call(FunctionId("test.missing"), emptyList())

    private fun user(fields: Map<PropertyKey, Value>): Expr =
        Expr.Const(Value.Obj(TypeId("User"), fields))

    // ---------------------------------------------------------------------------------
    // The nine variants
    // ---------------------------------------------------------------------------------

    @Test
    fun `a constant evaluates to itself`() {
        assertEquals(Value.Str("Hi"), eval(Expr.Const(Value.Str("Hi")), TypeRef.Str))
    }

    @Test
    fun `a state reference reads through the scope`() {
        val expr = Expr.Ref(RefTarget.State(count))

        assertEquals(Value.Int32(3), eval(expr, TypeRef.Int32))
    }

    @Test
    fun `a member reads a field of its receiver`() {
        val expr = Expr.Member(user(mapOf(PropertyKey("name") to Value.Str("Ada"))), "name")

        assertEquals(Value.Str("Ada"), eval(expr, TypeRef.Str))
    }

    @Test
    fun `a call runs the implementation registered for its id`() {
        val registry = RegistryBuilder<FunctionId, FunctionImpl>()
        registry.register(echo, FunctionImpl { args -> args.single() })
        val subject = typed(Expr.Call(echo, listOf(Expr.Const(Value.Str("Hi")))), TypeRef.Str)

        assertEquals(Value.Str("Hi"), Evaluator(FunctionImpls(registry.build())).eval(subject, scope))
    }

    @Test
    fun `a unary operator applies to its one operand`() {
        assertEquals(Value.Int32(-5), eval(Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Int32(5))), TypeRef.Int32))
        assertEquals(
            Value.Bool(false),
            eval(Expr.Unary(UnaryOp.Not, Expr.Const(Value.Bool(true))), TypeRef.Bool),
        )
    }

    @Test
    fun `a binary operator applies to both operands`() {
        val expr = Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2)))

        assertEquals(Value.Int32(3), eval(expr, TypeRef.Int32))
    }

    @Test
    fun `an if answers with the branch its condition takes`() {
        val expr = Expr.If(
            Expr.Const(Value.Bool(true)),
            Expr.Const(Value.Int32(1)),
            Expr.Const(Value.Int32(2)),
        )

        assertEquals(Value.Int32(1), eval(expr, TypeRef.Int32))
    }

    @Test
    fun `a list literal evaluates its items in order`() {
        val expr = Expr.ListLiteral(listOf(Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2))))

        assertEquals(
            Value.ListOf(listOf(Value.Int32(1), Value.Int32(2))),
            eval(expr, TypeRef.ListOf(TypeRef.Int32)),
        )
    }

    @Test
    fun `a template concatenates its parts`() {
        val expr = Expr.Template(listOf(Expr.Const(Value.Str("Hi ")), Expr.Const(Value.Int32(1))))

        assertEquals(Value.Str("Hi 1"), eval(expr, TypeRef.Str))
    }

    @Test
    fun `eval answers from the tree and never reads the settled type`() {
        // §10.4's type is the checker's promise. An evaluator that consulted it would let a
        // stale one change a value silently rather than failing, which is the one thing a
        // re-derivation in the evaluator would have cost.
        val subject = TypedExpr(Expr.Const(Value.Str("Hi")), ExprType.Of(TypeRef.Int32), emptySet())

        assertEquals(Value.Str("Hi"), evaluator.eval(subject, scope))
    }

    // ---------------------------------------------------------------------------------
    // The entry a step's argument takes, where no checked type exists to walk
    // ---------------------------------------------------------------------------------

    @Test
    fun `an unchecked constant answers with its own value`() {
        assertEquals(Value.Str("Hi"), evaluator.evalUnchecked(Expr.Const(Value.Str("Hi")), scope))
    }

    @Test
    fun `an unchecked reference resolves through the scope`() {
        assertEquals(Value.Int32(3), evaluator.evalUnchecked(Expr.Ref(RefTarget.State(count)), scope))
    }

    @Test
    fun `an unchecked expression answers what its checked form answers`() {
        val expr = Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1)))

        assertEquals(evaluator.eval(typed(expr, TypeRef.Int32), scope), evaluator.evalUnchecked(expr, scope))
    }

    /**
     * No operator is missing a row — the tables cover the whole enum — so the refusal an
     * unchecked tree can hit is a row refusing the pair it was given.
     */
    @Test
    fun `an unchecked operand pair the rows refuse throws naming the operator`() {
        val expr = Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Str("x")))

        val failure = assertFailsWith<IllegalStateException> { evaluator.evalUnchecked(expr, scope) }

        assertTrue(failure.message?.contains("'add'") == true, failure.message.orEmpty())
    }

    @Test
    fun `an unchecked call with no registered implementation throws naming the id`() {
        val failure = assertFailsWith<IllegalStateException> { evaluator.evalUnchecked(unreachable(), scope) }

        assertTrue(failure.message?.contains("test.missing") == true, failure.message.orEmpty())
    }

    // ---------------------------------------------------------------------------------
    // Laziness, which is a promise about both backends rather than about this one
    // ---------------------------------------------------------------------------------

    @Test
    fun `or answers true without reading a right side that would throw`() {
        val expr = Expr.Binary(BinaryOp.Or, Expr.Const(Value.Bool(true)), unreachable())

        assertEquals(Value.Bool(true), eval(expr, TypeRef.Bool))
    }

    @Test
    fun `and answers false without reading a right side that would throw`() {
        val expr = Expr.Binary(BinaryOp.And, Expr.Const(Value.Bool(false)), unreachable())

        assertEquals(Value.Bool(false), eval(expr, TypeRef.Bool))
    }

    @Test
    fun `and still reads the right side when the left one does not answer`() {
        val expr = Expr.Binary(BinaryOp.And, Expr.Const(Value.Bool(true)), Expr.Const(Value.Bool(true)))

        assertEquals(Value.Bool(true), eval(expr, TypeRef.Bool))
    }

    @Test
    fun `if does not evaluate the branch it does not take`() {
        val expr = Expr.If(Expr.Const(Value.Bool(true)), Expr.Const(Value.Int32(1)), unreachable())

        assertEquals(Value.Int32(1), eval(expr, TypeRef.Int32))
    }

    // ---------------------------------------------------------------------------------
    // A value the document does not have is Null
    // ---------------------------------------------------------------------------------

    @Test
    fun `a field the receiver left out reads as null`() {
        val expr = Expr.Member(user(emptyMap()), "name")

        assertEquals(Value.Null, eval(expr, TypeRef.Object(TypeId("User"))))
    }

    @Test
    fun `a field read of something that is not an object reads as null`() {
        val expr = Expr.Member(Expr.Const(Value.Str("not an object")), "name")

        assertEquals(Value.Null, eval(expr, TypeRef.Str))
    }

    @Test
    fun `a safe read of null reads as null`() {
        val read = Expr.Member(Expr.Const(Value.Null), "name", safe = true)

        assertEquals(Value.Null, evaluator.eval(TypedExpr(read, ExprType.Null, emptySet()), scope))
    }

    // ---------------------------------------------------------------------------------
    // A document that could not have got here throws
    // ---------------------------------------------------------------------------------

    @Test
    fun `a call with no registered implementation throws naming the id`() {
        val failure = assertFailsWith<IllegalStateException> { eval(unreachable(), TypeRef.Str) }

        assertTrue(failure.message?.contains("test.missing") == true, failure.message.orEmpty())
    }

    @Test
    fun `an operand pair the checker refuses throws naming the rule`() {
        val expr = Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Float64(1.5)))

        val failure = assertFailsWith<IllegalStateException> { eval(expr, TypeRef.Str) }

        assertTrue(failure.message?.contains("no implicit numeric conversion") == true, failure.message.orEmpty())
    }

    @Test
    fun `a non-bool condition throws`() {
        val expr = Expr.If(Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2)))

        assertFailsWith<IllegalStateException> { eval(expr, TypeRef.Int32) }
    }

    @Test
    fun `a non-bool logical operand throws`() {
        val expr = Expr.Binary(BinaryOp.And, Expr.Const(Value.Str("x")), Expr.Const(Value.Bool(true)))

        assertFailsWith<IllegalStateException> { eval(expr, TypeRef.Bool) }
    }

    @Test
    fun `reading through null with safe false throws`() {
        val expr = Expr.Member(Expr.Const(Value.Null), "name", safe = false)

        assertFailsWith<IllegalStateException> { eval(expr, TypeRef.Object(TypeId("User"))) }
    }

    // ---------------------------------------------------------------------------------
    // The two halves, checked from this side
    // ---------------------------------------------------------------------------------

    @Test
    fun `the value produced is the kind the checker's settled type names`() {
        // The twin of `ExpressionPassTest`'s pairing. §23.3 keeps these two modules apart, so
        // the agreement cannot be one test; `ValueKinds` is the table both halves can read,
        // and reading it from this side is what makes a drift a red test rather than two
        // lists that happen to agree today.
        val cases = listOf(
            typed(
                Expr.Template(listOf(Expr.Const(Value.Str("Hi ")), Expr.Const(Value.Int32(1)))),
                TypeRef.Str,
            ) to Value.Str("Hi 1"),
            typed(
                Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2))),
                TypeRef.Int32,
            ) to Value.Int32(3),
        )

        for ((subject, expected) in cases) {
            val produced = evaluator.eval(subject, scope)
            assertEquals(expected, produced, "$subject")
            val declared = (subject.type as ExprType.Of).type
            assertTrue(
                ValueKinds.kindFor(declared).accepts(produced),
                "checker settled '$declared' and the evaluator produced a $produced",
            )
        }
    }
}
