package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.Serializable

/**
 * A function a document may call and the embedding application implements.
 *
 * PLAN §11.6 declares this record in full on one line, and it is transcribed here field for
 * field and order for order.
 *
 * [returns] has **no default**, which is the one part of the declaration that is easy to
 * "fix" by accident. A host function that returns nothing has to write `returns = null`, and
 * a document that omits the key is refused rather than read as a unit function — the same
 * discipline §6.2 applies to the rest of the format, where an unstated default is not a
 * default. It is also not what [suspend] reads: §11.5 keys suspension off the flag, not off
 * the absence of a return type.
 */
@Serializable
public data class HostFunctionDecl(

    /** The name an action or an expression calls. */
    public val name: String,

    /** The typed inputs, in declaration order. */
    public val params: List<ParamDecl>,

    /** The return type, or `null` for a function that returns nothing. No default, by §11.6. */
    public val returns: TypeRef?,

    /** Whether the supplied implementation suspends (§11.5). */
    public val suspend: Boolean = false,
)
