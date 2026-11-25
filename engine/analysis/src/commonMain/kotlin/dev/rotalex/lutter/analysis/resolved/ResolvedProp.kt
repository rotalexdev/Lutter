package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.PropertyKey

/** Whether the effective value was authored, filled from the theme, or filled from the spec. */
public enum class PropOrigin {
    Specified,

    /**
     * Filled from the theme's `componentDefaults` for the node's type (§14.2).
     *
     * Its own origin, not folded into [Default], because the two fall through differently:
     * the generated call may omit a spec default — Compose carries it — while a theme default
     * is document-level configuration the generated app does not hold, so it is emitted.
     */
    Theme,
    Default,
}

/**
 * One effective property: the authored or defaulted value, the checked expression behind a
 * computed one, and the token if the value is a token.
 *
 * [typed] and `PropertyValue.Computed` agree by construction — pass 5 fills one and this
 * lowering fills the other — so a constant carries `null` and keeps the type its
 * `PropertySpec` declared. It defaults so a caller building a constant by hand is unchanged.
 */
public data class ResolvedProp(
    public val key: PropertyKey,
    public val value: PropertyValue,
    public val origin: PropOrigin,
    public val token: ResolvedToken?,
    public val typed: TypedExpr? = null,
)
