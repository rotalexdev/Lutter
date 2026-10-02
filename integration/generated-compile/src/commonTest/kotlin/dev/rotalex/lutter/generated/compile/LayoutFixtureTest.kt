package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.LayoutScopes
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each layout fixture proves that comparing two rendered trees cannot.
 *
 * Two halves agree pixel for pixel whether or not either honours `layout.weight`, so agreement
 * alone does not prove a scope-gated modifier was applied. Each fixture is therefore pinned on
 * the two facts that would have to change for it to stop proving anything: the scopes analysis
 * computed for it, and the modifier the generator wrote for it.
 */
class LayoutFixtureTest {

    @Test
    fun `row_weight carries the row scope onto both children and emits the weight`() {
        val (resolved, screen) = proofOf("row_weight")

        assertEquals(setOf(LayoutScopes.Row), scopesOf(resolved, "n_a"))
        assertEquals(setOf(LayoutScopes.Row), scopesOf(resolved, "n_b"))
        assertEquals(emptySet(), scopesOf(resolved, "n_root"), "the row provides the scope, it has none")
        assertTrue(screen.contains(".weight("), "no weight in the generated screen:\n$screen")
    }

    @Test
    fun `box_align carries the box scope and emits the absolute alignment entry`() {
        val (resolved, screen) = proofOf("box_align")

        // The scope reaches the child that never asks for it too, which is what makes it the
        // box's to provide rather than something `layout.align` brings with it.
        assertEquals(setOf(LayoutScopes.Box), scopesOf(resolved, "n_a"))
        assertEquals(setOf(LayoutScopes.Box), scopesOf(resolved, "n_b"))
        assertEquals(emptySet(), scopesOf(resolved, "n_root"), "the box provides the scope, it has none")
        assertTrue(screen.contains(".align("), "no align in the generated screen:\n$screen")
        // The entry names a side, so it resolves on `AbsoluteAlignment`; a bare `BottomRight`
        // would be the vocabulary that does not exist.
        assertTrue(screen.contains("AbsoluteAlignment.BottomRight"), "no enum symbol in:\n$screen")
    }

    @Test
    fun `spacer_size emits the two sizing modifiers it declares`() {
        val (resolved, screen) = proofOf("spacer_size")

        assertEquals(
            listOf("layout.height", "layout.width"),
            assertNotNull(resolved.nodes[NodeId("n_gap")], "no spacer node").modifiers.map { it.type.value },
        )
        assertTrue(screen.contains(".height("), "no height in the generated screen:\n$screen")
        assertTrue(screen.contains(".width("), "no width in the generated screen:\n$screen")
    }

    @Test
    fun `a weight outside a row is refused rather than generated`() {
        val result = analyze(WEIGHT_OUTSIDE_ROW)

        // The whole finding, not silence: the code says which gate refused and the message
        // names the scope the modifier asked for.
        assertEquals(
            listOf("modifier.scope_missing"),
            result.diagnostics.map { it.code.value },
        )
        assertEquals(Severity.Error, result.diagnostics.single().severity)
        assertEquals(
            "Modifier 'layout.weight' needs 'compose.RowScope' (node 'n_a')",
            result.diagnostics.single().message,
        )
        assertNull(result.resolved, "a refused document resolves nothing")
    }

    /** A fixture resolved and generated: the resolved tree, then the text of its screen. */
    private fun proofOf(id: String): Pair<ResolvedDocument, String> {
        val schema = ConformanceHarness.schema()
        val result = ConformanceHarness.analyze(schema, id)
        assertTrue(result.diagnostics.isEmpty(), "fixture '$id': ${result.diagnostics}")
        val resolved = assertNotNull(result.resolved, "fixture '$id' resolved nothing")
        val generated = KotlinGenerator(schema, CodegenOptions(PACKAGE + id))
            .generate(resolved, result.diagnostics)
        assertTrue(
            generated.diagnostics.none { it.severity == Severity.Error },
            "fixture '$id': ${generated.diagnostics}",
        )
        val screen = generated.files.files.firstOrNull { it.path.endsWith("HomeScreen.kt") }
        return resolved to assertNotNull(screen, "fixture '$id' emitted no screen").content
    }

    private fun analyze(json: String): AnalysisResult {
        val document: UiDocument = JsonDocumentCodec.decode(json.encodeToByteArray()).document
        return Analyzer(ConformanceHarness.schema()).analyze(document)
    }

    private fun scopesOf(resolved: ResolvedDocument, id: String): Set<ScopeId> =
        assertNotNull(resolved.nodes[NodeId(id)], "no node '$id'").scopes

    private companion object {
        const val PACKAGE: String = "dev.rotalex.lutter.generated."

        /**
         * `layout.weight` on a `core.Box` child: a scope-gated modifier outside its scope.
         *
         * A constant rather than a `fixtures/` file, because `generateFixtures` runs the CLI
         * over that directory and the CLI exits non-zero on an analysis error. A document that
         * is *meant* to be refused cannot live where generation reads.
         */
        val WEIGHT_OUTSIDE_ROW: String =
            """
            {
              "format": "forge.ui-document",
              "formatVersion": 1,
              "schemaVersion": 1,
              "payload": {
                "app": {"packageName": "com.example.conformance", "startPage": "p_home"},
                "components": {},
                "meta": {"name": "WeightOutsideRow"},
                "nodes": {
                  "n_a": {
                    "id": "n_a",
                    "modifiers": [
                      {
                        "type": "layout.weight",
                        "args": {"weight": {"type": "const", "value": {"type": "f32", "v": 1}}}
                      }
                    ],
                    "props": {"text": {"type": "const", "value": {"type": "str", "v": "Weighted"}}},
                    "type": "m3.Text"
                  },
                  "n_root": {
                    "id": "n_root",
                    "slots": {"children": ["n_a"]},
                    "type": "core.Box"
                  }
                },
                "pages": {
                  "p_home": {"id": "p_home", "name": "Home", "root": "n_root", "route": "home"}
                }
              }
            }
            """.trimIndent()
    }
}
