@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, which
// kotlinx.serialization still marks experimental. It has to be a file annotation and not a
// per-use `@OptIn` because Kotlin requires file annotations to precede the `package`
// declaration, and putting one after the imports is a syntax error rather than a warning.

package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * One piece of state: a page's, a component's or the app's.
 *
 * PLAN §12.1 declares all six fields, and the shape follows from where the record is used
 * rather than from any preference: it is emitted into generated code by §12.2's
 * `StateStrategy`, so [name] is a Kotlin identifier preserved verbatim and [type] is a full
 * `TypeRef` rather than a name — a document that types a state after a data model this
 * engine has never heard of still decodes.
 *
 * [initial] and [derived] are both nullable and both default to null, and "exactly one of
 * them" is a rule this class does not enforce. The model checks only what it can check
 * without a schema; which of the two a declaration has is §17.1's business, and a
 * constraint refused at construction would turn a document that ought to load into one that
 * does not.
 */
@Serializable
public data class StateDecl(

    /** The id, stable across a rename (§6.2). */
    public val id: StateId,

    /** A Kotlin identifier, written into the generated code unchanged (§12.1). */
    public val name: String,

    /** The declared type. */
    public val type: TypeRef,

    /** The starting value, when the state is held rather than computed. */
    public val initial: Value? = null,

    /** The expression that produces the value, when the state is derived. */
    public val derived: Expr? = null,

    /** How long the value outlives a composition. §12.1's six scopes, reduced to a tag. */
    public val persistence: Persistence = Persistence.None,
)

/**
 * How long a [StateDecl]'s value survives, as a closed union rather than an enum.
 *
 * PLAN §12.1 declares the two arms and argues for the shape, and the argument is
 * falsifiability rather than blast radius: nothing in this project can test whether the
 * *variant set* is right, so it should be the choice that is cheapest to be wrong about.
 * Adding a variant to an `enum class` is additive; adding a **payload** to an existing entry
 * is a wire break, because the new field appears on a tag every stored document already
 * carries. A sealed interface grows a variant with a payload without touching the ones
 * already written, which is the only way this type absorbs the `store` reference
 * `PersistentStore` will need when §31.3 stops deferring it.
 *
 * Two arms and no more. [None] is forced by [StateDecl.persistence]'s default, [Saveable] by
 * §12.1's and §15.3's `rememberSaveable` *where the type allows* — a distinction the field has
 * to be able to express. Neither carries a type list or a key: which types are saveable is
 * §12.2's `StateStrategy`, and a key is a store concern.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Persistence {

    /** Not saved. The default, and the only arm a document needs today. */
    @Serializable
    @SerialName("none")
    public data object None : Persistence

    /** `rememberSaveable`, for the types the store can hold. */
    @Serializable
    @SerialName("saveable")
    public data object Saveable : Persistence
}
