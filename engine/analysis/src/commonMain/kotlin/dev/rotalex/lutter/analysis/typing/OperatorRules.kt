package dev.rotalex.lutter.analysis.typing

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.ExprType
import dev.rotalex.lutter.model.expr.UnaryOp

/**
 * §10.4's operator rules as data: the one table [TypeChecker] reads, and the one the operator
 * contract in `:engine:test-support` reads back to compare against §10.6's rows.
 *
 * The rules were four `when` arms inside the checker, so nothing outside the file that applied
 * them could ask what they were, and §10.6's rows are a second list of the same facts with
 * nothing saying the twin exists.
 *
 * ### Why the row set is a map and not a lookup function
 *
 * "§10.4 has no row for this operator" and "§10.4 refuses this pair" are different facts, and
 * only the first is a table edit. A function answering null for both lets a new [BinaryOp] reach
 * a document with no rule at all and reports nothing at all, which is the drift this file
 * exists to expose. Keyed by the operator, the two questions are separate reads.
 *
 * Nothing here *describes* a rule: [BinaryRow.answer] and [UnaryRow.answer] are the rules, and
 * the checker calls them, so a row edited here changes what the checker accepts rather than
 * changing a comment about it. The refusal prose sits beside its row for the same reason — a
 * message naming the operand type it was handed cannot go stale, and one naming the rule would.
 */
public object OperatorRules {

    // The shared rows come first: `binary` and `unary` read them, and a property initialiser
    // runs in declaration order, so a map above them would capture nulls rather than rows.

    /** `+ - *` over two operands of one numeric type, answering that type. */
    private val SAME_NUMERIC: BinaryRow = BinaryRow { left, right ->
        if (left == right && numeric(left)) left else null
    }

    /** `+` over two operands of one type, numeric or text: `Str + Str` is §10.2's concatenation. */
    private val SAME_NUMERIC_OR_TEXT: BinaryRow = BinaryRow { left, right ->
        if (left == right && (numeric(left) || left == TEXT)) left else null
    }

    /** The four ordering operators, which are numeric only and answer a bool. */
    private val ORDERING_NUMERIC: BinaryRow = BinaryRow { left, right ->
        if (left == right && numeric(left)) BOOL else null
    }

    /** `==` and `!=`: one type against the same type, or against a null. */
    private val COMPARABLE: BinaryRow = BinaryRow { left, right ->
        if (left == right || left is ExprType.Null || right is ExprType.Null) BOOL else null
    }

    /** `&&` and `||`, over two bools. §10.6's short circuit is the generated symbol's, not this row's. */
    private val TWO_BOOLS: BinaryRow = BinaryRow { left, right ->
        if (left == BOOL && right == BOOL) BOOL else null
    }

    /** One row per [BinaryOp], keyed by the operator that settles the pair. */
    public val binary: Map<BinaryOp, BinaryRow> = mapOf(
        BinaryOp.Add to SAME_NUMERIC_OR_TEXT,
        BinaryOp.Sub to SAME_NUMERIC,
        BinaryOp.Mul to SAME_NUMERIC,
        BinaryOp.Eq to COMPARABLE,
        BinaryOp.Neq to COMPARABLE,
        BinaryOp.Lt to ORDERING_NUMERIC,
        BinaryOp.Le to ORDERING_NUMERIC,
        BinaryOp.Gt to ORDERING_NUMERIC,
        BinaryOp.Ge to ORDERING_NUMERIC,
        BinaryOp.And to TWO_BOOLS,
        BinaryOp.Or to TWO_BOOLS,
    )

    /** One row per [UnaryOp], keyed by the operator that settles the operand. */
    public val unary: Map<UnaryOp, UnaryRow> = mapOf(
        UnaryOp.Not to UnaryRow({ operand -> BOOL.takeIf { operand == BOOL } }, "needs a bool"),
        UnaryOp.Neg to UnaryRow({ operand -> operand.takeIf(::numeric) }, "needs a number"),
    )
}

/** One binary row: the type it settles over a pair, or null when the pair has no answer. */
public class BinaryRow(private val settle: (ExprType, ExprType) -> ExprType?) {

    /**
     * @param left the type the left operand settled as.
     * @param right the type the right operand settled as.
     * @return the settled type, or null when §10.4 refuses the pair.
     */
    public fun answer(left: ExprType, right: ExprType): ExprType? = settle(left, right)
}

/** One unary row: the type it settles, and the prose its own refusal carries. */
public class UnaryRow(
    private val settle: (ExprType) -> ExprType?,
    /** The hint [TypeChecker] appends to the operator's name when the row refuses. */
    public val refusal: String,
) {

    /**
     * @param operand the type the operand settled as.
     * @return the settled type, or null when §10.4 refuses the operand.
     */
    public fun answer(operand: ExprType): ExprType? = settle(operand)
}