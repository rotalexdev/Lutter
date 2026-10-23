package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.analysis.resolved.ResolvedToken
import dev.rotalex.lutter.analysis.resolved.TokenSource
import dev.rotalex.lutter.model.doc.ColorRole
import dev.rotalex.lutter.model.doc.ColorSpec
import dev.rotalex.lutter.model.doc.ShapeRole
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.FunctionSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.ModifierSpec
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The value-kind refusal class, pinned at the generator: `codegen.strategy_unsupported` with
 * the "Value kind emits nothing yet" wording fired where `LiteralPrinter` handed `valueOf` a
 * null. Every failing fuzz seed under that class carries a typography token on a
 * `style`-shaped property, and the diagnostic named one of two readings: a custom token whose
 * typography keys the runtime does not spell, or a token whose name the typography map does
 * not answer at all.
 *
 * The runtime answers every one of those with a `TextStyle` — the compose runtime's
 * `DefaultThemeHandle.textStyle` walks them in a fixed order, `textStyleOf` first and
 * `materialRoleOf` after it — and this suite is that order spelled at the generated screen:
 *
 *  * a role the typography map declares whose keys all fall inside `textStyleOf`'s four-key
 *    closure — `fontSize`, `lineHeight`, `letterSpacing`, `color` — is the `TextStyle(...)`
 *    literal of what `textStyleOf` would draw. That row is what the pre-fix printer already
 *    wrote, so the test pinning it is expected to pass before and after the fix; it is here
 *    so the fix cannot unwrite it.
 *  * every other typography reading is `TextStyle.Default`, because that is literally the
 *    value the runtime returns: a key outside the four (or one of the four whose value is not
 *    the kind it names), a name the theme declares but not as a typography role (the
 *    fallthrough matches nothing `md.`-shaped), a Material name whose second segment is not
 *    `typography`, and a Material role Material does not publish.
 *
 * A colour or shape token keeps its refusal, and the refusal names the design boundary
 * precisely: the theme handle answers text styles only, a colour role is a light/dark pair
 * and nothing decides which half draws, and a shape role is an open map with no agreed key
 * set — so there is no runtime drawing to mirror, and emitting one would be a guess.
 *
 * The schema is hand-built and the documents are handed to the generator resolved, the way
 * `CodegenTest` builds its token cases: no analyzer, no generator fixtures. `ReferencePass`
 * accepts a token by *name* across all five theme maps (§14.2's `themeKnows`), so a
 * typography-kind property may lawfully carry a name the theme declared under `colors` — that
 * shape is reachable in the fuzz batch and is what the map-miss case here tests. A colour
 * token reaches the printer only through a schema a plugin could declare, which is why the
 * boundary cases get a spec of their own.
 */
class CodegenCustomTokenParityTest {

    private val styledType: ComponentType = ComponentType("test.Styled")
    private val tintedType: ComponentType = ComponentType("test.Tinted")
    private val textKey: PropertyKey = PropertyKey("text")
    private val styleKey: PropertyKey = PropertyKey("style")
    private val accentKey: PropertyKey = PropertyKey("accent")

    /**
     * The theme the fuzz generator draws, in kind: one role per map, the typography role carrying
     * one key that is inside the four-key closure (`fontSize`) and one outside it (`label`), which
     * is exactly the shape the batch writes and the pre-fix printer refuses. `brand.title` is the
     * in-closure counterpart, all three keys declared.
     */
    private val parityTheme: ThemeDecl = ThemeDecl(
        id = ThemeId("t_parity"),
        name = "Parity",
        colors = mapOf(ColorRole("gen.primary") to ColorSpec(ColorArgb.parse("#FF00695C"))),
        typography = mapOf(
            TextRole("gen.title") to mapOf(
                PropertyKey("size") to Value.Sp(14f),
                PropertyKey("label") to Value.Str("gen"),
            ),
            TextRole("brand.title") to mapOf(
                PropertyKey("fontSize") to Value.Sp(16f),
                PropertyKey("lineHeight") to Value.Sp(24f),
                PropertyKey("color") to Value.Color(ColorArgb.parse("#FF37474F")),
            ),
        ),
        shapes = mapOf(ShapeRole("gen.round") to mapOf(PropertyKey("radius") to Value.Dp(99f))),
        dimensions = mapOf(TokenName("gen.gap") to Value.Dp(8f)),
        custom = mapOf(TokenName("gen.accent") to Value.Str("gen")),
    )

    private val resolvedTheme: ResolvedTheme = ResolvedTheme(parityTheme)

    private val paritySchema: Schema<ComponentSpec, ModifierSpec, String, FunctionSpec, String> =
        Schema.build {
            component(styledStub())
            component(tintedStub())
        }

    // Two `Text`-shaped specs, transcribed from `TextSpec`: `style` is the one property the
    // engine types as a typography token, and `tinted`'s `accent` is how a colour token reaches
    // the printer at all, because no built-in property declares one. The color kind is the
    // boundary half of this suite, which is why it gets its own spec.
    private fun styledStub(): ComponentSpec {
        val text = prop<String>("text", TypeRef.Str, required = true)
        val style = prop<TokenName?>("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography)))
        return componentSpec(styledType, 1) {
            metadata("Styled", Category.Basic)
            property(text)
            property(style)
            composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
                param("text", from = text, positional = Positional.WhenSole)
                param("style", from = style)
            }
        }
    }

    private fun tintedStub(): ComponentSpec {
        val label = prop<String>("text", TypeRef.Str, required = true)
        val accent = prop<TokenName?>("accent", TypeRef.Nullable(TypeRef.Token(TokenKind.Color)))
        return componentSpec(tintedType, 1) {
            metadata("Tinted", Category.Basic)
            property(label)
            property(accent)
            composeCall(KotlinSymbol("com.example.ui", "Panel")) {
                param("text", from = label, positional = Positional.WhenSole)
                param("accent", from = accent)
            }
        }
    }

    @Test
    fun `a role inside the four-key closure spells the TextStyle the runtime draws`() {
        val result = generate(
            styledNode("brand.title", custom(TokenKind.Typography, "brand.title")),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        val screen = screenOf(result)
        // Sorted by key, and the row `textStyleOf` reconstructs from the same numbers: a colour,
        // a size and a line height, the three the role wrote down with the fourth left out.
        assertTrue(
            screen.contains(
                "style = TextStyle(color = Color(0xFF37474F), fontSize = 16.0.sp, " +
                    "lineHeight = 24.0.sp),",
            ),
            screen,
        )
        assertTrue(screen.contains("import androidx.compose.ui.text.TextStyle"), screen)
        assertTrue(screen.contains("import androidx.compose.ui.unit.sp"), screen)
    }

    @Test
    fun `a role with a key outside the four-key closure draws the default`() {
        // `gen.title` is the fuzz theme's own role: `size` and `label` are keys the runtime's
        // `textStyleOf` does not spell, so the runtime draws `TextStyle.Default` and the
        // generated file names that same value.
        val result = generate(styledNode("gen.title", custom(TokenKind.Typography, "gen.title")))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertTrue(screenOf(result).contains("style = TextStyle.Default,"), screenOf(result))
    }

    @Test
    fun `a custom name that is not a typography role falls through to the default`() {
        // The effective kind of the `style` parameter is typography, and the theme declares
        // `gen.primary` under `colors` — a token usage that is legal and that the map lookup
        // misses. The runtime takes `materialRoleOf` next, matches nothing
        // `md.`-shaped, and answers the plain default; generated code spells the same answer.
        val result = generate(styledNode("gen.primary", custom(TokenKind.Typography, "gen.primary")))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertTrue(screenOf(result).contains("style = TextStyle.Default,"), screenOf(result))
    }

    @Test
    fun `a material name whose segment is not typography draws the default`() {
        // §14.2's other vocabulary: with no theme selected, any `md.*` name is legal, and the
        // `style` property lawfully carries `md.color.primary` because a token's kind comes from
        // the property, never from the name. The runtime looks for a typography role, refuses the
        // `color` segment, and answers `TextStyle.Default`.
        val result = generate(
            styledNode("md.color.primary", material(TokenKind.Typography, "md.color.primary")),
            ResolvedTheme(null),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertTrue(screenOf(result).contains("style = TextStyle.Default,"), screenOf(result))
    }

    @Test
    fun `a material role material does not publish draws the default`() {
        val result = generate(
            styledNode("md.typography.bodyEnormous", material(TokenKind.Typography, "md.typography.bodyEnormous")),
            ResolvedTheme(null),
        )

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertTrue(screenOf(result).contains("style = TextStyle.Default,"), screenOf(result))
    }

    @Test
    fun `a colour token keeps its refusal, and the refusal names the design boundary`() {
        val result = generate(
            tintedNode("gen.primary", custom(TokenKind.Color, "gen.primary")),
        )

        // §16.7's refusal row: one error empties the result, so the boundary is guarded whole.
        assertTrue(result.files.files.isEmpty())
        val diagnostic: Diagnostic = result.diagnostics.single()
        assertEquals(DiagnosticCodes.CodegenStrategyUnsupported, diagnostic.code)
        assertEquals(
            "Token 'gen.primary' has no runtime drawing to mirror: no ThemeHandle answers " +
                "colour tokens, and a colour role is a light/dark pair codegen cannot see " +
                "which half draws at 'n_1'",
            diagnostic.message,
        )
    }

    @Test
    fun `a shape token keeps its refusal, and the refusal names the design boundary`() {
        val result = generate(tintedNode("gen.round", custom(TokenKind.Shape, "gen.round")))

        assertTrue(result.files.files.isEmpty())
        val diagnostic: Diagnostic = result.diagnostics.single()
        assertEquals(DiagnosticCodes.CodegenStrategyUnsupported, diagnostic.code)
        assertEquals(
            "Token 'gen.round' has no runtime drawing to mirror: no ThemeHandle answers shape " +
                "tokens, and a shape role has no agreed key set at 'n_1'",
            diagnostic.message,
        )
    }

    // -------------------------------------------------------------------------------------
    // The hand-built pipeline: one root node per page, the way `CodegenTest` builds token cases.
    // -------------------------------------------------------------------------------------

    private fun screenOf(result: CodegenResult): String =
        result.files.files.single { it.path == "screens/HomeScreen.kt" }.content

    private fun styledNode(name: String, token: ResolvedToken): ResolvedNode =
        tokenNode(styledType, styleKey, token.kind, name, token)

    private fun tintedNode(name: String, token: ResolvedToken): ResolvedNode =
        tokenNode(tintedType, accentKey, token.kind, name, token)

    /**
     * A root node naming [name] as a token constant, its kind the property's declared one and
     * its provenance [token] exactly as pass 7 would have attached it.
     */
    private fun tokenNode(
        type: ComponentType,
        key: PropertyKey,
        kindFor: TokenKind,
        name: String,
        token: ResolvedToken,
    ): ResolvedNode = ResolvedNode(
        NodeId("n_1"),
        type,
        mapOf(
            textKey to ResolvedProp(textKey, PropertyValue.Const(Value.Str("Hi")), PropOrigin.Specified, null),
            key to ResolvedProp(
                key,
                PropertyValue.Const(Value.Token(kindFor, name)),
                PropOrigin.Specified,
                token,
            ),
        ),
        emptyList(),
        emptyMap(),
        emptySet(),
    )

    private fun custom(kind: TokenKind, name: String): ResolvedToken =
        ResolvedToken(kind, name, TokenSource.Custom)

    private fun material(kind: TokenKind, name: String): ResolvedToken =
        ResolvedToken(kind, name, TokenSource.Material)

    private fun generate(root: ResolvedNode, theme: ResolvedTheme = resolvedTheme): CodegenResult =
        KotlinGenerator(paritySchema, CodegenOptions("com.example.parity"))
            .generate(homeDocument(root, theme))

    private fun homeDocument(root: ResolvedNode, theme: ResolvedTheme): ResolvedDocument {
        val pages: Map<PageId, ResolvedPage> = mapOf(
            PageId("p_home") to ResolvedPage(PageId("p_home"), "Home", "home", root, emptyList()),
        )
        val index: Map<NodeId, ResolvedNode> = mapOf(root.id to root)
        return ResolvedDocument(
            pages,
            emptyMap(),
            index,
            theme,
        )
    }
}
