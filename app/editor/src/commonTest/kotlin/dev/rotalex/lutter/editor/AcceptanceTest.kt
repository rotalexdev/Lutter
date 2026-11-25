package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.SchemaRevision
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 17's acceptance (S4): a document authored in the editor renders and generates
 * identically to its JSON form.
 *
 * ### What "authored in the editor" means here
 *
 * The document is not built by the test. It starts from a hand-built baseline and every change
 * is a `dispatch` through [EditorSession]: one insertion through a `dropTargets` answer and two
 * property commits through `setProperty`. That is §25.1's requirement — the editor never
 * constructs a document, it asks the controller for one.
 *
 * ### What "identically" means
 *
 * Three comparisons, because a document is three things at once:
 *
 * 1. **The bytes**: encoding the authored document and encoding the document decoded from those
 *    bytes produces the same array. The codec is canonical, so this is the strongest statement
 *    available about the JSON form.
 * 2. **The render**: `Analyzer`'s resolved document is what the runtime draws, so equal resolved
 *    documents are equal pictures.
 * 3. **The generation**: `KotlinGenerator`'s files, compared path by path and byte by byte. A
 *    document that renders the same and generates different code would be two documents.
 *
 * ### Why the builtin schema
 *
 * The third claim is generation, and generation is a per-component binding: the synthetic
 * fixture schema the rest of this module uses carries none, so `KotlinGenerator` refuses its
 * types by design. The builtins are the schema the shipped applications use, which makes the
 * claim about the editor rather than about a test double. The edge is test scope and recorded
 * in `ModuleGraphRules.ALLOWED_IN_TESTS`.
 *
 * ### Why the revision is normalised away
 *
 * `ResolvedDocument.schemaRevision` is an opaque identity minted per schema context, not a fact
 * about the document: two analyzers over equal schemas mint different ids. Every other field of
 * the resolved tree is the content, and the content is what has to match.
 */
class AcceptanceTest {

    private val codec = JsonDocumentCodec
    private val schema = BuiltinEngine.schema()

    @Test
    fun a_document_authored_through_the_session_encodes_to_its_own_decoded_form() {
        val authored = authorThroughTheSession()

        val bytes: ByteArray = codec.encode(authored)
        val decoded: UiDocument = codec.decode(bytes).document

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

        val analyzer = Analyzer(schema)
        val authoredResult = analyzer.analyze(authored)
        val decodedResult = analyzer.analyze(decoded)

        assertFalse(authoredResult.hasErrors, "the authored document must resolve: ${authoredResult.diagnostics}")
        val authoredResolved = assertNotNull(authoredResult.resolved)
        val decodedResolved = assertNotNull(decodedResult.resolved)

        assertEquals(
            authoredResolved.copy(schemaRevision = SchemaRevision.NONE),
            decodedResolved.copy(schemaRevision = SchemaRevision.NONE),
        )
        assertEquals(authoredResult.diagnostics, decodedResult.diagnostics)
    }

    @Test
    fun an_authored_document_generates_the_same_files_as_its_json_form() {
        val authored = authorThroughTheSession()
        val decoded = codec.decode(codec.encode(authored)).document

        val authoredFiles: GeneratedFiles = generate(authored)
        val decodedFiles: GeneratedFiles = generate(decoded)

        assertTrue(authoredFiles.files.isNotEmpty(), "generation produced nothing, so nothing was compared")
        assertEquals(authoredFiles, decodedFiles)
    }

    /**
     * The document every assertion compares, authored the way a person authors one.
     *
     * A `dropTargets` answer is the palette's own answer, `insert` is what a drag lands as, and
     * `setProperty` is what the inspector's field commits. Both commits are asserted applied: a
     * refused write would leave the document unchanged and make the three comparisons pass over
     * a document nothing edited.
     */
    private fun authorThroughTheSession(): UiDocument {
        val session = EditorSession.open(baseline(), schema)
        val before: Set<NodeId> = session.document.nodes.ids().toSet()

        val target = session.dropTargets(TEXT_TYPE).first()
        session.insert(TEXT_TYPE, target)
        val inserted: NodeId = session.document.nodes.ids().first { it !in before }

        assertIs<PropertyWrite.Applied>(
            session.setProperty(inserted, TEXT_KEY, "\"Authored\""),
            "the inserted node's text must be accepted",
        )
        assertIs<PropertyWrite.Applied>(
            session.setProperty(GREETING, TEXT_KEY, "\"Renamed\""),
            "the baseline node's text must be accepted",
        )
        return session.document
    }

    private fun generate(document: UiDocument): GeneratedFiles {
        val resolved = assertNotNull(Analyzer(schema).analyze(document).resolved)
        return KotlinGenerator(schema, CodegenOptions("com.example.acceptance"))
            .generate(resolved)
            .files
    }

    /**
     * The baseline: one page whose root is a column holding one text.
     *
     * Hand-built for the reason the module's other fixtures are: a generated document would make
     * each assertion a statement about the generator. The types are the shipped ones, so the
     * generator has bindings for both, and the text property is required, so the only edit that
     * changes content is the one the test makes.
     */
    private fun baseline(): UiDocument {
        val nodes: NodeTable = NodeTable.EMPTY
            .with(Node(id = ROOT, type = COLUMN, slots = mapOf(CHILDREN to listOf(GREETING))))
            .with(
                Node(
                    id = GREETING,
                    type = TEXT_TYPE,
                    props = mapOf(TEXT_KEY to PropertyValue.Const(Value.Str("Hello"))),
                ),
            )
        return UiDocument(
            meta = DocumentMeta("Acceptance"),
            app = AppSpec("com.example.acceptance", HOME),
            pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
            components = emptyMap(),
            nodes = nodes,
        )
    }

    private companion object {
        val COLUMN: ComponentType = ComponentType("core.Column")
        val TEXT_TYPE: ComponentType = ComponentType("m3.Text")
        val CHILDREN: SlotName = SlotName("children")
        val TEXT_KEY: PropertyKey = PropertyKey("text")
        val HOME: PageId = PageId("p_home")
        val ROOT: NodeId = NodeId("n_root")
        val GREETING: NodeId = NodeId("n_greeting")
    }
}
