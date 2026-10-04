package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * How a `RefTarget.State` reads in the construct being emitted.
 *
 * §12.2 makes the *shape* a [StateStrategy]'s and §6.2 makes the identifier the document's
 * `StateDecl.name`, so neither is derivable from a [RefTarget] on its own — it carries an id
 * and nothing else. The read is a function of which file holds the reference, which is why this
 * is a parameter rather than a field: a screen reads `state.count`, a state class reads `count`
 * and the same document needs both.
 */
public fun interface StateRead {
    /** The read of [id], or null when no declaration the emitter can see carries it. */
    public fun read(id: StateId): KtExpr?
}

/**
 * A checked expression as the Kotlin the printer renders: §10.5's codegen column, node for node.
 *
 * Only [TypedExpr.expr] is read. [TypedExpr.type] and [TypedExpr.refs] were settled by §10.4
 * before this point, and emission is a spelling job: nothing here re-derives what the check
 * already agreed. Every `RefTarget` inside [TypedExpr.refs] is a *target* and never an
 * [Expr.Ref], so the two are read from different places and never from each other.
 *
 * The printer owns rendering and the parentheses that come with it, so this hands over
 * expressions — including the arguments of a [FunctionSpec]'s template — and never text.
 */
public class ExprEmitter(
    private val functions: Registry<FunctionId, FunctionSpec>,
    private val state: StateRead,
) {

    /** [typed] as a Kotlin expression, or a [CodegenBug] naming what has no spelling. */
    public fun emit(typed: TypedExpr): KtExpr = emitExpr(typed.expr)

    private fun emitExpr(expr: Expr): KtExpr = when (expr) {
        is Expr.Const -> literal(expr.value)
        is Expr.Ref -> reference(expr.target)
        is Expr.Member -> KtExpr.Member(emitExpr(expr.receiver), expr.name, expr.safe)
        is Expr.Call -> call(expr)
        is Expr.Unary -> KtExpr.Unary(opOf(expr.op), emitExpr(expr.operand))
        is Expr.Binary -> KtExpr.Binary(opOf(expr.op), emitExpr(expr.left), emitExpr(expr.right))
        is Expr.If -> KtExpr.IfElse(emitExpr(expr.cond), emitExpr(expr.then), emitExpr(expr.otherwise))
        is Expr.ListLiteral -> listLiteral(expr)
        is Expr.Template -> template(expr)
    }

    /**
     * A value as a literal, through [LiteralPrinter] — the one place escaping and the Compose
     * spellings live, so a `Value.Dp` reaching the printer is `8.0.dp` and carries `dp`.
     */
    private fun literal(value: Value): KtExpr {
        if (value is Value.Enum) {
            throw CodegenBug(
                "No Kotlin literal for " + value + ": an enum entry spells itself through its spec",
            )
        }
        val emitted: EmittedLiteral = LiteralPrinter.emit(value) ?: throw CodegenBug(
            "No Kotlin literal for " + value + ": §9.2 settles it as a symbol, not a literal",
        )
        return KtExpr.Literal(emitted.text, emitted.symbols)
    }

    private fun reference(target: RefTarget): KtExpr = when (target) {
        is RefTarget.Param -> KtExpr.Name(target.name.value)
        is RefTarget.EventArg -> KtExpr.Name(target.name)
        // §12.1 writes the identifier the document declared and §12.2 makes the receiver the
        // strategy's own choice, so [state] answers with both. Null is a lost declaration rather
        // than a document fault: pass 5 refuses an unresolved `RefTarget.State` upstream.
        is RefTarget.State -> state.read(target.id) ?: throw CodegenBug(
            "State '" + target.id.value + "' left the binding table; pass 5 refused an unresolved one",
        )
        // §10.2 has no iteration, so nothing binds this name; [RefTarget]'s own KDoc defers the
        // scope check for it to the wave that introduces one.
        is RefTarget.Item -> throw CodegenBug(
            "No Kotlin spelling for item '" + target.name + "': §10.2 has no iteration to bind it",
        )
    }

    /**
     * A call as its [FunctionSpec]'s filled template.
     *
     * The arguments stay expressions rather than becoming text, because a `{0}` may sit where
     * only an atom is safe — `{0}.size` — and only the printer knows the level a placeholder
     * needs. Nothing here reads the pattern: `renderPatternCall` owns that grammar, and it is a
     * different one from `fillPattern`'s `{key}`.
     */
    private fun call(expr: Expr.Call): KtExpr {
        val spec: FunctionSpec = functions[expr.function] ?: throw CodegenBug(
            "No FunctionSpec for '" + expr.function.value + "'",
        )
        return KtExpr.PatternCall(
            pattern = spec.kotlin.pattern,
            args = expr.args.map { emitExpr(it) },
            imports = spec.kotlin.imports,
            precedence = spec.kotlin.precedence,
        )
    }

    /** `listOf(a, b)`: a [KtExpr.Call] over a default-imported symbol, so no import is written. */
    private fun listLiteral(expr: Expr.ListLiteral): KtExpr = KtExpr.Call(
        KtExpr.Ref(KtSymbolRef(KotlinSymbol("kotlin.collections", "listOf"))),
        expr.items.map { KtArg(null, emitExpr(it)) },
    )

    /**
     * Interpolation as literal text plus interpolations, a string constant being text here
     * because the template supplies the quotes. Its `$` is escaped: a template is the one
     * place an unescaped `$` would start an expression.
     */
    private fun template(expr: Expr.Template): KtExpr =
        KtExpr.StringTemplate(expr.parts.map { part -> templatePart(part) })

    private fun templatePart(part: Expr): KtTemplatePart {
        val constant: Value? = (part as? Expr.Const)?.value
        return when (constant) {
            is Value.Str -> KtTemplatePart.Text(LiteralPrinter.escape(constant.v))
            is Value.Url -> KtTemplatePart.Text(LiteralPrinter.escape(constant.v))
            else -> KtTemplatePart.Interpolation(emitExpr(part))
        }
    }

    // Table-driven like §10.5's operator column, and exhaustively: the compiler refuses an
    // operator added to the model without a spelling here, which is the pairing §10.5 wants.
    private fun opOf(op: BinaryOp): KtOp = when (op) {
        BinaryOp.Add -> KtOp.Add
        BinaryOp.Sub -> KtOp.Sub
        BinaryOp.Mul -> KtOp.Mul
        BinaryOp.Eq -> KtOp.Eq
        BinaryOp.Neq -> KtOp.Neq
        BinaryOp.Lt -> KtOp.Lt
        BinaryOp.Le -> KtOp.Le
        BinaryOp.Gt -> KtOp.Gt
        BinaryOp.Ge -> KtOp.Ge
        BinaryOp.And -> KtOp.And
        BinaryOp.Or -> KtOp.Or
    }

    private fun opOf(op: UnaryOp): KtOp = when (op) {
        UnaryOp.Not -> KtOp.Not
        UnaryOp.Neg -> KtOp.Neg
    }
}
