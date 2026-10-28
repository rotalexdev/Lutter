package dev.rotalex.lutter.model.type

/**
 * What evaluating a value of a type produces at runtime — the second column of PLAN §9.2,
 * as a closed vocabulary rather than a table in a document.
 *
 * ### Why this is an enum and not a `KClass`
 *
 * §9.2's Runtime evaluation column names the answers a backend produces: `String`,
 * `Boolean`, `Int`, Compose's `Color`, `Dp`, `TextUnit`, `ResourceProvider`, `IconSet`,
 * `Evaluator`, `ObjectValue`, `ActionSequence`. Two of those are Compose types, four are
 * types this repository has not written yet, and all of them live in `:engine:runtime` or
 * `:engine:codegen`. Naming any of them in a model signature would be a lie about where
 * they live, and a `KClass` would additionally be a JVM-shaped answer to a question asked by
 * a module that compiles for Wasm.
 *
 * So this enum names the *category* of the answer and nothing more. `RuntimeType.Dp` says
 * "a density-independent pixel quantity, and this module does not have one"; it does not say
 * `androidx.compose.ui.unit.Dp`, because that class is somebody else's, and a document has
 * no business knowing it. PLAN §9.2's own wording agrees: the column is titled *Runtime
 * evaluation*, not *Runtime type*.
 *
 * ### What is deliberately absent
 *
 * The other three columns of §9.2 — editor metadata, validation, codegen — have no entries
 * here, and adding them would be actively wrong. The editor column is `EditorHints` and
 * belongs to `:engine:schema`; the validation column is `PropertyRule` and the spec's bounds
 * live with the spec; the codegen column is a `KotlinSymbol` and a binding, and it belongs to
 * `:engine:codegen`. A model that carried them would be a document format wearing a UI
 * toolkit, and the editor's needs would become the document format's needs. Two of the five
 * dimensions are facts the model owns and two of them are facts other modules own; the third
 * (serialization) is a fact about this module's own annotations. The mapping is exposed as
 * [TypeRef.serialTag] and [TypeRef.runtimeType], and the other three are read where they
 * belong.
 *
 * @see TypeRef for the type side of the mapping.
 */
public enum class RuntimeType {

    /** A `Boolean`. */
    Boolean,

    /** A 32-bit `Int`, from [TypeRef.Int32]. */
    Int,

    /** A 64-bit `Long`, from [TypeRef.Int64]. */
    Long,

    /** A 32-bit `Float`, from [TypeRef.Float32]. */
    Float,

    /** A 64-bit `Double`, from [TypeRef.Float64]. */
    Double,

    /**
     * A `String`, from [TypeRef.Str] and from [TypeRef.Url].
     *
     * One entry for two types because §9.2's Runtime column gives `String` for both. The
     * difference between them is in the *validation* column — a URL is checked for RFC-3986
     * syntax — and inventing a distinct runtime answer for it would be asserting a type the
     * plan does not have.
     */
    Text,

    /**
     * A colour, from [TypeRef.Color], marshalled to Compose's `Color`.
     *
     * The marshalling is `Color(color.argb)` with no conversion, which is the reason
     * `ColorArgb` packs into an `Int` rather than a `ULong` — see its KDoc.
     */
    Color,

    /** A density-independent pixel quantity, from [TypeRef.Dp]. */
    Dp,

    /** A scaled-pixel quantity for text, from [TypeRef.Sp]. */
    TextUnit,

    /**
     * A dimension, from [TypeRef.Dimension]: `Dp`, a fill fraction, or wrap.
     *
     * Its own entry rather than [Dp] because the union has three cases and only one of them
     * is a `Dp`. Post-MVP, and only [Dp] is inhabited today — see the variant's KDoc.
     */
    Dimension,

    /**
     * The inner type made optional, from [TypeRef.Nullable].
     *
     * A wrapper, not a value: which nullable you hold is decided by the value, not by the
     * enum. It has its own entry so that reading a type's runtime answer never returns a
     * half-answered "it depends".
     */
    Optional,

    /** A `List<T>`, from [TypeRef.ListOf]. */
    List,

    /** A `Map` with `String` keys, from [TypeRef.MapOf]. */
    Map,

    /**
     * A closed runtime map per enum type, from [TypeRef.Enum].
     *
     * §9.2 gives the answer as "closed runtime map per enum type", so the entry says the same
     * thing: there is no enum runtime type in the model, and there cannot be one, because the
     * set of entries is a plugin's to declare.
     */
    EnumValue,

    /** A structured record, from [TypeRef.Object], holding a document's data models. */
    ObjectValue,

    /** A resolved reference, from [TypeRef.Ref] — resolved by analysis, not by the model. */
    Reference,

    /** A theme token, from [TypeRef.Token], resolved by the theme. */
    ThemeToken,

    /**
     * An icon looked up in a registered icon set, from [TypeRef.Icon].
     *
     * §9.2's answer is "`IconSet` lookup", and a lookup is a data question: the set is
     * registered by a plugin. Nothing about it is a model concern beyond knowing that the
     * answer is a lookup.
     */
    Icon,
}
