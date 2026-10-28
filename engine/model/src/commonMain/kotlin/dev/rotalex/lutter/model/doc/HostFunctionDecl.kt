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
 * "fix" by accident. The argument is about the call site and not about the wire: it is a
 * required positional parameter, so a function that returns nothing has to *say* so and a typo
 * cannot quietly become one. On the wire the canonical `Json` omits the key for a null and
 * reads it back as null, which `ExplicitNullsTest` in `:engine:serialization` pins. Neither is
 * what [suspend] reads: §11.5 keys suspension off the flag, not off a missing return type.
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
