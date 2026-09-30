package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.type.TypeRef

/**
 * One event a component raises, with the typed arguments its handler receives.
 *
 * Events are not `TypeRef`s (§9.1): a handler body is an action sequence, not a value.
 */
public class EventSpec(
    public val key: EventKey,
    public val args: List<EventArgSpec> = emptyList(),
)

/**
 * One handler argument: its name and type. A bare string, like `RefTarget.EventArg`: the
 * name binds a scope the analysis resolves, so construction-time validation would refuse
 * legal names before anything could resolve them.
 */
public data class EventArgSpec(
    public val name: String,
    public val type: TypeRef,
)
