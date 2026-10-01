package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.PropertyKey

/** Whether the effective value was authored, or filled from the spec. */
public enum class PropOrigin {
    Specified,
    Default,
}

/**
 * One effective property: the authored or defaulted value, plus its token if it is one.
 *
 * Computed values pass through unchecked — typing them is the deferred expression pass.
 */
public data class ResolvedProp(
    public val key: PropertyKey,
    public val value: PropertyValue,
    public val origin: PropOrigin,
    public val token: ResolvedToken?,
)
