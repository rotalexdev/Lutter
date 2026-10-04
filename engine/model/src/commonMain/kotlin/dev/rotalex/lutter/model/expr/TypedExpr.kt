package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.type.TypeRef

/**
 * A checked expression: its tree, the type it produces, and every binding it names.
 *
 * PLAN §10.4 declares the triple and nothing else, so each field answers a question a later
 * unit asks — the bidirectional check settled on [type], and §17.1's *scope resolution* is
 * what [refs] holds. A wrapper, not a replacement: the evaluator walks [expr], and a typed
 * tree beside [Expr] would be a second AST for one document to keep in step with.
 *
 * Derived and never persisted (§5.6, §35), which is why it carries no `@Serializable`.
 */
public data class TypedExpr(

    /** The expression as the document wrote it, unevaluated. */
    public val expr: Expr,

    /** What the check agreed the expression produces; no consumer re-derives it. */
    public val type: ExprType,

    /** Every [RefTarget] the expression names, deduplicated. */
    public val refs: Set<RefTarget>,
)

/**
 * What an expression produces: a declared type, or the one type `Value.Null` has.
 *
 * A [TypeRef] alone cannot say it. §5.4 keeps nullability on the type and gives `Value.Null`
 * no type of its own, so an expression that is literally null fits every nullable target and
 * no non-nullable one — a position `TypeRef` has no way to hold. `Failed` is deliberately
 * *not* here: a subtree that already reported is a `null` from the checker, so this shared
 * vocabulary carries no analysis state.
 */
public sealed interface ExprType {

    /** A type a document can declare. Identity with [TypeRef], so nothing converts. */
    public data class Of(public val type: TypeRef) : ExprType

    /** The type of `Value.Null`: any nullable target, and nothing else (§5.4). */
    public data object Null : ExprType
}
