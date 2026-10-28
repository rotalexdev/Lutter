package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.ScopeId

/**
 * One node lowered for readers: defaults applied, children nested, scopes computed.
 *
 * A tree, not table rows — single parenthood is already proven, so nesting loses nothing.
 *
 * [events] is last and defaulted. Last because thirteen construction sites already pass
 * [scopes] positionally, and inserting a parameter ahead of it turns every one of them into a
 * type error — a compiler's job, not a reader's. Defaulted because a node that binds no
 * handler should not have to say so, and the pass is the only production constructor, so the
 * default cannot stand in for a handler that was in the document.
 */
public data class ResolvedNode(
    public val id: NodeId,
    public val type: ComponentType,
    public val props: Map<PropertyKey, ResolvedProp>,
    public val modifiers: List<ResolvedModifier>,
    public val slots: Map<SlotName, List<ResolvedNode>>,
    public val scopes: Set<ScopeId>,

    /**
     * The handlers as the document wrote them, carried through untouched: the one place a
     * handler survives resolution, and the contract the interpreter's executor and the emitted
     * call are both held to.
     */
    public val events: Map<EventKey, ActionSequence> = emptyMap(),
)
