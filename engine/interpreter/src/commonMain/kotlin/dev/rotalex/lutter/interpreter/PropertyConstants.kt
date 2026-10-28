package dev.rotalex.lutter.interpreter

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value

/**
 * The trivial case of evaluation: a constant, direct or `Computed`-wrapped.
 * Null means a real evaluator must run (Phase 6); callers state that refusal.
 */
public fun PropertyValue.constantOrNull(): Value? = when (this) {
    is PropertyValue.Const -> value
    is PropertyValue.Computed -> (expr as? Expr.Const)?.value
}
