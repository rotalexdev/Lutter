package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.ScopeId

/**
 * One node lowered for readers: defaults applied, children nested, scopes computed.
 *
 * A tree, not table rows — single parenthood is already proven, so nesting loses nothing.
 */
public data class ResolvedNode(
    public val id: NodeId,
    public val type: ComponentType,
    public val props: Map<PropertyKey, ResolvedProp>,
    public val modifiers: List<ResolvedModifier>,
    public val slots: Map<SlotName, List<ResolvedNode>>,
    public val scopes: Set<ScopeId>,
)
