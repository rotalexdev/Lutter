package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Every value a document can hold: a closed, serializable union of seventeen.
 *
 * PLAN §5.4's list, variant for variant, with the numbering it does not have. The count is
 * seventeen and it is worth being precise about, because "the value union" is easy to
 * misremember as a smaller thing. The seventeen are ten scalars (`Null` through `Sp`), five
 * named things (`Enum` through `Token`) and two containers (`ListOf` and `Obj`).
 *
 * ### Closed, on purpose, and the thing that is open instead
 *
 * An eighteenth would be a schema-version event: every document written before it is still
 * readable, but an engine that adds one can write something an older engine cannot. That is a
 * legitimate cost, paid rarely, and §5.4's D3 says what is bought with it — extensibility
 * comes from *data-defined* types (`EnumTypeSpec`, `ObjectTypeSpec`, `IconSet`) registered in
 * the schema, so a plugin that needs a new kind of value declares a type rather than a class.
 * A union that grew on demand would move the cost from a version number nobody notices to a
 * document nobody can read.
 *
 * ### Every tag is spelled
 *
 * Each variant carries an explicit `@SerialName`, and none of them may be named by its class
 * name. This is not a style preference and it is not enforced by a linter here: a rename
 * without a `@SerialName` repoints every stored document at a discriminator nothing answers
 * to, and the failure arrives as an empty document rather than as an error a person reads.
 * `ValueTest` encodes every variant and reads the discriminator back out of the JSON, so a
 * rename that changes the wire form fails a test rather than a review.
 *
 * ### Numbers have one spelling (D1)
 *
 * `Float32`, `Dp` and `Sp` are written by `CanonicalFloat`; `Float64` by `CanonicalDouble`.
 * None of them ever reaches a `toString()`, so `0.1` is `0.1` and not the widened double
 * `0.10000000149011612` that a document would otherwise diff on. The construction side is the
 * other half of the same promise: `Value.dp(16.5f)` rounds to at most four fractional digits
 * and refuses NaN, the infinities, and a magnitude past `2.0e11`.
 *
 * **`Dp` and `Sp` stay separate variants** even though both hold a `Float` through the same
 * serializer. They are separate because they are separate quantities, and a single numeric
 * variant would put the choice of unit on every property in every document. The serializer is
 * shared because the *spelling* of a number is one decision; what the number measures is
 * another.
 *
 * ### Decoding consults nothing (D4)
 *
 * A `Value` decodes without knowing which component owns it, which no `Schema`,
 * `PropertySpec`, `TypeRegistry` or `ReferenceIndex` takes part in. This is what lets a
 * document written by a newer plugin round-trip through an older engine without losing the
 * parts this engine does not understand.
 *
 * The honest limit of that, because a closed union has one: what survives is unknown
 * *payload*, not unknown *variants*. A `Value.Obj` naming a `TypeId` this engine has never
 * heard of, a `Value.Ref` pointing at a page that does not exist, a `Value.Enum` carrying an
 * entry no `EnumTypeSpec` declares, a `Value.Icon` from a set nobody registered — all of those
 * decode, because a tag, an id and an entry name are data. An eighteenth `Value` *variant*
 * does not decode, because the union has no answer for its discriminator, and that is D3
 * working rather than D4 failing.
 *
 * ### The discriminator key
 *
 * `type`, written explicitly through `JsonClassDiscriminator`. The full argument, including
 * PLAN §9.2's `{"k":"str","v":"…"}` and the `k` in its example documents, is in `TypeRef`'s
 * KDoc; the two hierarchies share one key and one spelling of it.
 *
 * ### Three asymmetries with the type side, all deliberate
 *
 *  * **No `TypeRef` for `Null`.** A property is always present, and `Null` is what it holds
 *    when the value is absent. Nullability is a fact about the type (`TypeRef.Nullable`), not a
 *    second type.
 *  * **`Enum` carries an entry name and not a `TypeId`.** The type it belongs to is the
 *    property's declared type, which the spec supplies; repeating it in the value would give a
 *    document two sources for one fact, and the two could disagree. Validation checks the entry
 *    against the enum type the spec declares.
 *  * **`Ref` carries a bare `String` for its id**, and `Token` a name rather than a resolved
 *    token. Both are resolved by analysis, and resolving them at decode time would be a schema
 *    lookup — which is the thing D4 forbids. The arguments are in `RefKind`.
 *
 * ### What is deliberately not here
 *
 *  * **Expressions.** A computed property is `PropertyValue.Computed` (§5.4), the other arm
 *    against `PropertyValue.Const`. An expression is not a value until an evaluator produces
 *    one, and what it produces is one of these seventeen.
 *  * **A map.** `ListOf` and `Obj` are containers; there is no map variant, and therefore
 *    **no value inhabits `TypeRef.MapOf`**. §9.2's Map row answers the question with "`obj`
 *    with typed fields or `list` of pairs", which is a statement about schema-level encoding
 *    and not about a variant of this union. Adding one is the schema-version event above, and
 *    the plan has not asked for it; the gap is recorded in the feature document rather than
 *    papered over with a variant nobody asked for.
 *  * **A dimension.** `TypeRef.Dimension` is post-MVP and its fill and wrap cases have no
 *    representation at all. `Dp` covers the one case that is expressible today.
 *
 * @see dev.rotalex.lutter.model.type.TypeRef for the type side, which is a different
 *   closed set with a different size.
 * @see ValueKind for the typed access a renderer wants, and for the kinds that need a schema
 *   to exist at all.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Value {

    // -----------------------------------------------------------------------------------
    // Scalars
    // -----------------------------------------------------------------------------------

    /**
     * The absence of a value.
     *
     * Written as the tag `"null"` and therefore as the *string* `"null"`, not as a JSON
     * `null`: the discriminator is a string field, and a document that wrote a bare `null`
     * would not be able to say which of the seventeen it meant either.
     */
    @Serializable
    @SerialName("null")
    public data object Null : Value

    /** A boolean. */
    @Serializable
    @SerialName("bool")
    public data class Bool(public val v: Boolean) : Value

    /** A 32-bit signed integer, exactly as written. */
    @Serializable
    @SerialName("i32")
    public data class Int32(public val v: Int) : Value

    /**
     * A 64-bit signed integer, exactly as written.
     *
     * The escape hatch from the canonical magnitude bound. A document that needs a number past
     * `2.0e11` — a nanosecond timestamp, an identifier as a number — is not refused, it is
     * written as an integer, which is exact on every target and has no rounding to defend.
     */
    @Serializable
    @SerialName("i64")
    public data class Int64(public val v: Long) : Value

    /** A 32-bit float, canonicalized to at most four fractional digits (D1). */
    @Serializable
    @SerialName("f32")
    public data class Float32(
        @Serializable(CanonicalFloat::class) public val v: Float,
    ) : Value

    /**
     * A 64-bit float, canonicalized the same way.
     *
     * PLAN §5.4 draws the line here: a `Float64` *produced by evaluation* is never serialized
     * and may hold any finite double. Canonicalization is a property of the document, not of
     * the arithmetic, and this variant is the document's.
     */
    @Serializable
    @SerialName("f64")
    public data class Float64(
        @Serializable(CanonicalDouble::class) public val v: Double,
    ) : Value

    /** Text, with no bound of its own. The length limit is the property's rule (§9.2). */
    @Serializable
    @SerialName("str")
    public data class Str(public val v: String) : Value

    /**
     * A colour, written `#AARRGGBB` and marshalled to Compose with no conversion.
     *
     * The serializer sits on the property rather than on `ColorArgb` itself, and that is not
     * a style choice. `@Serializable(with = …)` directly on a `@JvmInline value class`
     * compiles, and then throws `SerializationException` on the Wasm target while working on
     * the JVM and Android — so it is a construct that passes two of three targets and fails
     * the third, which is the worst way to fail.
     *
     * Declaring it here is the same shape T2 already uses and CI already proved on all three
     * targets: `@Serializable(CanonicalFloat::class) val v: Float` on `Value.Dp`. A custom
     * serializer belongs on the *property*, where the format is a decision the union makes,
     * not on the type, where it would have to be right for every future use site as well.
     *
     * The cost is that a `ColorArgb` on its own is not directly serializable, and that is
     * correct: the model has no reason to write a colour anywhere except inside a `Value`.
     */
    @Serializable
    @SerialName("color")
    public data class Color(
        @Serializable(ColorArgbSerializer::class) public val argb: ColorArgb,
    ) : Value

    /**
     * A density-independent pixel quantity, canonicalized like [Float32].
     *
     * A number, not a dimension: `TypeRef.Dimension` is the post-MVP union of a `dp`, a fill
     * fraction and wrap, and this variant is the one case of it that is expressible today.
     */
    @Serializable
    @SerialName("dp")
    public data class Dp(
        @Serializable(CanonicalFloat::class) public val v: Float,
    ) : Value

    /**
     * A scaled-pixel quantity for text, canonicalized like [Float32].
     *
     * Distinct from [Dp] because the quantity is distinct, not because the number needs a
     * different serializer. See the file's KDoc.
     */
    @Serializable
    @SerialName("sp")
    public data class Sp(
        @Serializable(CanonicalFloat::class) public val v: Float,
    ) : Value

    // -----------------------------------------------------------------------------------
    // Named things
    // -----------------------------------------------------------------------------------

    /**
     * One entry of an enum type, by name.
     *
     * The name is the whole of it — no [TypeId], and the reason is in the file's KDoc: the
     * property's declared type already says which enum this is, and a value that repeated it
     * would be a second source for one fact.
     */
    @Serializable
    @SerialName("enum")
    public data class Enum(public val entry: String) : Value

    /**
     * A URL, as a string.
     *
     * §9.2's validation column is "RFC-3986 syntactic" and its editor column is a text field.
     * Neither is a property of the value — both are rules on the property — and a `str` with a
     * checker on it is a smaller thing to get right than a URL type that is *nearly* a string.
     */
    @Serializable
    @SerialName("url")
    public data class Url(public val v: String) : Value

    /** A reference to a page, component, resource or data model, by id. */
    @Serializable
    @SerialName("ref")
    public data class Ref(public val kind: RefKind, public val id: String) : Value

    /**
     * An icon, named within a set.
     *
     * `set` is not nullable, and the asymmetry with `TypeRef.Icon` — where it is — is the
     * difference between a type that may say "from the default set" and a value that has to
     * say which set, because a value is something to look up.
     */
    @Serializable
    @SerialName("icon")
    public data class Icon(public val set: String, public val name: String) : Value

    /** A theme token, resolved by the selected theme. */
    @Serializable
    @SerialName("token")
    public data class Token(public val kind: TokenKind, public val name: String) : Value

    // -----------------------------------------------------------------------------------
    // Containers
    // -----------------------------------------------------------------------------------

    /**
     * A list of values, in document order.
     *
     * A `List` rather than an immutable collection for the same reason every record here is a
     * plain data class: the document is data, and kotlinx.serialization has one answer for a
     * `List` on every target. A persistent structure is an in-memory representation choice for
     * a later work unit, and it would have to arrive with its own serializer to be worth having.
     */
    @Serializable
    @SerialName("list")
    public data class ListOf(public val items: List<Value>) : Value

    /**
     * A structured record: a [TypeId] this engine may not know, and the fields it was given.
     *
     * This is the variant D4 lives in. The shape is *named*, not declared, so a document can
     * carry a data model written by a plugin this engine has never loaded and hand it back
     * unchanged — the fields are a `Map<PropertyKey, Value>`, and both halves of that key are
     * closed domain types, which is what lets a decoder know what an unknown field's value
     * still is.
     *
     * The key is [PropertyKey] rather than a `String` for the same reason the identifiers are
     * value classes: a key is a name that was checked, and a `String` in this position would
     * be a name that was not.
     */
    @Serializable
    @SerialName("obj")
    public data class Obj(
        public val typeId: TypeId,
        public val fields: Map<PropertyKey, Value>,
    ) : Value
}
