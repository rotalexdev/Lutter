package dev.rotalex.lutter.analysis.typing

import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.type.TypeRef

/**
 * Whether a value of one expression type may fill a position of another.
 *
 * §10.4's rules reduce to one question, asked at every node: what the expression produced
 * against what the declaration promised. Two consequences are structural rather than
 * written down anywhere:
 *
 *  * **No implicit numeric conversion (§10.4).** [TypeRef.Int32] and [TypeRef.Float64] are
 *    distinct objects, so no arm below can succeed by widening — the rule is the absence of a
 *    case rather than a check that could be forgotten.
 *  * **Nullability lifts (§10.4).** A non-null value fills a nullable slot, and the reverse
 *    does not, which is the whole of [TypeRef.Nullable]'s meaning for a checker.
 *
 * §10.6's other rows are answered where the operator is, not here: ordering and equality
 * have operand rules that are not about the destination.
 */
internal object Assignability {

    /** Whether [found] may stand where [expected] was promised. */
    public fun accepts(expected: ExprType, found: ExprType): Boolean = when {
        expected is ExprType.Of && found is ExprType.Of -> declared(expected.type, found.type)

        // `Value.Null` fits a nullable and nothing else, which is §5.4's reason for having
        // no `TypeRef.Null`: null is a value the type admits, not a type of its own.
        found is ExprType.Null -> expected is ExprType.Of && expected.type is TypeRef.Nullable

        else -> false
    }

    private fun declared(expected: TypeRef, found: TypeRef): Boolean = when {
        expected == found -> true
        expected is TypeRef.Nullable -> accepts(ExprType.Of(expected.inner), ExprType.Of(found))
        expected is TypeRef.ListOf && found is TypeRef.ListOf ->
            accepts(ExprType.Of(expected.element), ExprType.Of(found.element))

        expected is TypeRef.MapOf && found is TypeRef.MapOf ->
            accepts(ExprType.Of(expected.value), ExprType.Of(found.value))

        else -> false
    }
}
