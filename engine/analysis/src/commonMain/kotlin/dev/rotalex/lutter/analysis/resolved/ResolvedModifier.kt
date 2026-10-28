package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey

/** One modifier with its effective arguments, in application order. */
public data class ResolvedModifier(
    public val type: ModifierType,
    public val args: Map<PropertyKey, ResolvedProp>,
)
