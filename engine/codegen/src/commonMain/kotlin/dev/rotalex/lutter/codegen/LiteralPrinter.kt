package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * A literal's text plus the imports it needs (`dp`, `Color`, a theme member).
 *
 * Null means the value has no skeleton spelling: containers, references and icons are
 * refused by the generator, not guessed at.
 */
internal data class EmittedLiteral(val text: String, val symbols: List<KotlinSymbol>)

/** Values to Kotlin text. Returns null where the skeleton has no valid spelling. */
internal object LiteralPrinter {
    private val ComposeColor: KotlinSymbol = KotlinSymbol("androidx.compose.ui.graphics", "Color")
    private val DpUnit: KotlinSymbol = KotlinSymbol("androidx.compose.ui.unit", "dp")
    private val SpUnit: KotlinSymbol = KotlinSymbol("androidx.compose.ui.unit", "sp")
    private val MaterialTheme: KotlinSymbol =
        KotlinSymbol("androidx.compose.material3", "MaterialTheme")
    private val HexDigits: String = "0123456789ABCDEF"

    fun emit(value: Value): EmittedLiteral? {
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
            is Value.Enum -> EmittedLiteral(value.entry, emptyList())
            is Value.Token -> tokenOf(value)
            is Value.Ref -> null
            is Value.Icon -> null
            is Value.ListOf -> null
            is Value.Obj -> null
            is Value.MapOf -> null
        }
        return emitted
    }

    // A token is a member off the matching theme group; dimensions have no group yet.
    private fun tokenOf(token: Value.Token): EmittedLiteral? {
        val group: String = if (token.kind == TokenKind.Color) "colorScheme"
        else if (token.kind == TokenKind.Typography) "typography"
        else if (token.kind == TokenKind.Shape) "shapes"
        else return null
        return EmittedLiteral(
            "MaterialTheme." + group + "." + token.name,
            listOf(MaterialTheme),
        )
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
