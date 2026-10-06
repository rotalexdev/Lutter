package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.TokenSource
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pass 4: dangling references, kind mismatches and unknown tokens fail visibly. */
class ReferencePassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val themeId: ThemeId = ThemeId("t")
    private val pageId: PageId = PageId("p_home")

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block = block)).diagnostics.map { it.code.value }

    /** One `Text` styled by [name], so each token case differs from the next in the name alone. */
    private fun styledBy(name: String): UiDocument = homeDocument {
        node(TextType) {
            prop("text", Value.Str("Hi"))
            prop("style", Value.Token(TokenKind.Typography, name))
        }
    }

    /** What `resolveToken` decided for `style`; null when pass 4 rejected the document. */
    private fun styleSourceOf(result: AnalysisResult): TokenSource? =
        result.resolved?.pages?.get(pageId)?.root?.props?.get(PropertyKey("style"))?.token?.source

    @Test
    fun `reference to an absent page reports dangling`() {
        val codes = codesOf {
            node(LinkType) { prop("target", Value.Ref(RefKind.Page, "p_nope")) }
        }

        assertTrue(codes.contains(DiagnosticCodes.RefDangling.value), "got $codes")
    }

    @Test
    fun `reference of the wrong kind reports kind mismatch`() {
        val codes = codesOf {
            node(LinkType) { prop("target", Value.Ref(RefKind.Resource, "r_any")) }
        }

        assertTrue(codes.contains(DiagnosticCodes.RefKindMismatch.value), "got $codes")
    }

    @Test
    fun `reference to an absent resource reports resource unknown`() {
        val codes = codesOf {
            node(LinkType) { prop("icon", Value.Ref(RefKind.Resource, "r_nope")) }
        }

        assertTrue(codes.contains(DiagnosticCodes.ResourceUnknown.value), "got $codes")
    }

    @Test
    fun `object of an undeclared model reports dangling`() {
        val codes = codesOf {
            node(LinkType) {
                prop("payload", Value.Obj(TypeId("User"), emptyMap()))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.RefDangling.value), "got $codes")
    }

    @Test
    fun `material token with no theme reports nothing`() {
        val result = analyzer.analyze(styledBy("md.typography.bodyLarge"))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
    }

    @Test
    fun `non-material token with no theme reports token unknown`() {
        val result = analyzer.analyze(styledBy("brand.accent"))

        val unknown = result.diagnostics.single { it.code == DiagnosticCodes.TokenUnknown }
        assertEquals("Token 'brand.accent' is not declared by the selected theme", unknown.message)
    }

    @Test
    fun `the pass and the resolver agree on which tokens exist`() {
        val material = analyzer.analyze(styledBy("md.typography.bodyLarge"))
        val brand = analyzer.analyze(styledBy("brand.accent"))

        // Accepted, so a source exists — and `md.*` with no theme is Material's own defaults.
        assertTrue(material.diagnostics.isEmpty(), "got ${material.diagnostics}")
        assertEquals(TokenSource.Material, assertNotNull(styleSourceOf(material)))

        // Rejected, so no source exists at all: `resolveToken` is never asked and cannot disagree.
        assertNull(styleSourceOf(brand))
    }

    @Test
    fun `token absent from the theme reports token unknown`() {
        val document = homeDocument {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("style", Value.Token(TokenKind.Typography, "nope"))
            }
        }.copy(
            themes = mapOf(themeId to ThemeDecl(themeId, "App", typography = mapOf(TextRole("headline") to emptyMap()))),
            theme = themeId,
        )

        val codes = analyzer.analyze(document).diagnostics.map { it.code.value }

        assertTrue(codes.contains(DiagnosticCodes.TokenUnknown.value), "got $codes")
    }

    @Test
    fun `live page and model targets report nothing`() {
        val document = homeDocument {
            node(LinkType) {
                prop("target", Value.Ref(RefKind.Page, "p_home"))
                prop("payload", Value.Obj(TypeId("User"), emptyMap()))
            }
        }.copy(dataModels = mapOf(DataModelId("User") to DataModelDecl(DataModelId("User"), "User")))

        val result = analyzer.analyze(document)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
    }

    @Test
    fun `token declared by the theme reports nothing`() {
        val document = homeDocument {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("style", Value.Token(TokenKind.Typography, "headline"))
            }
        }.copy(
            themes = mapOf(themeId to ThemeDecl(themeId, "App", typography = mapOf(TextRole("headline") to emptyMap()))),
            theme = themeId,
        )

        val result = analyzer.analyze(document)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
    }

    /** The boundary: a selected theme is asked about `md.*` exactly as it is about any other name. */
    @Test
    fun `material token declared by the theme reports nothing`() {
        val document = styledBy("md.typography.bodyLarge").copy(
            themes = mapOf(
                themeId to ThemeDecl(
                    themeId,
                    "App",
                    custom = mapOf(TokenName("md.typography.bodyLarge") to Value.Sp(20f)),
                ),
            ),
            theme = themeId,
        )

        val result = analyzer.analyze(document)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
    }

    @Test
    fun `material token absent from the theme reports token unknown`() {
        val document = styledBy("md.typography.bodyLarge").copy(
            themes = mapOf(
                themeId to ThemeDecl(
                    themeId,
                    "App",
                    custom = mapOf(TokenName("md.typography.labelLarge") to Value.Sp(20f)),
                ),
            ),
            theme = themeId,
        )

        val result = analyzer.analyze(document)

        val unknown = result.diagnostics.single { it.code == DiagnosticCodes.TokenUnknown }
        assertEquals("Token 'md.typography.bodyLarge' is not declared by the selected theme", unknown.message)
    }

    @Test
    fun `the only theme is selected without naming it`() {
        // A custom name, so silence proves the theme was selected rather than `md.*` answering.
        val document = styledBy("brand.accent").copy(
            themes = mapOf(
                themeId to ThemeDecl(
                    themeId,
                    "App",
                    custom = mapOf(TokenName("brand.accent") to Value.Dp(16f)),
                ),
            ),
        )

        val result = analyzer.analyze(document)

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertEquals(TokenSource.Custom, assertNotNull(styleSourceOf(result)))
    }
}
