// The evaluation half of the expression system, in one subpackage: the entry point, the
// operator rows, the canonical display text, and the dispatch table a call lands in. §33.4
// files them together and they are only readable together — a reader asking "what does `+`
// do" needs the row, the refusal and the totality rule at the same time.
package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.interpreter.EvalScope
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.FunctionId
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
     * [typed] is compiled rather than re-derived, and the compiled closure is what evaluates.
     * §29.1 precompiles a `TypedExpr` into closures, memoized on this evaluator: the first eval
     * of an expression builds one closure per node of its tree, and every later eval — the next
     * frame, the next state change — runs that closure instead of walking the tree again. The
     * compilation reuses exactly the helpers this class holds ([fieldRead], [branch], [call],
     * the operator rows), so there is one evaluation path and not two; a `TypedExpr` reaches a
     * resolved property and never carries a scope, which is why its closure takes the
     * [EvalScope] at call time rather than closing over one.
     *
     * What is not re-derived is §10.4's type either way: it is the checker's promise and no
     * consumer re-derives it, which is why the operator rows below dispatch on the operands'
     * runtime values. A `TypedExpr` carries the root type and the set of names, not a type per
     * node, so the row is where an operand's type is finally known.
     */
    public fun eval(typed: TypedExpr, scope: EvalScope): Value = compiledClosure(typed)(scope)

    /**
     * The entry a step's argument takes: the raw [Expr] an action argument carries, and a scope.
     *
     * Untyped on purpose — a `TypedExpr` reaches a resolved property and nothing else, so an
     * argument has none to hand, and typing one here would be a second checker beside the first.
     * A refusal throws as it does through [eval], which is why a handler catches it.
     */
    public fun evalUnchecked(expr: Expr, scope: EvalScope): Value = evaluate(expr, scope)

    /**
     * The compiled closures, keyed by the `TypedExpr` they were compiled from.
     *
     * The key is the [TypedExpr] itself, compared as the data class it is. Structural equality
     * is the honest key: two `TypedExpr`s that are equal carry trees that evaluate alike, so
     * sharing one closure between them is a cache hit that cannot change an answer. Keying on
     * identity would need an identity-keyed map, which common code has no portable spelling of,
     * and the structural cost — hashing the tree on every lookup — is a walk that reads no
     * scope, dispatches no operator and allocates no [Value], so it stays cheaper than the
     * evaluation a miss would have paid for.
     *
     * The map lives on the evaluator and nowhere shared because a render runtime is one
     * `Evaluator` (`UiRuntime.evaluator` holds its own), so the map's lifetime is the runtime's
     * and its size is bounded by the distinct resolved expressions that runtime has rendered.
     * Evaluation happens on the runtime's single render path, which is all a plain map needs.
     */
    private val compiledClosures: MutableMap<TypedExpr, (EvalScope) -> Value> = mutableMapOf()

    /** The compiler over this evaluator's helpers, so a closure cannot grow a second semantics. */
    private val compiler: ClosureCompiler = ClosureCompiler(this)

    /** The closure for [typed], compiled on first use and reused for every later one (§29.1). */
    private fun compiledClosure(typed: TypedExpr): (EvalScope) -> Value =
        compiledClosures.getOrPut(typed) { compiler.compile(typed.expr) }

    /**
     * The walk the untyped entries take, one case per variant.
     *
     * This is [evalUnchecked]'s path: an action argument arrives as a raw `Expr` with no
     * `TypedExpr` to compile. The checked entry runs closures compiled out of the same helpers
     * this walk calls, so the two shapes are one vocabulary and not two evaluators.
     */
    private fun evaluate(expr: Expr, scope: EvalScope): Value = when (expr) {
        is Expr.Const -> expr.value
        is Expr.Ref -> scope.read(expr.target)
        is Expr.Member -> fieldRead(evaluate(expr.receiver, scope), expr.name, expr.safe)
        is Expr.Call -> call(expr.function, expr.args.map { evaluate(it, scope) })
        is Expr.Unary -> UnaryOperators.table.getValue(expr.op).apply(evaluate(expr.operand, scope))
        is Expr.Binary -> BinaryOperators.table.getValue(expr.op).apply(evaluate(expr.left, scope)) {
            evaluate(expr.right, scope)
        }
        is Expr.If -> branch(
            evaluate(expr.cond, scope),
            { evaluate(expr.then, scope) },
            { evaluate(expr.otherwise, scope) },
        )
        is Expr.ListLiteral -> Value.ListOf(expr.items.map { evaluate(it, scope) })
        is Expr.Template -> Value.Str(expr.parts.joinToString("") { evaluate(it, scope).toDisplayString() })
    }

    /**
     * §10.5's field read, over an already-evaluated receiver.
     *
     * The totality rule's two data cases are here: a receiver that is not an object, and a
     * field the object does not carry. An absent field is real rather than hypothetical —
     * pass 5 checks the name against the declared data model while the value comes from the
     * document, so a literal may legitimately omit one.
     *
     * The receiver arrives evaluated rather than as an [Expr.Member] so both paths read this:
     * the walk evaluates its receiver and hands it here, and a compiled closure hands it the
     * value its own receiver closure produced. One definition, one totality rule.
     */
    internal fun fieldRead(receiver: Value, name: String, safe: Boolean): Value {
        if (receiver is Value.Null) {
            if (safe) return Value.Null
            throw IllegalStateException(
                "'$name' reads through a null receiver and safe is false; " +
                    "§10.4 requires safe access on a nullable receiver",
            )
        }
        val record = receiver as? Value.Obj ?: return Value.Null
        // Compared by the key's text because `Expr.Member.name` is a bare String: a
        // `PropertyKey` could only be built by re-validating what the document already wrote.
        return record.fields.entries.firstOrNull { it.key.value == name }?.value ?: Value.Null
    }

    /** §10.5's call: the implementation for [function], over the arguments in written order. */
    internal fun call(function: FunctionId, args: List<Value>): Value {
        val impl = functions[function] ?: throw IllegalStateException(
            "No FunctionImpl for '$function'; §10.3 pairs every FunctionSpec with one",
        )
        // Arity is the implementation's own business, because this signature carries no
        // signature: `FunctionImpl` is a `List<Value>` and nothing about the id is here.
        return impl.invoke(args)
    }

    /** §10.5's lazy `if`. Only the branch the condition takes is evaluated. */
    internal fun branch(cond: Value, then: () -> Value, otherwise: () -> Value): Value =
        if (boolOperand(cond, "if")) then() else otherwise()
}
