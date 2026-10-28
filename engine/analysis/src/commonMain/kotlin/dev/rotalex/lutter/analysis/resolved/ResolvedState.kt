package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.expr.TypedExpr

/**
 * One state declaration as the backends see it: §12.1's record plus the checked body a derived
 * one carries.
 *
 * [derived] is non-null exactly when [decl] is derived, and it has no default so that is a
 * decision rather than an omission — a held declaration has no body, and §12.1 makes derived
 * state computed on read, so this is the whole of what its runtime half needs.
 */
public data class ResolvedState(
    public val decl: StateDecl,
    public val derived: TypedExpr?,
)
