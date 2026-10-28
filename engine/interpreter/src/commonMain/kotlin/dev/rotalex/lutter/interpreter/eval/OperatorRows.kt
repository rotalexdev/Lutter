package dev.rotalex.lutter.interpreter.eval

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.UnaryOp
import dev.rotalex.lutter.model.value.Value

/**
 * §10.6's rows as answers: the tables [Evaluator] reads, read back over [Value]s.
 *
 * [BinaryOperators] and [UnaryOperators] are reachable only through [Evaluator], so the one
 * question worth asking about them — does a row agree with §10.4's rule for the same operator? —
 * could not be asked from outside this module.
 *
 * ### Why these are maps and not a lookup function
 *
 * Keyed by the operator, and for the same reason the analysis module keys its own rules by it:
 * "§10.6 has no row for this operator" and "this row refused these values" are separate facts,
 * and only the first is a table edit. The key set answers the first and the null answer the
 * second, so a new operator arriving without a row is visible rather than indistinguishable from
 * a refusal.
 *
 * ### Why a null answer is a refusal
 *
 * Every refusal in this package is an `IllegalStateException` carrying §10.6's hint, which is
 * what makes catching one a way to ask what a row accepts. The catch is here rather than in the
 * caller because the refusal is this module's own convention and a caller outside it has no
 * business knowing it; anything else propagates, because a row that threw for another reason is
 * a bug rather than a verdict.
 *
 * ### The right operand stays a thunk
 *
 * `and` and `or` never call it, and short-circuit is a promise about both backends, so the view
 * carries the same `() -> Value` [BinaryOperators.table] does rather than an already-evaluated
 * right side. A caller that wants the skip has to call the thunk, which is the point.
 */
public object OperatorRows {

    /** One row per [BinaryOp], read through [BinaryOperators.table]. */
    public val binary: Map<BinaryOp, (Value, () -> Value) -> Value?> =
        BinaryOperators.table.mapValues { (_, row) -> answering(row) }

    /** One row per [UnaryOp], read through [UnaryOperators.table]. */
    public val unary: Map<UnaryOp, (Value) -> Value?> =
        UnaryOperators.table.mapValues { (_, row) -> answering(row) }
}

/**
 * [row] as an answer rather than a throw: a refusal becomes the null the map's value type is.
 *
 * A named function rather than a lambda in place, because a lambda written in an argument position
 * would have to spell `right: () -> Value` next to the arrow that ends its parameter list.
 */
private fun answering(row: BinaryRule): (Value, () -> Value) -> Value? = { left, right ->
    try {
        row.apply(left, right)
    } catch (_: IllegalStateException) {
        null
    }
}

/** [row] as an answer rather than a throw, for the one-operand shape. */
private fun answering(row: UnaryRule): (Value) -> Value? = { operand ->
    try {
        row.apply(operand)
    } catch (_: IllegalStateException) {
        null
    }
}