package dev.rotalex.lutter.model.type

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kinds of theme token a document can name (PLAN §9.1, §9.2, §14).
 *
 * ### Four entries, and why "Custom" is not a fifth
 *
 * §14.1's `ThemeDecl` carries five token maps — `colors`, `typography`, `shapes`,
 * `dimensions` and `custom` — and the first four are obviously kinds. `custom` is not, and
 * §14.2 says so directly: the analyzer resolves every token to
 * `ResolvedToken(kind, name, source = Material | Custom)`. **`Custom` is a source, not a
 * kind.** A brand accent is a colour token that happens to come from the document instead of
 * from Material, so it is `TokenKind.Color` with a name in the custom namespace, and the
 * analyzer decides its provenance. Collapsing the two axes into one enum would make
 * `brand.accent` and `md.color.primary` the same kind of thing, and §9.2's codegen column
 * already shows they are not: the first becomes `MaterialTheme.colorScheme.primary` and the
 * second becomes `AppTokens.brand.accent` — a lookup by name in a generated class, which is
 * why codegen does not need to know a custom token's kind at all.
 *
 * The cost of that reading is that a custom token of a type outside the four roles has
 * nowhere to say what it is. §14.1's MVP line names "custom color/dp tokens", and a custom
 * colour is [Color] and a custom `dp` is [Dimension], so MVP is covered exactly. When a
 * post-MVP role appears — an elevation token, a motion duration — it becomes a fifth entry,
 * and a stored document that names it will be unreadable to an engine without it, which is
 * the same cost every `@SerialName` in this module carries.
 *
 * ### Why these are persisted at all
 *
 * `TokenKind` appears inside `Value.Token` and `TypeRef.Token`, both of which are written to
 * disk, so this enum is part of the format and every entry carries an explicit `@SerialName`.
 * The spellings are lower case and singular — `color`, `typography`, `shape`, `dimension` —
 * singular because §14.1's role types are singular (`ColorRole`, `TextRole`, `ShapeRole`)
 * and the `ThemeDecl` field names are the plural forms of the same vocabulary. PLAN's own
 * example writes `"kind": "typography"` for `TokenKind.Typography`, and that is the
 * precedent followed here for all four.
 *
 * @see RefKind, the other persisted kind enum.
 */
@Serializable
public enum class TokenKind {

    /** A colour role: `md.color.primary`, or a custom brand colour. */
    @SerialName("color")
    Color,

    /** A text style role: `md.typography.headlineMedium`. */
    @SerialName("typography")
    Typography,

    /** A shape role: corner rounding and similar. */
    @SerialName("shape")
    Shape,

    /**
     * A dimension token: the spacing scale, and the custom `dp` tokens of §14.1's MVP line.
     *
     * Named `Dimension` rather than `Dp` because the value behind a dimension token is a
     * number and the role is the spacing scale, not the unit — a document that wrote
     * `{"type":"token","kind":"dp"}` would be claiming a unit where the plan has a role.
     */
    @SerialName("dimension")
    Dimension,
}
