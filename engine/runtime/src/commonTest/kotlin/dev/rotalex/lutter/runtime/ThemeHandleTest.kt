package dev.rotalex.lutter.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.TextStyleSpec
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * §14.1's typography role becoming a real `TextStyle`.
 *
 * The composable half — `MaterialTheme.typography` — needs a composition, and no engine module
 * here has a harness for one; the conformance fixture in `:integration:generated-compile` is what
 * proves the two backends agree about it. What is under this test is the part that fixture
 * cannot see: a role's own map read, a key spelled, and a key this engine cannot spell refusing
 * instead of drawing a default the document never asked for.
 *
 * The refusal is half the contract. Codegen refuses the same role, so a document whose theme
 * declares an unspellable key produces no generated screen at all — which is why the two halves
 * cannot be seen to disagree about it.
 */
class ThemeHandleTest {

    private val fontSize: PropertyKey = PropertyKey("fontSize")
    private val lineHeight: PropertyKey = PropertyKey("lineHeight")
    private val letterSpacing: PropertyKey = PropertyKey("letterSpacing")
    private val color: PropertyKey = PropertyKey("color")
    private val teal: Color = ColorArgb.parse("#FF00695C").toCompose()

    @Test
    fun `a role carrying a size becomes a real TextStyle`() {
        val style: TextStyle = assertNotNull(textStyleOf(mapOf(fontSize to Value.Sp(28f))))

        assertEquals(28.sp, style.fontSize)
    }

    @Test
    fun `an absent key keeps the default, which is the argument codegen leaves out`() {
        val style: TextStyle = assertNotNull(textStyleOf(mapOf(fontSize to Value.Sp(28f))))

        assertEquals(TextUnit.Unspecified, style.lineHeight)
        assertEquals(TextUnit.Unspecified, style.letterSpacing)
        assertEquals(Color.Unspecified, style.color)
    }

    @Test
    fun `every key a role may carry is spelled`() {
        val style: TextStyle = assertNotNull(
            textStyleOf(
                mapOf(
                    fontSize to Value.Sp(12f),
                    lineHeight to Value.Sp(16f),
                    letterSpacing to Value.Sp(2f),
                    color to Value.Color(ColorArgb.parse("#FF00695C")),
                ),
            ),
        )

        assertEquals(12.sp, style.fontSize)
        assertEquals(16.sp, style.lineHeight)
        assertEquals(2.sp, style.letterSpacing)
        assertEquals(teal, style.color)
    }

    @Test
    fun `a key this engine does not spell refuses the whole role`() {
        assertNull(textStyleOf(mapOf(PropertyKey("baselineShift") to Value.Sp(1f))))
    }

    @Test
    fun `a key carrying the wrong kind refuses rather than being dropped`() {
        assertNull(textStyleOf(mapOf(fontSize to Value.Dp(28f))))
    }

    @Test
    fun `an empty role refuses, because there is no style to draw`() {
        assertNull(textStyleOf(emptyMap()))
    }

    @Test
    fun `a name the theme declares is the document's, and a name it does not is Material's`() {
        val brand: ThemeDecl = ThemeDecl(
            id = ThemeId("t_brand"),
            name = "Brand",
            typography = mapOf(TextRole("caption") to mapOf(fontSize to Value.Sp(28f))),
        )

        val declared: TextStyleSpec = assertNotNull(typographyRole(brand, TokenName("caption")))

        assertEquals(28.sp, assertNotNull(textStyleOf(declared)).fontSize)
        // The `md.*` arm, and the reason the role lookup returning null is the whole decision:
        // this is the name `materialTextStyle` is asked about, and a document selecting no theme
        // at all sends every one of its tokens down that same path.
        assertNull(typographyRole(brand, TokenName("md.typography.bodyLarge")))
        assertNull(typographyRole(null, TokenName("md.typography.bodyLarge")))
    }
}
