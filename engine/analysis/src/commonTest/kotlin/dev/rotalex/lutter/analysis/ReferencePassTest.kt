package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.doc.DataModelDecl
import dev.rotalex.lutter.model.doc.ThemeDecl
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pass 4: dangling references, kind mismatches and unknown tokens fail visibly. */
class ReferencePassTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val themeId: ThemeId = ThemeId("t")

    private fun codesOf(block: PageScope.() -> Unit): List<String> =
        analyzer.analyze(homeDocument(block)).diagnostics.map { it.code.value }

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
    fun `token with no theme reports token unknown`() {
        val codes = codesOf {
            node(TextType) {
                prop("text", Value.Str("Hi"))
                prop("style", Value.Token(TokenKind.Typography, "headline"))
            }
        }

        assertTrue(codes.contains(DiagnosticCodes.TokenUnknown.value), "got $codes")
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
}
