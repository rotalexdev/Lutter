package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.type.TypeRef

/**
 * The [ValueKind]s that can exist without a schema, and the eight types that are waiting for
 * one.
 *
 * ### The rule, which is the whole design of this file
 *
> A kind exists in `:engine:model` exactly when its `decode` is **total and schema-free**.
>
 * The test is not "is it a scalar". It is: can this method produce a model type from the
> value alone, for every value of this variant, without asking a registry anything? For
 * `Value.Dp` the answer is yes — the number is in the value, and the unit is the type's
 * business. For `Value.Obj` the answer is no: the fields are in the value but the *shape* is
> named by a `TypeId`, and turning a name into a shape is a registry lookup.
 *
> The rule earns its keep in the one direction that matters, which is that it cannot be faked.
 * Anything that needs a schema cannot be here, because §23.3 says this module may depend on
 * no other module and `ModuleGraphRules.ALLOWED` enforces it as `:engine:model` to an empty
 * set. A kind that quietly consulted a schema would be a compile error, so the split here is
 * the module graph's split rather than a preference about where code is comfortable.
 *
 * ### What the eleven are
 *
 * The scalars, plus `EnumEntryValueKind`. Every one of them decodes to a model type the
 * renderer can hand on: a `Boolean`, a `Float`, a `ColorArgb`, an entry name.
 *
 * `DpValueKind` and `SpValueKind` both decode to a `Float`, and that is not a shortcut. The
 * *unit* is a property of the type — that is the entire difference between a `dp` and an `sp` —
 * and the conversion to Compose's `Dp` or `TextUnit` belongs in `:engine:runtime`'s
 * marshalling helpers, where §9.3 puts it. A model `Dp` value class would be a second number
 * type for a quantity the value already holds, and two of them would need a rule saying which
 * one wins.
 *
 * ### What the other eight are waiting for
 *
 *  * **`TypeRef.Icon` — the `IconSet`.** A value is a set and a name; what a renderer wants is
 *    the looked-up icon, and the set is a plugin's to register.
 *  * **`TypeRef.Dimension` — a value for its fill and wrap cases.** Only the `Dp` case is
 *    expressible today, and `Value.Dp` already covers that.
 *  * **`TypeRef.Nullable` — a kind for the inner type.** "Is this a `T?`" is a question about
 *    `T`, and the wrapper decides nothing on its own.
 *  * **`TypeRef.ListOf` — the element kind.** The part that makes a list kind useful, checking
 *    each element against `element`, is a question about the element.
 *  * **`TypeRef.MapOf` — a value.** No `Value` variant inhabits it; see `TypeRef.MapOf`.
 *  * **`TypeRef.Object` — the declared shape.** The fields are in the value; what they *mean*
 *    is named by a `TypeId`.
 *  * **`TypeRef.Ref` — analysis.** The id's type comes from the `RefKind` and the target comes
 *    from the document; a renderer's answer is a resolved page, which is not a model type.
 *  * **`TypeRef.Token` — the theme.** A token is a name until the selected theme turns it into
 *    a value.
 *
 * The nineteenth type is not on the list, because `EnumEntryValueKind` already answers the
 * half of it the model can: the entry's *name*. Turning a name into a closed value needs
 * the `EnumTypeSpec`, and that is a schema.
 *
 * When `:engine:schema` grows its own kinds this file is not replaced, it is extended: the
 * eleven here answer the questions a renderer can ask with nothing but a document in hand.
 *
 * ### No lookup, on purpose
 *
 * There is no `TypeRef.forKind()` and no `ValueKinds` registry object, and the absence is
 * worth stating because a reader will look for one. A lookup keyed by `TypeRef` would have to
 * be a `when` over nineteen variants returning eleven possible answers and null for the rest,
 * and the `null` is the interesting part: it is a question with no answer, and encoding it as
 * a nullable return makes every caller handle it. Naming the kinds directly says what it
 * means — a caller that wants a `dp` says `DpValueKind`, and a caller that needs to
 * *discover* the kind for a type is a caller that needs the schema, which is the next module
 * along.
 *
 * @see ValueKind for the contract, including why `toKotlin` is not part of it.
 */
// ---------------------------------------------------------------------------------------
// The scalars
// ---------------------------------------------------------------------------------------

/** `TypeRef.Bool` → `Boolean`. */
public object BoolValueKind : ValueKind<Boolean> {
    override val type: TypeRef = TypeRef.Bool
    override fun accepts(value: Value): Boolean = value is Value.Bool
    override fun decode(value: Value): Boolean =
        (value as? Value.Bool)?.v ?: wrongValue(this, value)
}

/** `TypeRef.Int32` → `Int`. Exact — there is nothing to round. */
public object Int32ValueKind : ValueKind<Int> {
    override val type: TypeRef = TypeRef.Int32
    override fun accepts(value: Value): Boolean = value is Value.Int32
    override fun decode(value: Value): Int =
        (value as? Value.Int32)?.v ?: wrongValue(this, value)
}

/** `TypeRef.Int64` → `Long`. */
public object Int64ValueKind : ValueKind<Long> {
    override val type: TypeRef = TypeRef.Int64
    override fun accepts(value: Value): Boolean = value is Value.Int64
    override fun decode(value: Value): Long =
        (value as? Value.Int64)?.v ?: wrongValue(this, value)
}

/**
 * `TypeRef.Float32` → `Float`, already canonicalized.
 *
 * Nothing rounds here: the value came off the wire through [CanonicalFloat], or it came from
 * an evaluator, and §5.4 says those are allowed to differ. The kind reports what the value
 * holds.
 */
public object Float32ValueKind : ValueKind<Float> {
    override val type: TypeRef = TypeRef.Float32
    override fun accepts(value: Value): Boolean = value is Value.Float32
    override fun decode(value: Value): Float =
        (value as? Value.Float32)?.v ?: wrongValue(this, value)
}

/** `TypeRef.Float64` → `Double`, already canonicalized, same caveat as [Float32ValueKind]. */
public object Float64ValueKind : ValueKind<Double> {
    override val type: TypeRef = TypeRef.Float64
    override fun accepts(value: Value): Boolean = value is Value.Float64
    override fun decode(value: Value): Double =
        (value as? Value.Float64)?.v ?: wrongValue(this, value)
}

/** `TypeRef.Str` → `String`, unescaped. §9.2 gives the escaping rule to codegen, not here. */
public object StringValueKind : ValueKind<String> {
    override val type: TypeRef = TypeRef.Str
    override fun accepts(value: Value): Boolean = value is Value.Str
    override fun decode(value: Value): String =
        (value as? Value.Str)?.v ?: wrongValue(this, value)
}

/**
 * `TypeRef.Url` → `String`, unvalidated.
 *
 * The same model type as [StringValueKind] and a different one, because the *type* is
 * different: §9.2's validation column is "RFC-3986 syntactic" and that check belongs to the
 * property's rule, where it can be turned off for a property that takes a relative URL. A
 * renderer asking for a URL wants the text, and the text is what it gets.
 */
public object UrlValueKind : ValueKind<String> {
    override val type: TypeRef = TypeRef.Url
    override fun accepts(value: Value): Boolean = value is Value.Url
    override fun decode(value: Value): String =
        (value as? Value.Url)?.v ?: wrongValue(this, value)
}

/**
 * `TypeRef.Color` → [ColorArgb], which is the model's own colour type.
 *
 * §9.3's parenthetical — "Renderers convert model types to Compose (`ColorArgb.toCompose()`)
 * in `:engine:runtime` helpers" — is the whole of what happens next. The conversion is
 * `Color(argb)` with no arithmetic, because the model packs the colour the way Compose reads
 * it. See [ColorArgb] for why the packing is an `Int`.
 */
public object ColorValueKind : ValueKind<ColorArgb> {
    override val type: TypeRef = TypeRef.Color
    override fun accepts(value: Value): Boolean = value is Value.Color
    override fun decode(value: Value): ColorArgb =
        (value as? Value.Color)?.argb ?: wrongValue(this, value)
}

/**
 * `TypeRef.Dp` → `Float`, in density-independent pixels.
 *
 * The unit is not in the return type because the unit is in [type], and the two cannot
 * disagree: this kind's [type] *is* `TypeRef.Dp`.
 */
public object DpValueKind : ValueKind<Float> {
    override val type: TypeRef = TypeRef.Dp
    override fun accepts(value: Value): Boolean = value is Value.Dp
    override fun decode(value: Value): Float =
        (value as? Value.Dp)?.v ?: wrongValue(this, value)
}

/**
 * `TypeRef.Sp` → `Float`, in scaled pixels.
 *
 * Identical to [DpValueKind] in every respect but the type it answers for, and it stays a
 * separate kind for the same reason `Value.Sp` stays a separate variant: the quantity is
 * different and a caller reading `DpValueKind` should not be able to hand its result to a
 * property that asked for text.
 */
public object SpValueKind : ValueKind<Float> {
    override val type: TypeRef = TypeRef.Sp
    override fun accepts(value: Value): Boolean = value is Value.Sp
    override fun decode(value: Value): Float =
        (value as? Value.Sp)?.v ?: wrongValue(this, value)
}

// ---------------------------------------------------------------------------------------
// The one kind that needs to be told which type it is for
// ---------------------------------------------------------------------------------------

/**
 * An enum type → the entry's name.
 *
 * A class rather than an object, and the only reason is the `TypeId`. A kind for an enum
 * answers for *one* enum type, and two properties with different enum types must not be able
 * to share one instance — so the type is a constructor argument rather than a constant.
 *
 * `String` is the honest answer and the limit of what the model can do. A renderer wants an
 * enum *value*, and the mapping from entry name to value is `EnumEntrySpec` (§9.2's runtime
 * column: "closed runtime map per enum type") and `EnumEntrySpec.kotlin` for codegen. Both
 * are schema. What the model can hand over without one is the name the document wrote, which
 * is also the name an error message should quote.
 */
public class EnumEntryValueKind(override val type: TypeRef.Enum) : ValueKind<String> {

    override fun accepts(value: Value): Boolean = value is Value.Enum

    override fun decode(value: Value): String =
        (value as? Value.Enum)?.entry ?: wrongValue(this, value)
}

// ---------------------------------------------------------------------------------------
// The one failure message
// ---------------------------------------------------------------------------------------

/** The tail of every refusal. Split out so the sentence, not the diagnosis, is the constant. */
private const val NOT_A_VALUE: String = "is not a value of this type"

/**
 * What every kind says when it is handed a value of another type.
 *
 * One place, because a diagnostic that names the *expected tag* is worth more than one that
 * names a class: the tag is the spelling in the document, so the sentence points at the file
 * and not at the code. The variant's simple name is on the other side for the same reason —
 * it is what the reader has to look up in the source.
 *
 * The payload is deliberately not printed. A `Value.Dp` would print its float through
 * `toString()`, which is the one spelling of a number this repository refuses to rely on
 * (§5.4, D1), and a diagnostic is a place where that habit is hardest to notice.
 */
private fun wrongValue(kind: ValueKind<*>, value: Value): Nothing =
    throw IllegalArgumentException(
        "'${kind.type.serialTag}' expected, got a ${value::class.simpleName} which $NOT_A_VALUE",
    )
