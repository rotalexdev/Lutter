package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.value.Value

/**
 * What §10.6's rows say about one binary operator, written as a row rather than as a branch
 * in [Evaluator].
 *
 * [right] is a thunk so that a row which skips its right side says so in the row. `and` and
 * `or` are the two that never call it, and short-circuit is a promise about *both* backends,
 * so it belongs where the operator is defined rather than in a caller that would have to know
 * which rows skip.
 */
internal fun interface BinaryRule {

    /** Applies the operator to an evaluated [left] and a right side that may not be evaluated. */
    public fun apply(left: Value, right: () -> Value): Value
}

/** What one unary operator accepts and gives. Two rows, so the shape mirrors [BinaryRule]. */
internal fun interface UnaryRule {

    /** Applies the operator to an evaluated [operand]. */
    public fun apply(operand: Value): Value
}

/**
 * The eleven binary rows, in §10.1's order.
 *
 * ### Why the table dispatches on operand *values*
 *
 * §10.4's rule is about a pair: two sides must already be the same type, so `1 + 1.5` is not
 * a missing row but a pair this table has no answer for. That is what makes "no implicit
 * numeric conversion" true at runtime rather than only in a diagnostic — the arithmetic below
 * has no arm that could widen an `Int32` to a `Double`, because there is no arm for the pair
 * at all.
 *
 * Overflow is Kotlin's, deliberately: §10.6 rules that both sides run Kotlin and so wrap the
 * same way, and reproducing `Int` wrap-around by hand would be a second answer to keep.
 *
 * The results are not canonicalized. §5.4 says a value produced by evaluation is never
 * serialized and may hold any finite double, and rounding it here would make a sum depend on
 * the formatter rather than on the arithmetic.
 */
internal object BinaryOperators {

    /** One row per operator; the evaluator's `Binary` case is this lookup and nothing else. */
    public val table: Map<BinaryOp, BinaryRule> = mapOf(
        BinaryOp.Add to arithmetic(::add),
        BinaryOp.Sub to arithmetic(::subtract),
        BinaryOp.Mul to arithmetic(::multiply),
        BinaryOp.Lt to ordering(::lessThan),
        BinaryOp.Le to ordering(::atMost),
        BinaryOp.Gt to ordering(::greaterThan),
        BinaryOp.Ge to ordering(::atLeast),
        BinaryOp.Eq to EQUAL,
        BinaryOp.Neq to UNEQUAL,
        BinaryOp.And to CONJUNCTION,
        BinaryOp.Or to DISJUNCTION,
    )
}

/**
 * The two unary rows. `not` is §10.4's bool rule and `neg` its numeric rule; neither widens,
 * and negating `Int.MIN_VALUE` wraps, which is what Kotlin does on both sides.
 */
internal object UnaryOperators {

    /** One row per operator: §10.1's enum has two entries and this table has two rows. */
    public val table: Map<UnaryOp, UnaryRule> = mapOf(
        UnaryOp.Not to UnaryRule { operand -> Value.Bool(!boolOperand(operand, "not")) },
        UnaryOp.Neg to UnaryRule { operand -> negate(operand) },
    )
}

/** `Str + Str` is §10.2's concatenation: no separate operator and no `str.*` function for it. */
private fun add(left: Value, right: Value): Value = when {
    left is Value.Int32 && right is Value.Int32 -> Value.Int32(left.v + right.v)
    left is Value.Int64 && right is Value.Int64 -> Value.Int64(left.v + right.v)
    left is Value.Float32 && right is Value.Float32 -> Value.Float32(left.v + right.v)
    left is Value.Float64 && right is Value.Float64 -> Value.Float64(left.v + right.v)
    left is Value.Str && right is Value.Str -> Value.Str(left.v + right.v)
    else -> refuseOperands(BinaryOp.Add, left, right)
}

private fun subtract(left: Value, right: Value): Value = when {
    left is Value.Int32 && right is Value.Int32 -> Value.Int32(left.v - right.v)
    left is Value.Int64 && right is Value.Int64 -> Value.Int64(left.v - right.v)
    left is Value.Float32 && right is Value.Float32 -> Value.Float32(left.v - right.v)
    left is Value.Float64 && right is Value.Float64 -> Value.Float64(left.v - right.v)
    else -> refuseOperands(BinaryOp.Sub, left, right)
}

private fun multiply(left: Value, right: Value): Value = when {
    left is Value.Int32 && right is Value.Int32 -> Value.Int32(left.v * right.v)
    left is Value.Int64 && right is Value.Int64 -> Value.Int64(left.v * right.v)
    left is Value.Float32 && right is Value.Float32 -> Value.Float32(left.v * right.v)
    left is Value.Float64 && right is Value.Float64 -> Value.Float64(left.v * right.v)
    else -> refuseOperands(BinaryOp.Mul, left, right)
}

/** §10.6 rules locale-dependent ordering out, so none of these four has a text arm. */
private fun lessThan(left: Value, right: Value): Boolean = when {
    left is Value.Int32 && right is Value.Int32 -> left.v < right.v
    left is Value.Int64 && right is Value.Int64 -> left.v < right.v
    left is Value.Float32 && right is Value.Float32 -> left.v < right.v
    left is Value.Float64 && right is Value.Float64 -> left.v < right.v
    else -> refuseOperands(BinaryOp.Lt, left, right)
}

private fun atMost(left: Value, right: Value): Boolean = when {
    left is Value.Int32 && right is Value.Int32 -> left.v <= right.v
    left is Value.Int64 && right is Value.Int64 -> left.v <= right.v
    left is Value.Float32 && right is Value.Float32 -> left.v <= right.v
    left is Value.Float64 && right is Value.Float64 -> left.v <= right.v
    else -> refuseOperands(BinaryOp.Le, left, right)
}

private fun greaterThan(left: Value, right: Value): Boolean = when {
    left is Value.Int32 && right is Value.Int32 -> left.v > right.v
    left is Value.Int64 && right is Value.Int64 -> left.v > right.v
    left is Value.Float32 && right is Value.Float32 -> left.v > right.v
    left is Value.Float64 && right is Value.Float64 -> left.v > right.v
    else -> refuseOperands(BinaryOp.Gt, left, right)
}

private fun atLeast(left: Value, right: Value): Boolean = when {
    left is Value.Int32 && right is Value.Int32 -> left.v >= right.v
    left is Value.Int64 && right is Value.Int64 -> left.v >= right.v
    left is Value.Float32 && right is Value.Float32 -> left.v >= right.v
    left is Value.Float64 && right is Value.Float64 -> left.v >= right.v
    else -> refuseOperands(BinaryOp.Ge, left, right)
}

private fun negate(value: Value): Value = when (value) {
    is Value.Int32 -> Value.Int32(-value.v)
    is Value.Int64 -> Value.Int64(-value.v)
    is Value.Float32 -> Value.Float32(-value.v)
    is Value.Float64 -> Value.Float64(-value.v)
    else -> throw IllegalStateException(
        "'neg' takes a number, and this one is '${value::class.simpleName}'",
    )
}

/**
 * A logical operand, refused rather than coerced: coercing is the implicit conversion §10.2
 * excludes, and generated Kotlin requires a `Boolean` statically for `&&`, `||` and `if`.
 */
internal fun boolOperand(value: Value, operator: String): Boolean = (value as? Value.Bool)?.v
    ?: throw IllegalStateException(
        "'$operator' takes bool operands, and this one is '${value::class.simpleName}'",
    )

/** An arithmetic row over two operands; the right side is eager, which is what `+ - *` mean. */
private fun arithmetic(apply: (Value, Value) -> Value): BinaryRule =
    BinaryRule { left, right -> apply(left, right()) }

/** An ordering row over two operands, answering a bool. */
private fun ordering(apply: (Value, Value) -> Boolean): BinaryRule =
    BinaryRule { left, right -> Value.Bool(apply(left, right())) }

/**
 * §10.6 permits `==` on floats, and NaN follows Kotlin's own answer, which is what
 * structural equality on [Value] already is.
 */
private val EQUAL: BinaryRule = BinaryRule { left, right -> Value.Bool(left == right()) }

/** The negation of [EQUAL], and the only other comparison §10.6 lets take text. */
private val UNEQUAL: BinaryRule = BinaryRule { left, right -> Value.Bool(left != right()) }

/** `&&`: the right side is read only when the left one does not already answer the question. */
private val CONJUNCTION: BinaryRule = BinaryRule { left, right -> both(left, right) }

/** `||`: the mirror image, and the only other row that skips its right side. */
private val DISJUNCTION: BinaryRule = BinaryRule { left, right -> either(left, right) }

private fun both(left: Value, right: () -> Value): Value =
    if (boolOperand(left, "and")) Value.Bool(boolOperand(right(), "and")) else Value.Bool(false)

private fun either(left: Value, right: () -> Value): Value =
    if (boolOperand(left, "or")) Value.Bool(true) else Value.Bool(boolOperand(right(), "or"))

/**
 * The one refusal for a pair §10.4 rejects, carrying the checker's own two hints so both
 * halves of a document's diagnosis say the same thing.
 */
private fun refuseOperands(operator: BinaryOp, left: Value, right: Value): Nothing {
    val hint = if (operator in ORDERING) ORDERING_HINT else CONVERSION_HINT
    throw IllegalStateException(
        "'${operator.name.lowercase()}' takes '${left::class.simpleName}' and " +
            "'${right::class.simpleName}': $hint",
    )
}

/** §10.6's ordering operators, the ones whose operand rule is about text as much as numbers. */
private val ORDERING: Set<BinaryOp> = setOf(BinaryOp.Lt, BinaryOp.Le, BinaryOp.Gt, BinaryOp.Ge)

/** §10.6 rules string comparison down to `==`/`!=` and the `str.*` functions. */
private const val ORDERING_HINT: String = "text comparison is limited to ==, != and the str.* functions"

/** §10.4 has no implicit numeric conversion, and `num.toDouble` is the way out of one. */
private const val CONVERSION_HINT: String =
    "there is no implicit numeric conversion, so wrap one side in num.toDouble"
