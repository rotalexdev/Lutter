package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.ColorArgbSerializer
import dev.rotalex.lutter.model.value.Value
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One colour role's light and dark values, §14.1.
 *
 * A pair rather than a nullable, because "this role has no dark value" and "this role is not
 * in the theme" are different facts, and only one of them is expressible as an absent entry in
 * `ThemeDecl.colors`.
 *
 * The two serializer annotations sit on the properties rather than on `ColorArgb` itself,
 * which is this project's decision and not a preference: `@Serializable(with = …)` on the
 * value class compiles and then throws on Wasm, so the wire form is declared where a use site
 * cannot forget it. `Value.Color` already carries the same two annotations, and `#AARRGGBB` is
 * the form §9.2's Serialization column specifies.
 *
 * `ThemeDecl` and the theme vocabulary — `ThemeBase`, the four role value classes and the two
 * spec aliases — are the rest of this file, per §33.1's `doc/ThemeDecl.kt` row.
 */
@Serializable
public data class ColorSpec(

    /** The light appearance, written `#AARRGGBB`. */
    public @Serializable(ColorArgbSerializer::class) val light: ColorArgb,

    /** The dark appearance. Omitted from the wire form when there is not one. */
    public @Serializable(ColorArgbSerializer::class) val dark: ColorArgb? = null,
)

/**
 * A theme: the token maps a document declares, under the base they resolve against.
 *
 * PLAN §14.1 declares all nine fields. Five of them are token maps keyed by a role, and the
 * argument for why those roles are open strings is the whole point of the file below — a
 * wrong role is `token.unknown` at analysis time (§14.2, §17.3), not a constructor failure,
 * so nothing needs the vocabulary closed for the document to be rejected.
 *
 * `dimensions` is typed `Value.Dp` and not `Value` because a dimension scale has one unit.
 * `custom` is a plain [Value] because a brand token is not necessarily a number — `brand.accent`
 * is a colour and `brand.gap` is not.
 */
@Serializable
public data class ThemeDecl(

    /** The id, and what `UiDocument.theme` selects. */
    public val id: ThemeId,

    /** A label, so a person can tell two themes apart in a list. */
    public val name: String,

    /** The design system the tokens resolve against. The one closed type in §14.1. */
    public val base: ThemeBase = ThemeBase.Material3,

    /** Colour roles, each a light/dark pair. */
    public val colors: Map<ColorRole, ColorSpec> = emptyMap(),

    /** Typography roles. */
    public val typography: Map<TextRole, TextStyleSpec> = emptyMap(),

    /** Shape roles. */
    public val shapes: Map<ShapeRole, ShapeSpec> = emptyMap(),

    /** The spacing and size scale, in `dp`. */
    public val dimensions: Map<TokenName, Value.Dp> = emptyMap(),

    /** Brand tokens, of any kind. `md.*` and `brand.*` are addressed the same way (§14.1). */
    public val custom: Map<TokenName, Value> = emptyMap(),

    /** Per-component property defaults. Post-MVP (§14.2), declared so the format can carry it. */
    public val componentDefaults: Map<ComponentType, Map<PropertyKey, PropertyValue>> = emptyMap(),
)

/**
 * The design system a theme's tokens resolve against — the one closed type in §14.1.
 *
 * A *name* can be validated by lookup, which §14.2 already does for a role; a *base* has to
 * be dispatched on, because `:engine:runtime` has to build a Compose `MaterialTheme` from it
 * and `:engine:codegen` has to emit `lightColorScheme(…)` from it. An open hierarchy would be
 * worse than useless, because nothing in the model could resolve one: a document can register
 * new *types* through the data-defined mechanism D3 provides, and there is no such mechanism
 * for a design system — a branch over an unknown base would have no arm that is honest.
 *
 * [Material3] is the only entry the text names, and a second one is a `FORMAT_VERSION` event
 * because an unknown enum tag fails to decode.
 */
@Serializable
public enum class ThemeBase {

    /** Material 3. The MVP base, and the default. */
    @SerialName("material3")
    Material3,
}

/**
 * A typography role: `headlineMedium`, or anything else the schema declares.
 *
 * The four role value classes below are one pattern and not four decisions, and they are
 * value classes for the reason §5.2 gives for the ids: a role on the wire is a string, and a
 * bare `String` in a signature is a string nothing can validate.
 *
 * **The check is `isNotBlank()` and not a grammar, and the rest is `token.unknown`'s to
 * report.** That is the cost of leaving the vocabulary open, stated rather than hidden: a
 * typo in a role name is caught by analysis and not by `init`. The alternative was writing
 * Material 3's role names down as the variants, which would make every rename a
 * `FORMAT_VERSION` event before anyone has built a theme — and §14.2 already mandates the
 * validation that giving the vocabulary up buys.
 */
@JvmInline
@Serializable
public value class TextRole(public val value: String) {
    init {
        require(value.isNotBlank()) { "Blank TextRole" }
    }

    override fun toString(): String = value
}

/** A colour role: `primary`, or anything else the schema declares. See [TextRole]. */
@JvmInline
@Serializable
public value class ColorRole(public val value: String) {
    init {
        require(value.isNotBlank()) { "Blank ColorRole" }
    }

    override fun toString(): String = value
}

/** A shape role: `small`, or anything else the schema declares. See [TextRole]. */
@JvmInline
@Serializable
public value class ShapeRole(public val value: String) {
    init {
        require(value.isNotBlank()) { "Blank ShapeRole" }
    }

    override fun toString(): String = value
}

/**
 * The name of a token outside the three role maps: `md.color.primary`, `brand.accent`.
 *
 * Namespaced and dotted in the shape §5.2 gives `ComponentType`, which is also why these four
 * do **not** use `IdSyntax`: that grammar is `[A-Za-z0-9_]{1,64}` and has no room for a dot.
 */
@JvmInline
@Serializable
public value class TokenName(public val value: String) {
    init {
        require(value.isNotBlank()) { "Blank TokenName" }
    }

    override fun toString(): String = value
}

/**
 * A text style: size, weight, line height, letter spacing — whatever the map holds.
 *
 * A typealias rather than a `data class` because there is nothing to add: [PropertyKey] and
 * [Value] are already serializable, so the map is, and a wrapper would be a record whose
 * only field is its own payload. The keys are not enumerated here and that is a real gap:
 * §14.1 declares the map and §14.2 names "typography roles" as its content, but no section
 * lists the properties. The key set belongs to the schema, and a key outside it is
 * `prop.unknown` (§17.3) — the existing code for exactly this.
 */
public typealias TextStyleSpec = Map<PropertyKey, Value>

/** A shape: corner radii, as the same open map [TextStyleSpec] is. */
public typealias ShapeSpec = Map<PropertyKey, Value>
