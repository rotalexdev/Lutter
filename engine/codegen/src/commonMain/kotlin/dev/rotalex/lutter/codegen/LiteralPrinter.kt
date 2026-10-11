package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedToken
import dev.rotalex.lutter.analysis.resolved.TokenSource
import dev.rotalex.lutter.model.doc.TextStyleSpec
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * A literal's text plus the imports it needs (`dp`, `Color`, a theme member).
 *
 * Null means the value has no skeleton spelling here: containers, references, icons and enum
 * entries are refused by the generator, not guessed at.
 */
internal data class EmittedLiteral(val text: String, val symbols: List<KotlinSymbol>)

/** Values to Kotlin text. Returns null where the skeleton has no valid spelling. */
internal object LiteralPrinter {
    private val ComposeColor: KotlinSymbol = KotlinSymbol("androidx.compose.ui.graphics", "Color")
    private val DpUnit: KotlinSymbol = KotlinSymbol("androidx.compose.ui.unit", "dp")
    private val SpUnit: KotlinSymbol = KotlinSymbol("androidx.compose.ui.unit", "sp")
    private val MaterialTheme: KotlinSymbol =
        KotlinSymbol("androidx.compose.material3", "MaterialTheme")
    private val ComposeTextStyle: KotlinSymbol = KotlinSymbol("androidx.compose.ui.text", "TextStyle")

    /**
     * The name prefix and the `MaterialTheme` group each kind's `md.` names reach, §14.1's
     * `md.color.primary` on the left and §9.2's `MaterialTheme.colorScheme.primary` on the
     * right.
     *
     * `TokenKind.Dimension` is **absent**, and that is the answer rather than a gap: Material 3
     * publishes no spacing scale under `MaterialTheme`, so a dimension token resolves through the
     * document's own `dimensions` map and a `md.dimension.*` name is refused instead of being
     * written out as a member that does not exist.
     */
    private val MaterialGroups: Map<TokenKind, Pair<String, String>> = mapOf(
        TokenKind.Color to ("color" to "colorScheme"),
        TokenKind.Typography to ("typography" to "typography"),
        TokenKind.Shape to ("shape" to "shapes"),
    )

    private val MaterialPrefix: String = "md."

    /**
     * The typography roles Material 3 publishes.
     *
     * Checked here because `MaterialTheme.typography` is the one token group the runtime reads
     * back (§14.2's runtime row), so a role this set omits is one the two backends disagree
     * about — and §16.2 asks for a located finding rather than a red build in the consumer's
     * tree. Colour and shape roles are written through unchecked, and the reason is a limit
     * rather than a preference: no property in the engine takes a colour or a shape token, so
     * nothing on this side can yet be wrong about them.
     *
     * The runtime's own copy of this vocabulary is `DefaultThemeHandle.materialTextStyle`, which
     * is a `when` over the same fifteen members because Kotlin has no member-by-name lookup. The
     * two are a pair, and the conformance fixture is what holds them together.
     */
    private val MaterialTextRoles: Set<String> = setOf(
        "displayLarge", "displayMedium", "displaySmall",
        "headlineLarge", "headlineMedium", "headlineSmall",
        "titleLarge", "titleMedium", "titleSmall",
        "bodyLarge", "bodyMedium", "bodySmall",
        "labelLarge", "labelMedium", "labelSmall",
    )

    /**
     * [value] as Kotlin text, or null where the skeleton has no valid spelling.
     *
     * [token] and [theme] are the provenance pass 7 decided and the theme a document-owned value
     * is read out of. Both are nullable because three of the four call sites have no
     * `ResolvedProp` to carry them — an action argument, a state initializer and a constant
     * inside a computed expression reach this printer with nothing but the value — and a token
     * arriving there is **refused**, never assumed to be Material's. §16.2's rule is the reason:
     * a generated file naming a `MaterialTheme` member that does not exist is a compile error in
     * the consumer's tree, and a caller that cannot say where a token came from cannot say
     * whether it is there.
     */
    fun emit(value: Value, token: ResolvedToken? = null, theme: ThemeDecl? = null): EmittedLiteral? {
        val emitted: EmittedLiteral? = when (value) {
            is Value.Null -> EmittedLiteral("null", emptyList())
            is Value.Bool -> EmittedLiteral(if (value.v) "true" else "false", emptyList())
            is Value.Int32 -> EmittedLiteral(value.v.toString(), emptyList())
            is Value.Int64 -> EmittedLiteral(value.v.toString() + "L", emptyList())
            is Value.Float32 -> EmittedLiteral(floatText(value.v.toDouble()) + "f", emptyList())
            is Value.Float64 -> EmittedLiteral(floatText(value.v), emptyList())
            is Value.Str -> EmittedLiteral("\"" + escape(value.v) + "\"", emptyList())
            is Value.Url -> EmittedLiteral("\"" + escape(value.v) + "\"", emptyList())
            is Value.Color -> EmittedLiteral(
                "Color(0x" + value.argb.toHexString().substring(1) + ")",
                listOf(ComposeColor),
            )
            is Value.Dp -> EmittedLiteral(floatText(value.v.toDouble()) + ".dp", listOf(DpUnit))
            is Value.Sp -> EmittedLiteral(floatText(value.v.toDouble()) + ".sp", listOf(SpUnit))
            // An enum entry spells itself through the symbol its `EnumTypeSpec` declares, and
            // this printer is given nothing but the value, so the generator answers those.
            is Value.Enum -> null
            is Value.Token -> tokenOf(token, theme)
            is Value.Ref -> null
            is Value.Icon -> null
            is Value.ListOf -> null
            is Value.Obj -> null
            is Value.MapOf -> null
        }
        return emitted
    }

    // §9.2's codegen column, read against the provenance analysis already decided: Material's
    // own token is a member off the matching group, and the document's is the value its theme
    // wrote down.
    private fun tokenOf(token: ResolvedToken?, theme: ThemeDecl?): EmittedLiteral? {
        if (token == null) return null
        return when (token.source) {
            TokenSource.Material -> materialToken(token)
            TokenSource.Custom -> customToken(token, theme)
        }
    }

    // `md.typography.bodyLarge` → `MaterialTheme.typography.bodyLarge`. The `md.<kind>.` prefix
    // is the document's spelling of "Material's" and is not part of the member name, so a token
    // whose name is not shaped like one is refused rather than pasted into the group.
    private fun materialToken(token: ResolvedToken): EmittedLiteral? {
        val addressed: Pair<String, String> = MaterialGroups[token.kind] ?: return null
        val role: String = materialRole(token, addressed.first) ?: return null
        if (token.kind == TokenKind.Typography && role !in MaterialTextRoles) return null
        return EmittedLiteral(
            "MaterialTheme." + addressed.second + "." + role,
            listOf(MaterialTheme),
        )
    }

    private fun materialRole(token: ResolvedToken, segment: String): String? {
        if (!token.name.startsWith(MaterialPrefix)) return null
        val body: String = token.name.removePrefix(MaterialPrefix)
        val separator: Int = body.indexOf('.')
        if (separator <= 0 || separator == body.length - 1) return null
        if (body.substring(0, separator) != segment) return null
        return body.substring(separator + 1)
    }

    /**
     * The document's own token, as the value its theme declares — inlined rather than looked up.
     *
     * §32 names a generated `AppTokens` object as the means; the acceptance is that a token
     * resolves *identically* on both backends, and a literal satisfies that structurally, because
     * there is no lookup here for the runtime's answer to drift from. Generating an object would
     * give the runtime something to look up and create the one place the two could drift — the
     * failure the conformance suite exists to catch, paid for once.
     *
     * A theme's `colors` and `shapes` maps refuse, and the reasons are the same kind of fact:
     * a colour role is a light/dark pair and codegen cannot see which one is in force, and a
     * shape role is an open map with no agreed key set. Neither has a literal to write, so
     * neither gets a guessed one.
     */
    private fun customToken(token: ResolvedToken, theme: ThemeDecl?): EmittedLiteral? {
        val declared: ThemeDecl = theme ?: return null
        if (token.kind == TokenKind.Typography) {
            val role: TextStyleSpec = declared.typography.entries
                .firstOrNull { it.key.value == token.name }?.value ?: return null
            return textStyle(role)
        }
        // The spacing scale is a dimension token's home and the brand map is every kind's, in
        // that order: `ThemeDecl.dimensions` is the one place §14.1 says a scale lives, and a
        // `custom` entry may hold a `Dp` under a name a document chose.
        val scale: Value? = if (token.kind == TokenKind.Dimension) {
            named(declared.dimensions, token.name)
        } else {
            null
        }
        return literalFor(token, scale ?: named(declared.custom, token.name))
    }

    /**
     * The value [name] addresses in [scale], or null.
     *
     * An entry walk rather than a subscript because the key is a `TokenName` — a value class
     * whose constructor rejects a blank name — and a token name is a bare `String` a document
     * wrote. Subscripting would throw on the one name that is not there.
     */
    private fun <V> named(scale: Map<TokenName, V>, name: String): V? =
        scale.entries.firstOrNull { it.key.value == name }?.value

    /**
     * A brand value has to be of the kind the token says it is.
     *
     * Nothing upstream checks it — `ThemeDecl.custom` is a `Map<TokenName, Value>` and a document
     * can write any variant under any name — and a `str` inlined where a colour belongs is code
     * that does not compile. Typography and shape have no `Value` at all: a `TextStyle` is
     * composed and a shape is a corner set, which is why the typography arm above reads the role
     * map instead and why a shape token has nothing to inline.
     */
    private fun literalFor(token: ResolvedToken, value: Value?): EmittedLiteral? {
        if (token.kind == TokenKind.Color && value is Value.Color) return emit(value)
        if (token.kind == TokenKind.Dimension && value is Value.Dp) return emit(value)
        return null
    }

    // `TextStyle(fontSize = 28.0.sp, color = Color(0xFF00695C))`: the theme's own entry, written
    // out rather than looked up, so the runtime can build the same style from the same numbers.
    // Sorted for determinism, and a key this engine does not spell refuses the whole call rather
    // than being dropped from it — a silently missing argument is how two backends come to
    // disagree about a font.
    private fun textStyle(spec: TextStyleSpec): EmittedLiteral? {
        val arguments = mutableListOf<String>()
        val symbols = mutableListOf<KotlinSymbol>()
        for (entry in spec.entries.sortedBy { it.key.value }) {
            val emitted: EmittedLiteral = styleValue(entry.key, entry.value) ?: return null
            arguments += entry.key.value + " = " + emitted.text
            symbols += emitted.symbols
        }
        if (arguments.isEmpty()) return null
        return EmittedLiteral(
            "TextStyle(" + arguments.joinToString(", ") + ")",
            listOf(ComposeTextStyle) + symbols,
        )
    }

    private fun styleValue(key: PropertyKey, value: Value): EmittedLiteral? = when (key.value) {
        "fontSize", "lineHeight", "letterSpacing" -> if (value is Value.Sp) emit(value) else null
        "color" -> if (value is Value.Color) emit(value) else null
        else -> null
    }

    // Canonical magnitudes never print in scientific notation, so this only pins `.0`.
    private fun floatText(value: Double): String {
        val text: String = value.toString()
        if (text.contains('.') || text.contains('E') || text.contains('e')) return text
        return text + ".0"
    }

    // Kotlin escaping: quotes, backslashes, control chars and `$` (template Marker).
    internal fun escape(text: String): String = buildString {
        for (char in text) {
            if (char == '"') append("\\\"")
            else if (char == '\\') append("\\\\")
            else if (char == '\n') append("\\n")
            else if (char == '\r') append("\\r")
            else if (char == '\t') append("\\t")
            else if (char == '$') append("\\$")
            else if (char < ' ') appendUnicode(char)
            else append(char)
        }
    }

    private fun StringBuilder.appendUnicode(char: Char): Unit {
        val code: Int = char.code
        append("\\u")
        append(HexDigits[(code ushr 12) and 0xF])
        append(HexDigits[(code ushr 8) and 0xF])
        append(HexDigits[(code ushr 4) and 0xF])
        append(HexDigits[code and 0xF])
    }
}
