package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.Serializable

/**
 * A typed input of a page, a component or a host function.
 *
 * PLAN §5.5 names this type three times and declares it nowhere; §33.1 puts it in this file,
 * beside a `Page` that is not here yet. The three fields are quoted from §13.1's analysis rule
 * — *"provided args match the target's `ParamDecl`s (name, type, required)"* — and their types
 * are the only ones the surrounding vocabulary permits: §10.1's `RefTarget.Param` and §13.2's
 * `Map<ParamName, Value>` fix the first, §5.5's "typed inputs" fixes the second.
 *
 * `required` defaults to true, which §903 does not say. §6.2's canonical writer encodes no
 * defaults, so this makes the common case the one that is not written and leaves the wire form
 * identical for a caller that spells it out.
 */
@Serializable
public data class ParamDecl(

    /** The name a call site binds and `RefTarget.Param` reads. */
    public val name: ParamName,

    /** The declared type. Bounds belong to the spec, as they do for every other type. */
    public val type: TypeRef,

    /** Whether a caller has to supply it. */
    public val required: Boolean = true,
)
