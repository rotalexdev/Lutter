package dev.rotalex.lutter.model.type

import dev.rotalex.lutter.model.ids.TypeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A type, as a document states it: the declared type of a property, a state declaration, a
 * page parameter or a data model field.
 *
 * PLAN §9.1 puts this in `:engine:model` for a reason worth repeating, because it decides
 * where every other module looks for it: *"documents declare types for state, params and data
 * models"*. A type is not only a schema author's vocabulary. It travels inside the document,
 * so it is part of the file format and belongs with the values it describes.
 *
 * ### The closed set
 *
 * Nineteen variants, sealed, every one `@Serializable` with an explicit `@SerialName`. Not one
 * of them may be named by its class name: a rename would repoint every stored document at a
 * tag that no longer exists, and the failure would be a document that decodes to nothing
 * rather than an error anyone reads. The tag is the contract; the class name is an
 * implementation detail that happens to be readable.
 *
 * ### What is *not* a `TypeRef`
 *
 * Two exclusions, both from §9.1 and both load-bearing:
 *
 *  * **Events and lambdas.** An event handler is an `EventSpec` with an `ActionSequence` body
 *    (§11), not a value of a type. A `TypeRef` describes what a property *is*; an action
 *    describes what happens, and giving it a type would put behaviour in a position the
 *    analyzer has to typecheck.
 *  * **Expressions and bindings.** Those are `PropertyValue.Computed` (§5.4), the other arm of
 *    the pair that `PropertyValue.Const` completes. A property is a constant of some
 *    `TypeRef`, a computed property, or neither — and a computed property is not a value at
 *    all until the evaluator produces one.
 *
 * A theme token, by contrast, *is* a `TypeRef` ([Token]) and its value is a `Value.Token`.
 *
 * ### The two dimensions this module owns
 *
 * §9.2 tabulates five dimensions per type: serialization, runtime evaluation, editor
 * metadata, validation and codegen. Two of the five are facts this module owns and exposes as
 * data — [serialTag] and [runtimeType] — and the other three are read where they belong:
 * editor hints are `EditorHints` in `:engine:schema`, validation is the spec's own bounds in
 * `:engine:schema`, and codegen is a `KotlinSymbol` in `:engine:codegen`. They are not modelled
 * here as empty types, because a model type that exists only to be empty is a claim that the
 * column is answered, and it is not.
 *
 * ### Why these are members of the interface
 *
 * Both are abstract on the interface, so a twentieth variant does not compile until it says
 * what it serializes as and how it evaluates. A `when` in a companion object would have the
 * same exhaustiveness, but it moves the answer away from the declaration it describes, and the
 * compiler's exhaustiveness check on a `when` is worth less than its exhaustiveness check on
 * an interface: a new variant is a *design* event and deserves to fail on its own declaration
 * rather than in a function three hundred lines away.
 *
 * [serialTag] repeats each variant's `@SerialName`, and that duplication is deliberate and
 * guarded rather than tolerated. The alternative — reading the serial name back out of the
 * generated serializer descriptor at runtime — was rejected for a specific reason: it makes a
 * structural question about a type depend on a `Json` instance being configured, and the
 * discriminator's *key* is a format setting while its *value* is a type fact. The guard is
 * `TypeRefTest`, which encodes every variant and reads the discriminator out of the JSON, so
 * the two copies cannot drift without a red test.
 *
 * ### The discriminator key
 *
 * The key is `type`, written explicitly through [JsonClassDiscriminator] rather than left as
 * kotlinx.serialization's default. A published format should not have a field name that a
 * library can change underneath it.
 *
 * PLAN writes `k` in two places: §9.2's String row shows `{"k":"str","v":"…"}` and the §18
 * example document shows `"style": { "k": "token", ... }`. §5.4's declarations say nothing
 * about a discriminator, and kotlinx.serialization's default is `type`, so `type` is what
 * these declarations produce. Both PLAN spellings agree on what follows the key — `"v"`,
 * `"name"`, `"kind"` — and disagree only on the key itself. The `k` form is plausible for the
 * *compact* property encoding of §33.1's `PropertyValueSerializer`, where a `Value` is nested
 * inside something that already has a `type`, and that is a decision for the work unit that
 * owns the envelope, not one to guess at here. Whichever way it goes, it is a
 * `FORMAT_VERSION` event: a key that changes name invalidates every document already written.
 *
 * ### One constraint on the variants
 *
 * **No variant may declare a property named `type`.** The discriminator owns that key, and
 * kotlinx.serialization fails schema construction when a class property collides with it. The
 * nearest miss in the plan is `Value.Obj`, whose `typeId` field is deliberately not called
 * `type`.
 *
 * @see RuntimeType for the evaluation column, and for why the other three are elsewhere.
 * @see Value for the value side of the same vocabulary.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface TypeRef {

    /**
     * The tag this type writes in JSON, which is its `@SerialName`.
     *
     * This is the serialization column of §9.2's table. Three rows need saying out loud,
     * because §9.2's column is a mixture of two vocabularies and the difference is not
     * visible from the table:
     *
     *  * For the fifteen tags the two hierarchies share — `bool`, `i32`, `i64`, `f32`, `f64`,
     *    `str`, `color`, `dp`, `sp`, `url`, `icon`, `list`, `enum`, `ref`, `token` — §9.2's tag
     *    and the `@SerialName` are the same string, which is the useful property and the
     *    reason the two sections were written the way they were.
     *  * `Object` is the one where the two *names* part company. §9.2's Object row says
     *    `obj{typeId,fields}`, and that is the tag of a *value* of this type: `Value.Obj`. The
     *    type is `object`. Several of §9.2's rows are written as a value's wire form rather
     *    than a type's — "Resource / Image" is `ref(kind=resource)`, "Icon" is `icon{set,name}`
     *    — which is harmless while the two spellings agree and is a bug the moment they do
     *    not. This is the row where it bites.
     *  * `nullable` and `map` have no row in §9.2 at all, because a nullable and a map are not
     *    type *families* the way the table's rows are. Their tags come from §9.1 alone.
     */
    public val serialTag: String

    /**
     * The runtime answer for a value of this type — the Runtime evaluation column of §9.2.
     *
     * A many-to-one answer. `Str` and `Url` are both [RuntimeType.Text] because §9.2 says
     * both evaluate to a `String`, and `Nullable` is [RuntimeType.Optional] because the answer
     * is "the inner type, or absent", which no other entry says.
     */
    public val runtimeType: RuntimeType

    // -----------------------------------------------------------------------------------
    // Scalars
    // -----------------------------------------------------------------------------------

    /** A `Boolean`. §9.2's editor answer is a switch and its codegen answer is `true`/`false`. */
    @Serializable
    @SerialName("bool")
    public data object Bool : TypeRef {
        override val serialTag: String = "bool"
        override val runtimeType: RuntimeType = RuntimeType.Boolean
    }

    /** A 32-bit signed integer. */
    @Serializable
    @SerialName("i32")
    public data object Int32 : TypeRef {
        override val serialTag: String = "i32"
        override val runtimeType: RuntimeType = RuntimeType.Int
    }

    /**
     * A 64-bit signed integer.
     *
     * A separate type rather than an `Int32` with a bigger bound because §5.4's canonical
     * numerics refuse a magnitude past `2.0e11` for anything float-ish and a document that
     * needs a genuinely large number reaches for this instead.
     */
    @Serializable
    @SerialName("i64")
    public data object Int64 : TypeRef {
        override val serialTag: String = "i64"
        override val runtimeType: RuntimeType = RuntimeType.Long
    }

    /**
     * A 32-bit float, canonicalized at construction and written with at most four fractional
     * digits (PLAN §5.4, rule D1).
     */
    @Serializable
    @SerialName("f32")
    public data object Float32 : TypeRef {
        override val serialTag: String = "f32"
        override val runtimeType: RuntimeType = RuntimeType.Float
    }

    /** A 64-bit float, canonicalized the same way. */
    @Serializable
    @SerialName("f64")
    public data object Float64 : TypeRef {
        override val serialTag: String = "f64"
        override val runtimeType: RuntimeType = RuntimeType.Double
    }

    /**
     * A string, with no length bound.
     *
     * The bound is in the `PropertySpec`, not here, and §9.2 says so: its validation column for
     * String is "length limits (spec)". That is a deliberate division — a type says what a
     * value *is*, and how much text a particular text field will accept is that field's rule.
     * A `TypeRef.Str(maxLength = 40)` would also have had to be a second sealed hierarchy, or a
     * nullable field on this variant, and neither is a fact about strings.
     */
    @Serializable
    @SerialName("str")
    public data object Str : TypeRef {
        override val serialTag: String = "str"
        override val runtimeType: RuntimeType = RuntimeType.Text
    }

    /** A colour, `#AARRGGBB` on the wire and `Color(0xFF6200EE)` in generated code. */
    @Serializable
    @SerialName("color")
    public data object Color : TypeRef {
        override val serialTag: String = "color"
        override val runtimeType: RuntimeType = RuntimeType.Color
    }

    /**
     * A density-independent pixel quantity — a `dp` value, which is a number and not a
     * dimension (see [Dimension]).
     *
     * `≥0` is a rule from the spec, not a rule from the type: §9.2's validation column says
     * "≥0 unless spec allows", and a negative offset is a legitimate thing for one property to
     * want.
     */
    @Serializable
    @SerialName("dp")
    public data object Dp : TypeRef {
        override val serialTag: String = "dp"
        override val runtimeType: RuntimeType = RuntimeType.Dp
    }

    /**
     * A scaled-pixel quantity for text, which is a different quantity from [Dp] and not a
     * synonym for it.
     *
     * They are separate variants because the two are separately meaningful: text that scales
     * with the user's font-size preference should be `sp`, and everything else is `dp`. A
     * single numeric type with a per-property unit would push that decision onto every
     * property in every document, and the error it invites — a padding in `sp` — is invisible
     * until a user with a large font sees it.
     */
    @Serializable
    @SerialName("sp")
    public data object Sp : TypeRef {
        override val serialTag: String = "sp"
        override val runtimeType: RuntimeType = RuntimeType.TextUnit
    }

    /**
     * A URL.
     *
     * §9.2's validation column is "RFC-3986 syntactic" and its editor column is a plain text
     * field — the same field as [Str], with a checker on it. The check is a rule on the
     * property, not a different kind of string, and that is why the runtime answer is
     * [RuntimeType.Text] and not an entry of its own.
     */
    @Serializable
    @SerialName("url")
    public data object Url : TypeRef {
        override val serialTag: String = "url"
        override val runtimeType: RuntimeType = RuntimeType.Text
    }

    // -----------------------------------------------------------------------------------
    // Composites
    // -----------------------------------------------------------------------------------

    /**
     * The inner type made optional: `T?`.
     *
     * There is no `TypeRef.Null`. Nullability is a property of a type, and a property that is
     * absent holds [Value.Null] — the same as a present property that happens to hold it. Two
     * ways to spell that would give a document two spellings for one fact, which is the whole
     * class of problem PLAN §5.4's canonical numerics exist to prevent.
     */
    @Serializable
    @SerialName("nullable")
    public data class Nullable(public val inner: TypeRef) : TypeRef {
        override val serialTag: String = "nullable"
        override val runtimeType: RuntimeType = RuntimeType.Optional
    }

    /** A homogeneous list, `List<T>`. */
    @Serializable
    @SerialName("list")
    public data class ListOf(public val element: TypeRef) : TypeRef {
        override val serialTag: String = "list"
        override val runtimeType: RuntimeType = RuntimeType.List
    }

    /**
     * A map with `String` keys, `Map<String, V>`.
     *
     * **No value inhabits this type today.** `Value` has no map variant: §5.4's closed union
     * has a list and an object and no map, and adding one would be the schema-version event
     * §5.4 reserves for a new variant. §9.2's Map row says the wire form is "`obj` with typed
     * fields or `list` of pairs", which is a statement about how a *schema* would encode a
     * map, not about a `Value` variant — and a document cannot be written for this type until
     * the union grows. The type is declared because §9.1 declares it, and declaring it is
     * what makes the gap visible to the next person instead of leaving them to find it by
     * writing a document that will not validate.
     */
    @Serializable
    @SerialName("map")
    public data class MapOf(public val value: TypeRef) : TypeRef {
        override val serialTag: String = "map"
        override val runtimeType: RuntimeType = RuntimeType.Map
    }

    // -----------------------------------------------------------------------------------
    // Data-defined types
    // -----------------------------------------------------------------------------------

    /**
     * An enum type declared by a plugin or by the document itself, named by its [TypeId].
     *
     * The entry names are not in the document because the document cannot know them: the set of
     * entries belongs to the `EnumTypeSpec` that declares this type (§5.4's D3, which puts
     * extensibility in data-defined types rather than in new `Value` variants). That is why
     * this type carries a `TypeId` and not a list of entries.
     */
    @Serializable
    @SerialName("enum")
    public data class Enum(public val id: TypeId) : TypeRef {
        override val serialTag: String = "enum"
        override val runtimeType: RuntimeType = RuntimeType.EnumValue
    }

    /**
     * A structured record: a data model, or a typed group of properties.
     *
     * The shape is declared, not written, for the same reason as [Enum] — and it is the reason
     * D4 is possible at all: a `Value.Obj` carries only a `TypeId` and its fields, so a
     * document naming a data model this engine has never heard of still decodes.
     */
    @Serializable
    @SerialName("object")
    public data class Object(public val id: TypeId) : TypeRef {
        override val serialTag: String = "object"
        override val runtimeType: RuntimeType = RuntimeType.ObjectValue
    }

    // -----------------------------------------------------------------------------------
    // Resolved at runtime
    // -----------------------------------------------------------------------------------

    /**
     * A reference to something in the document, of one [RefKind].
     *
     * The *target's id type* is decided by the kind, not carried here, and the reference is
     * resolved by analysis rather than by the model — §9.2 says "resolved by analysis" and
     * "target exists/kind ok", and PLAN §5.6's ReferenceIndex is the thing that answers those.
     */
    @Serializable
    @SerialName("ref")
    public data class Ref(public val kind: RefKind) : TypeRef {
        override val serialTag: String = "ref"
        override val runtimeType: RuntimeType = RuntimeType.Reference
    }

    /** A theme token of one [TokenKind], resolved by the selected theme. */
    @Serializable
    @SerialName("token")
    public data class Token(public val kind: TokenKind) : TypeRef {
        override val serialTag: String = "token"
        override val runtimeType: RuntimeType = RuntimeType.ThemeToken
    }

    /**
     * An icon looked up in a registered icon set.
     *
     * The set is nullable and that is the only interesting thing about this variant, so it is
     * worth saying why: a property may say "an icon, from whichever set is the default here"
     * and leave the choice to the environment, which is a real difference between a *type*
     * and a value. `Value.Icon.set` is not nullable, and the asymmetry is deliberate — see
     * [Dimension] for the argument.
     */
    @Serializable
    @SerialName("icon")
    public data class Icon(public val set: String?) : TypeRef {
        override val serialTag: String = "icon"
        override val runtimeType: RuntimeType = RuntimeType.Icon
    }

    // -----------------------------------------------------------------------------------
    // Post-MVP
    // -----------------------------------------------------------------------------------

    /**
     * `Dp`, a fill fraction, or wrap — post-MVP, and declared anyway.
     *
     * §9.1 marks this variant post-MVP, and the declaration is still worth its cost for a
     * reason that has nothing to do with fill: a `@SerialName`d `data object` is four lines,
     * and having it here means a document written against a later engine decodes on this one
     * instead of failing. **Declared, unused, and this comment is why** — nothing in the
     * engine reads it yet, and the first person to reach for it should read this first.
     *
     * No value inhabits it, which is the part worth being explicit about. Of the three cases,
     * only `Dp` has a `Value` variant — `Value.Dp` — because the other two are codegen shapes
     * (`Modifier.fillMaxWidth()`) with nothing to store. A document that writes a fill will
     * fail validation, and that is the correct outcome for a post-MVP type used early: the
     * failure is at the point of authoring, not at the point of rendering.
     */
    @Serializable
    @SerialName("dimension")
    public data object Dimension : TypeRef {
        override val serialTag: String = "dimension"
        override val runtimeType: RuntimeType = RuntimeType.Dimension
    }
}
