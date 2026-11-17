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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * §29.1's compiled closures, reached through [Evaluator.eval] and never through the compiler.
 *
 * The closures are the evaluation path for a `TypedExpr`, so what this file pins is what a
 * document observes: every variant answers what it answered before, the same compiled
 * expression answers per scope rather than per compilation, and a refusal stays a refusal on
 * every evaluation. Each variant is also read through [Evaluator.evalUnchecked]'s walk, because
 * the compiled path and the walk run the same helpers and a disagreement between them is the
 * second-evaluator drift §29.1's compilation exists not to create.
 *
 * The §10.6 rows and the totality cases (absent field, non-object receiver, safe read of null,
 * short-circuit) are `EvaluatorTest`'s and `OperatorsTest`'s, and they reach the compiled path
 * through `eval` now; this file adds only what they do not already say.
 */
class ClosureCompilationTest {

    private val count: StateId = StateId("s_count")

    /** §10.2's seed set arrives with `:engine:builtins`; this is the smallest one. */
    private val echo: FunctionId = FunctionId("test.echo")

    private val evaluator: Evaluator = Evaluator(
        FunctionImpls.of(listOf(echo to FunctionImpl { args -> args.single() })),
    )

    private fun typed(expr: Expr, type: TypeRef): TypedExpr = TypedExpr(expr, ExprType.Of(type), emptySet())

    private fun scopeOf(countValue: Int): EvalScope =
        MapEvalScope(MapStateStore(mapOf(count to Value.Int32(countValue))))

    /** One case per §10.1 variant, spelled the way the resolved properties of a document are. */
    private data class Case(val name: String, val expr: Expr, val type: TypeRef, val expected: Value)

    private val variants: List<Case> = listOf(
        Case("const", Expr.Const(Value.Str("Hi")), TypeRef.Str, Value.Str("Hi")),
        Case("ref", Expr.Ref(RefTarget.State(count)), TypeRef.Int32, Value.Int32(3)),
        Case(
            "member",
            Expr.Member(
                Expr.Const(Value.Obj(TypeId("User"), mapOf(PropertyKey("name") to Value.Str("Ada")))),
                "name",
            ),
            TypeRef.Str,
            Value.Str("Ada"),
        ),
        Case("call", Expr.Call(echo, listOf(Expr.Const(Value.Int32(1)))), TypeRef.Int32, Value.Int32(1)),
        Case("unary", Expr.Unary(UnaryOp.Neg, Expr.Const(Value.Int32(5))), TypeRef.Int32, Value.Int32(-5)),
        Case(
            "binary",
            Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1))),
            TypeRef.Int32,
            Value.Int32(4),
        ),
        Case(
            "if",
            Expr.If(Expr.Const(Value.Bool(true)), Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2))),
            TypeRef.Int32,
            Value.Int32(1),
        ),
        Case(
            "list",
            Expr.ListLiteral(listOf(Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1)))),
            TypeRef.ListOf(TypeRef.Int32),
            Value.ListOf(listOf(Value.Int32(3), Value.Int32(1))),
        ),
        Case(
            "template",
            Expr.Template(listOf(Expr.Const(Value.Str("n=")), Expr.Ref(RefTarget.State(count)))),
            TypeRef.Str,
            Value.Str("n=3"),
        ),
    )

    @Test
    fun `every variant evaluates alike through the compiled path and the walk`() {
        val scope = scopeOf(3)

        for (case in variants) {
            val subject = typed(case.expr, case.type)

            assertEquals(case.expected, evaluator.eval(subject, scope), case.name)
            assertEquals(case.expected, evaluator.evalUnchecked(case.expr, scope), case.name)
        }
    }

    /**
     * The memoization's one observable promise: the compiled closure takes its scope at call
     * time, so a second evaluation of the same `TypedExpr` — a cache hit — answers with the
     * second scope's values and not the first compilation's.
     */
    @Test
    fun `the same typed expression answers per scope`() {
        val subject = typed(Expr.Ref(RefTarget.State(count)), TypeRef.Int32)

        assertEquals(Value.Int32(3), evaluator.eval(subject, scopeOf(3)))
        assertEquals(Value.Int32(7), evaluator.eval(subject, scopeOf(7)))
    }

    /**
     * The cache key is the `TypedExpr` compared as the data class it is, so two structurally
     * equal expressions share one compiled closure — and that sharing has to be a hit that
     * cannot change an answer, which is what the second evaluation pins.
     */
    @Test
    fun `a structurally equal expression shares its compiled closure`() {
        val expr = Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1)))
        val first = typed(expr, TypeRef.Int32)
        val second = typed(expr, TypeRef.Int32)

        assertEquals(Value.Int32(4), evaluator.eval(first, scopeOf(3)))
        assertEquals(Value.Int32(8), evaluator.eval(second, scopeOf(7)))
    }

    /** A compiled closure is not a memoized answer: what threw once throws on every evaluation. */
    @Test
    fun `a compiled expression that refuses refuses on every evaluation`() {
        val subject = typed(Expr.Call(FunctionId("test.missing"), emptyList()), TypeRef.Str)

        assertFailsWith<IllegalStateException> { evaluator.eval(subject, scopeOf(3)) }
        assertFailsWith<IllegalStateException> { evaluator.eval(subject, scopeOf(3)) }
    }
}
