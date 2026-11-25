package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Code screen's value half: a document becomes files, and the files are the generator's.
 *
 * What a reader of that screen relies on is not "some Kotlin appears" but "the Kotlin is what
 * `:engine:codegen` emits for this document". So the test drives the whole chain — a palette
 * insert, the analyzer, the generator — and reads the emitted bytes. A composable that formatted
 * its own summary would pass a screenshot test and fail this one.
 *
 * It runs on the builtin schema because the generator is driven by per-component `CodegenBinding`
 * data, and the synthetic fixture schema deliberately has none.
 */
class GeneratedCodeTest {

    private val schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
        BuiltinEngine.schema()

    private fun document(): UiDocument = UiDocument(
        meta = DocumentMeta("Codegen"),
        app = AppSpec(PACKAGE, HOME),
        pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = ROOT, type = ComponentType("core.Column"))),
    )

    private fun resolved(document: UiDocument): ResolvedDocument {
        val result = Analyzer(schema).analyze(document)
        return assertNotNull(result.resolved, "the fixture must analyze: ${result.diagnostics}")
    }

    @Test
    fun `the files are the generator's, under the document's own package`() {
        val view = generatedCode(schema, resolved(document()), PACKAGE)

        assertTrue(view.files.isNotEmpty(), "a column page still emits the app and the screen")
        assertTrue(
            view.files.any { it.content.contains("package $PACKAGE") },
            "no file declares the document's package: ${view.files.map { it.path }}",
        )
        assertEquals(
            view.files.map { it.path },
            view.files.map { it.path }.sorted(),
            "the listing keeps the generator's order, which is sorted by path",
        )
        assertTrue(view.diagnostics.isEmpty(), "a clean document generates without findings")
    }

    @Test
    fun `what the palette inserted is in the code`() {
        // The button and its label come from `insert`, not from the fixture: this is the assertion
        // that ties the two halves of "tap the palette, read the code" together.
        val session = EditorSession.open(document(), schema)
        session.insert(ComponentType("m3.Button"), DropTarget(parent = ROOT, slot = CHILDREN, index = 0))

        val view = generatedCode(schema, resolved(session.document), PACKAGE)

        val all: String = view.files.joinToString("\n") { it.content }
        assertContains(all, "Button(")
        assertContains(all, "\"Button\"")
    }

    @Test
    fun `the same document generates the same bytes twice`() {
        val first = generatedCode(schema, resolved(document()), PACKAGE)
        val second = generatedCode(schema, resolved(document()), PACKAGE)

        assertEquals(first.files, second.files, "the screen is a projection, not a source of drift")
    }

    @Test
    fun `a node the old palette left bare still generates`() {
        // Build 555's palette inserted a bare node: no props, no children. Those documents are on
        // real devices and open on every launch, so "the editor refuses to show code for a
        // document it used to produce" is a regression, not an edge case.
        //
        // A bare *container* is the reachable case: a bare `m3.Text` has a required `text` and
        // analysis refuses it before the generator ever sees it, which is a refusal the screen
        // already shows. The generator's own answer to an empty column is an empty column.
        val bare = NodeTable.EMPTY
            .with(
                Node(
                    id = ROOT,
                    type = ComponentType("core.Column"),
                    slots = mapOf(CHILDREN to listOf(BARE)),
                ),
            )
            .with(Node(id = BARE, type = ComponentType("core.Column")))
        val document = document().copy(nodes = bare)

        val view = generatedCode(schema, resolved(document), PACKAGE)

        assertNull(view.failure, "a bare container must not throw: ${view.failure}")
        assertTrue(view.files.isNotEmpty(), "the rest of the document still emits")
    }

    @Test
    fun `a generator bug is reported, not thrown`() {
        // `CodegenCoverage.check` throws for a component whose slot emits nothing. That is a bug in
        // a generator (or a schema), never something the author can fix, and the screen has to say
        // so instead of taking the composition down with it.
        val broken = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            component(
                componentSpec(ComponentType("test.Broken"), 1) {
                    metadata("Broken", Category.Basic)
                    slot(SlotSpec(CHILDREN, Cardinality.Many))
                    intrinsic()
                },
            )
        }
        val result = Analyzer(broken).analyze(
            UiDocument(
                meta = DocumentMeta("Broken"),
                app = AppSpec(PACKAGE, HOME),
                pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
                components = emptyMap(),
                nodes = NodeTable.EMPTY.with(Node(id = ROOT, type = ComponentType("test.Broken"))),
            ),
        )
        val resolved = assertNotNull(result.resolved, "the document itself is well formed: ${result.diagnostics}")

        val view = generatedCode(broken, resolved, PACKAGE)

        assertNotNull(view.failure, "the throw must arrive as a message")
        assertTrue(view.files.isEmpty(), "a failed generation shows no half-written files")
    }

    private companion object {
        const val PACKAGE: String = "com.example.codegen"
        val HOME: PageId = PageId("p_home")
        val ROOT: NodeId = NodeId("n_root")
        val BARE: NodeId = NodeId("n_bare")
        val CHILDREN: SlotName = SlotName("children")
    }
}
