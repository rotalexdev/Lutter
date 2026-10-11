package dev.rotalex.lutter.analysis.resolved

import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.value.Value

/**
 * The selected theme, if the document selects one (§14.2: explicit, or the only entry).
 *
 * Null theme means nothing to resolve a custom name against; `md.*` still resolves.
 */
public data class ResolvedTheme(public val theme: ThemeDecl?)

/**
 * Where a token resolved to: the design system, or the document's own brand map.
 */
public enum class TokenSource {
    Material,
    Custom,
}

/**
 * A token with its provenance decided: kind and name from the document, source from the theme.
 */
public data class ResolvedToken(
    public val kind: TokenKind,
    public val name: String,
    public val source: TokenSource,
)

/** Picks the theme tokens resolve against, or null when the document selects none. */
internal fun selectTheme(document: UiDocument): ThemeDecl? {
    val selected = document.theme
    if (selected != null) return document.themes[selected]
    if (document.themes.size == 1) return document.themes.values.single()
    return null
}

/** Whether [name] is declared anywhere in [theme], by exact key match. */
internal fun themeKnows(theme: ThemeDecl, name: String): Boolean =
    theme.colors.keys.any { it.value == name } ||
        theme.typography.keys.any { it.value == name } ||
        theme.shapes.keys.any { it.value == name } ||
        theme.dimensions.keys.any { it.value == name } ||
        theme.custom.keys.any { it.value == name }

/**
 * Decides a token's source: a name the selected theme declares is the document's own, the rest
 * is Material's.
 *
 * [themeKnows] and not a lookup in `ThemeDecl.custom`, because those are the two halves of one
 * question and pass 4 already asks it with `themeKnows` to decide whether the token is legal at
 * all. Answering it for one map here and for all five there would mark a legal token `Material`:
 * a role the document wrote in `typography`, `colors`, `shapes` or `dimensions` is as much its own
 * as one in `custom`, and codegen writes a `Material` token as a member off the matching
 * `MaterialTheme` group — so a role Material does not publish becomes a name in a generated file
 * that does not compile.
 */
internal fun resolveToken(token: Value.Token, theme: ThemeDecl?): ResolvedToken {
    val custom = theme != null && themeKnows(theme, token.name)
    val source = if (custom) TokenSource.Custom else TokenSource.Material
    return ResolvedToken(token.kind, token.name, source)
}

/**
 * Whether [name] is in Material's namespace, which §14.2 resolves through `MaterialTheme.*`
 * whether or not the document declares a theme.
 *
 * An MVP rule that Phase 8 reverses: MVP is the Material 3 base, so a document selecting no theme
 * resolves `md.*` to Material's defaults. Real themes make an absent theme an error instead.
 */
internal fun isMaterialToken(name: String): Boolean = name.startsWith(MATERIAL_PREFIX)

/** The `md.` prefix §14.2's runtime and codegen rows name Material's tokens by, and nothing else. */
private const val MATERIAL_PREFIX: String = "md."
