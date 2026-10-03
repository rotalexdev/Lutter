package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.PropertyKey

/** Whether the effective value was authored, or filled from the spec. */
public enum class PropOrigin {
    Specified,
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
