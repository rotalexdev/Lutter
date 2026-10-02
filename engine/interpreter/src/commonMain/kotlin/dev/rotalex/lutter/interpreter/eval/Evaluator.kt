// The evaluation half of the expression system, in one subpackage: the entry point, the
// operator rows, the canonical display text, and the dispatch table a call lands in. §33.4
// files them together and they are only readable together — a reader asking "what does `+`
// do" needs the row, the refusal and the totality rule at the same time.
package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.value.Value

/**
 * §15.3's expression interpreter: a [TypedExpr] and an [EvalScope] in, a [Value] out.
 *
 * ### Two kinds of failure, and the difference is not cosmetic
 *
 * **A value the document does not have is [Value.Null].** A field an object literal left out,
 * a member read of something that is not an object, a safe read of a null: §10.6 makes
 * evaluation total, and `list.get` returning nullable is the row that says so. The evaluator
 * holds this rule in one place rather than per case, so a new case gets it by reading this.
 *
 * **A document that could not have got here at all throws.** An operand pair §10.4 rejects, a
 * call with no implementation, an unsafely-read null. Pass 5 refuses those documents before
 * anything renders them, so a throw means the checker and this half disagree — which §15.3's
 * per-node error boundary is there to report. Answering `Null` instead would make the
 * disagreement invisible and let the two backends differ silently.
 *
 * ### Why a class rather than an object
 *
 * §15.3 says the `FunctionImpl`s come from `Implementations`, so the dispatch table is
 * somebody else's object and has to be held somewhere. A singleton would hold it in a `var`,
 * and this repository's architecture rules refuse exactly that.
 */
public class Evaluator(
    private val functions: FunctionImpls = FunctionImpls.None,
) {

    /**
     * The entry point §15.3 spells: `Evaluator.eval(typedExpr, EvalScope)`.
     *
     * [typed] is walked rather than re-derived. §10.4's type is the checker's promise and no
     * consumer re-derives it, which is why the operator rows below dispatch on the operands'
     * runtime values: a `TypedExpr` carries the root type and the set of names, not a type per
     * node, so the row is where an operand's type is finally known.
     */
    public fun eval(typed: TypedExpr, scope: EvalScope): Value = evaluate(typed.expr, scope)

    /** The nine §10.1 variants, one case each. The table behind two of them does the rest. */
    private fun evaluate(expr: Expr, scope: EvalScope): Value = when (expr) {
        is Expr.Const -> expr.value
        is Expr.Ref -> scope.read(expr.target)
        is Expr.Member -> member(expr, scope)
        is Expr.Call -> call(expr, scope)
        is Expr.Unary -> UnaryOperators.table.getValue(expr.op).apply(evaluate(expr.operand, scope))
        is Expr.Binary -> BinaryOperators.table.getValue(expr.op).apply(evaluate(expr.left, scope)) {
            evaluate(expr.right, scope)
        }
        is Expr.If -> branch(expr, scope)
        is Expr.ListLiteral -> Value.ListOf(expr.items.map { evaluate(it, scope) })
        is Expr.Template -> Value.Str(expr.parts.joinToString("") { evaluate(it, scope).toDisplayString() })
    }

    /**
     * §10.5's field read, over a [Value.Obj].
     *
     * The totality rule's two data cases are here: a receiver that is not an object, and a
     * field the object does not carry. An absent field is real rather than hypothetical —
     * pass 5 checks the name against the declared data model while the value comes from the
     * document, so a literal may legitimately omit one.
     */
    private fun member(expr: Expr.Member, scope: EvalScope): Value {
        val receiver = evaluate(expr.receiver, scope)
        if (receiver is Value.Null) {
            if (expr.safe) return Value.Null
            throw IllegalStateException(
                "'${expr.name}' reads through a null receiver and safe is false; " +
                    "§10.4 requires safe access on a nullable receiver",
            )
        }
        val record = receiver as? Value.Obj ?: return Value.Null
        // Compared by the key's text because `Expr.Member.name` is a bare String: a
        // `PropertyKey` could only be built by re-validating what the document already wrote.
        return record.fields.entries.firstOrNull { it.key.value == expr.name }?.value ?: Value.Null
    }

    /** §10.5's call: the implementation for this id, over the arguments in written order. */
    private fun call(expr: Expr.Call, scope: EvalScope): Value {
        val impl = functions[expr.function] ?: throw IllegalStateException(
            "No FunctionImpl for '${expr.function}'; §10.3 pairs every FunctionSpec with one",
        )
        // Arity is the implementation's own business, because this signature carries no
        // signature: `FunctionImpl` is a `List<Value>` and nothing about the id is here.
        return impl.invoke(expr.args.map { evaluate(it, scope) })
    }

    /** §10.5's lazy `if`. Only the branch the condition takes is evaluated. */
    private fun branch(expr: Expr.If, scope: EvalScope): Value {
        val taken = boolOperand(evaluate(expr.cond, scope), "if")
        return evaluate(if (taken) expr.then else expr.otherwise, scope)
    }
}
