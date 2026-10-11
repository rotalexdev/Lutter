package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedToken
import dev.rotalex.lutter.analysis.resolved.TokenSource
import dev.rotalex.lutter.model.doc.ColorRole
import dev.rotalex.lutter.model.doc.ColorSpec
import dev.rotalex.lutter.model.doc.ShapeRole
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §9.2's codegen column for a token, one case at a time: `md.color.primary` is a member off
 * `MaterialTheme.colorScheme`, and the document's own token is the value its theme wrote down.
 *
 * The printer rather than the generator, because these are the cases the generator has no way to
 * reach — no property in this module takes a colour or a dimension token, and the one property
 * that takes a token at all is a typography one that `CodegenTest` already carries through the
 * whole generator. What is tested here is the decision, which is where the kinds are.
 */
class LiteralPrinterTest {

    private val theme: ThemeDecl = ThemeDecl(
        id = ThemeId("t_brand"),
        name = "Brand",
        colors = mapOf(ColorRole("brand") to ColorSpec(ColorArgb.parse("#FF00695C"))),
        typography = mapOf(
            TextRole("caption") to mapOf(
                PropertyKey("fontSize") to Value.Sp(28f),
                PropertyKey("color") to Value.Color(ColorArgb.parse("#FF00695C")),
            ),
        ),
        shapes = mapOf(ShapeRole("pill") to mapOf(PropertyKey("corner") to Value.Dp(99f))),
        dimensions = mapOf(TokenName("md.dimension.gutter") to Value.Dp(16f)),
        custom = mapOf(TokenName("brand.accent") to Value.Color(ColorArgb.parse("#FFFFB300"))),
    )

    private fun textOf(value: EmittedLiteral?): String? = value?.text

    @Test
    fun `a material colour token is the member off the colour scheme`() {
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Color, "md.color.primary"),
            ResolvedToken(TokenKind.Color, "md.color.primary", TokenSource.Material),
            theme,
        )

        assertEquals("MaterialTheme.colorScheme.primary", textOf(emitted))
    }

    @Test
    fun `a material token with no theme still names the member`() {
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Typography, "md.typography.bodyLarge"),
            ResolvedToken(TokenKind.Typography, "md.typography.bodyLarge", TokenSource.Material),
            null,
        )

        assertEquals("MaterialTheme.typography.bodyLarge", textOf(emitted))
    }

    @Test
    fun `a material role Material does not publish refuses`() {
        val name = "md.typography.bodyEnormous"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Typography, name),
            ResolvedToken(TokenKind.Typography, name, TokenSource.Material),
            theme,
        )

        assertNull(emitted)
    }

    @Test
    fun `a material dimension token refuses, because Material publishes no scale`() {
        val name = "md.dimension.gutter"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Dimension, name),
            ResolvedToken(TokenKind.Dimension, name, TokenSource.Material),
            theme,
        )

        assertNull(emitted)
    }

    @Test
    fun `a token with no provenance refuses rather than being assumed material`() {
        assertNull(LiteralPrinter.emit(Value.Token(TokenKind.Color, "md.color.primary")))
    }

    @Test
    fun `a document colour token inlines the colour the theme wrote`() {
        val name = "brand.accent"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Color, name),
            ResolvedToken(TokenKind.Color, name, TokenSource.Custom),
            theme,
        )

        assertEquals("Color(0xFFFFB300)", textOf(emitted))
    }

    @Test
    fun `a dimension token resolves to the value the theme's scale declares`() {
        val name = "md.dimension.gutter"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Dimension, name),
            ResolvedToken(TokenKind.Dimension, name, TokenSource.Custom),
            theme,
        )

        assertEquals("16.0.dp", textOf(emitted))
    }

    @Test
    fun `a document typography token is written as the TextStyle it describes`() {
        val name = "caption"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Typography, name),
            ResolvedToken(TokenKind.Typography, name, TokenSource.Custom),
            theme,
        )

        // Sorted by key, so two runs over one document write the same text.
        assertEquals("TextStyle(color = Color(0xFF00695C), fontSize = 28.0.sp)", textOf(emitted))
    }

    @Test
    fun `a text style key this engine does not spell refuses the whole style`() {
        val odd = theme.copy(
            typography = mapOf(TextRole("caption") to mapOf(PropertyKey("baselineShift") to Value.Sp(1f))),
        )
        val name = "caption"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Typography, name),
            ResolvedToken(TokenKind.Typography, name, TokenSource.Custom),
            odd,
        )

        assertNull(emitted)
    }

    @Test
    fun `a colour role refuses, because a light and dark pair has no single literal`() {
        val name = "brand"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Color, name),
            ResolvedToken(TokenKind.Color, name, TokenSource.Custom),
            theme,
        )

        assertNull(emitted)
    }

    @Test
    fun `a brand value of the wrong kind refuses rather than being inlined anyway`() {
        val wrong = theme.copy(custom = mapOf(TokenName("brand.accent") to Value.Str("teal")))
        val name = "brand.accent"
        val emitted = LiteralPrinter.emit(
            Value.Token(TokenKind.Color, name),
            ResolvedToken(TokenKind.Color, name, TokenSource.Custom),
            wrong,
        )

        assertNull(emitted)
    }
}
