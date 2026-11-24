package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 17's acceptance (S4): a document authored in the editor renders and generates
 * identically to its JSON form.
 *
 * ### What "authored in the editor" means here
 *
 * The document is not built by hand. It starts from the fixture and every change is a
 * `dispatch` through [EditorSession] — one insertion through `dropTargets`, two property
 * commits through `setProperty`. That is the whole of §25.1's requirement: the editor never
 * constructs a document, it asks the controller for one.
 *
 * ### What "identically" means
 *
 * Three comparisons, because a document is three things at once:
 *
 * 1. **The bytes**: encoding the authored document and encoding the document decoded from those
 *    bytes must produce the same byte array. The codec is canonical, so this is the strongest
 *    statement available about the JSON form.
 * 2. **The render**: `Analyzer`'s `resolved` document is what the runtime draws. Both sides
 *    resolve to the same value, so no pixel can differ.
 * 3. **The generation**: `KotlinGenerator`'s files are compared path by path and byte by byte.
 *    A document that renders the same and generates different code would be two documents.
 *
 * ### Why the fixture schema and not the builtins
 *
 * Reaching `:engine:builtins-compose` from this module would be an edge with no row in
 * `ModuleGraphRules`. The synthetic schema generates real composables — the generator is the
 * same — so the claim is tested without widening the graph for a test.
 */
class AcceptanceTest {

    private val schema = editorSchema()
    private val codec = JsonDocumentCodec

    @Test
    fun a_document_authored_through_the_session_encodes_to_its_own_decoded_form() {
        val authored = authorThroughTheSession()

        val bytes = codec.encode(authored)
        val decoded = codec.decode(bytes).document

        assertEquals(
            bytes.toList(),
            codec.encode(decoded).toList(),
            "the codec must be canonical: encoding the decoded form reproduces the bytes",
        )
    }

    @Test
    fun an_authored_document_resolves_to_the_same_value_as_its_json_form() {
        val authored = authorThroughTheSession()
        val decoded = codec.decode(codec.encode(authored)).document

        val authoredResult: AnalysisResult = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(schema).analyze(authored)
        val decodedResult: AnalysisResult = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(schema).analyze(decoded)

        val authoredResolved = assertNotNull(authoredResult.resolved, "the authored document must resolve")
        val decodedResolved = assertNotNull(decodedResult.resolved, "its JSON form must resolve too")
        assertEquals(authoredResolved, decodedResolved)
        assertEquals(authoredResult.diagnostics, decodedResult.diagnostics)
    }

    @Test
    fun an_authored_document_generates_the_same_files_as_its_json_form() {
        val authored = authorThroughTheSession()
        val decoded = codec.decode(codec.encode(authored)).document

        val authoredFiles = generate(authored)
        val decodedFiles = generate(decoded)

        assertEquals(
            authoredFiles.map { it.path },
            decodedFiles.map { it.path },
            "the same document must name the same files",
        )
        authoredFiles.zip(decodedFiles).forEach { (a, b) ->
            assertEquals(a.content, b.content, "generated content differs for ${a.path}")
        }
    }

    /**
     * A document whose every change went through `dispatch`.
     *
     * The insertion goes through the same `dropTargets` answer the palette uses, and the two
     * commits go through the same `setProperty` the inspector uses — this is the editor's own
     * path, not a test-only shortcut.
     */
    private fun authorThroughTheSession(): UiDocument {
        val session = session()
        val before: Set<NodeId> = session.document.nodes.ids().toSet()

        val target = session.dropTargets(LABEL).first()
        session.insert(LABEL, target)
        val inserted: NodeId = session.document.nodes.ids().first { it !in before }

        session.setProperty(inserted, TEXT, "\"Authored\"")
        session.setProperty(LEAF, TEXT, "\"Renamed\"")
        return session.document
    }

    private fun generate(document: UiDocument): List<dev.rotalex.lutter.codegen.GeneratedFile> {
        val resolved = assertNotNull(
            Analyzer<ActionSpec, FunctionSpec, TypeSpec>(schema).analyze(document).resolved,
        )
        val result: CodegenResult =
            KotlinGenerator<ActionSpec, TypeSpec>(schema, CodegenOptions("com.example.editor"))
                .generate(resolved)
        assertTrue(result.diagnostics.isEmpty(), "the fixture must generate cleanly: ${result.diagnostics}")
        return result.files.files
    }
}
