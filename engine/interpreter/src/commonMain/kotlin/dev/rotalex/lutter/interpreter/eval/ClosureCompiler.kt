package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.value.Value

/**
 * §29.1's expression compilation: a `TypedExpr`'s tree becomes one closure per node, built once
 * and invoked for every later evaluation.
 *
 * The closure this returns takes its [EvalScope] at call time and closes over nothing but its
 * sub-closures and the constants its subtree spells. That is the whole memoization contract:
 * the compiled form is the tree in evaluation order, so one compiled expression answers
 * correctly for any scope the caller hands it, and the cache holding closures can be keyed by
 * the tree alone. A scope closed over at compile time would key the cache into a lie instead —
 * a second evaluation with a different scope would answer the first scope's values.
 *
 * ### The one invariant this class has
 *
 * **A closure reuses the [Evaluator] helpers; it never states a semantics rule of its own.**
 * [Evaluator.fieldRead], [Evaluator.branch], [Evaluator.call] and the operator rows are the
 * single definition of §10.5's totality rules and §10.6's refusals, and the compiled path runs
 * through them on purpose: a closure that spelled a rule differently would be a second
 * evaluator, and the two backends would be free to disagree again. Compiling therefore does
 * exactly one thing per variant — bind sub-closures and constants — and hands every decision
 * about values to the helper the arm calls.
 *
 * Reachable only from [Evaluator], which owns the memoization: one map per evaluator, one
 * evaluator per render runtime. This class holds no state of its own, so a compile is a pure
 * walk of the tree and the closures it returns are the only thing that survives it.
 */
internal class ClosureCompiler(private val evaluator: Evaluator) {

    /** The nine §10.1 variants, one closure each, in the same order the walk reads them. */
    internal fun compile(expr: Expr): (EvalScope) -> Value = when (expr) {
        is Expr.Const -> {
            val value = expr.value
            { _: EvalScope -> value }
        }

        is Expr.Ref -> {
            val target = expr.target
            { scope: EvalScope -> scope.read(target) }
        }

        is Expr.Member -> {
            val receiver = compile(expr.receiver)
            val name = expr.name
            val safe = expr.safe
            { scope: EvalScope -> evaluator.fieldRead(receiver(scope), name, safe) }
        }

        is Expr.Call -> {
            val function = expr.function
            val argClosures = expr.args.map { compile(it) }
            { scope: EvalScope -> evaluator.call(function, argClosures.map { it(scope) }) }
        }

        is Expr.Unary -> {
            val row = UnaryOperators.table.getValue(expr.op)
            val operand = compile(expr.operand)
            { scope: EvalScope -> row.apply(operand(scope)) }
        }

        is Expr.Binary -> {
            val row = BinaryOperators.table.getValue(expr.op)
            val left = compile(expr.left)
            val right = compile(expr.right)
            { scope: EvalScope -> row.apply(left(scope)) { right(scope) } }
        }

        is Expr.If -> {
            val cond = compile(expr.cond)
            val thenBranch = compile(expr.then)
            val otherwiseBranch = compile(expr.otherwise)
            { scope: EvalScope -> evaluator.branch(cond(scope), { thenBranch(scope) }, { otherwiseBranch(scope) }) }
        }

        is Expr.ListLiteral -> {
            val itemClosures = expr.items.map { compile(it) }
            { scope: EvalScope -> Value.ListOf(itemClosures.map { it(scope) }) }
        }

        is Expr.Template -> {
            val partClosures = expr.parts.map { compile(it) }
            { scope: EvalScope -> Value.Str(partClosures.joinToString("") { it(scope).toDisplayString() }) }
        }
    }
}
