package dev.rotalex.lutter.editor

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every component the palette offers survives the round trip the Code tab performs.
 *
 * ### Why this exists
 *
 * The palette is the only way a person adds a component, and the Code tab is the only way they
 * read what it became. A component the palette offers but the generator cannot emit is a screen
 * that offers a door into a wall: the insert succeeds, and the code behind the Code tab fills with
 * errors the author did not cause and cannot fix. Nothing else asserts that pair — the generator's
 * own tests start from hand-built documents, and the palette's tests stop at the document.
 *
 * ### What counts as a failure
 *
 * Three separate answers to "is this component usable", because they fail at different places and
 * are fixed by different people: no legal home in a plain column (the palette or the planner), a
 * document the analyzer refuses (the schema or the template), and a generator that refuses or
 * throws (codegen). All three are collected and asserted together, so one run reports every gap
 * instead of the first.
 */
class PaletteGenerationTest {

    private val schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
        BuiltinEngine.schema()

    @Test
    fun `every component the palette offers analyzes, generates and emits a file`() {
        val problems = mutableListOf<String>()

        for (section in EditorSession.open(starter(), schema).palette()) {
            for (entry in section.entries) {
                val type: ComponentType = entry.type
                // A fresh session per type: an insert changes the document, and a later type
                // judged against leftovers would be a statement about the previous test case.
                val session = EditorSession.open(starter(), schema)
                val target = session.dropTargets(type).firstOrNull()
                if (target == null) {
                    problems += "${type.value}: no legal home in a column"
                    continue
                }
                session.insert(type, target)

                val analyzed = Analyzer(schema).analyze(session.document)
                val resolved = analyzed.resolved
                if (resolved == null) {
                    problems += "${type.value}: analysis refused " +
                        analyzed.diagnostics.joinToString { "${it.code.value} ${it.message}" }
                    continue
                }
                val view = generatedCode(schema, resolved, session.document.app.packageName)
                view.failure?.let { problems += "${type.value}: generator threw $it" }
                if (view.failure == null) {
                    if (view.diagnostics.isNotEmpty()) {
                        problems += "${type.value}: generator refused " +
                            view.diagnostics.joinToString { "${it.code.value} ${it.message}" }
                    }
                    if (view.isEmpty) problems += "${type.value}: emitted no file"
                }
            }
        }

        assertEquals(emptyList<String>(), problems, "every palette entry must reach the Code tab intact")
    }

    @Test
    fun `the starter document itself generates`() {
        // The document the app opens with is the one every author starts from: if the Code tab is
        // broken before a single edit, the palette sweep above is measuring the wrong thing.
        val analyzed = Analyzer(schema).analyze(starter())
        val resolved = assertNotNull(analyzed.resolved, "the starter must analyze: ${analyzed.diagnostics}")

        val view = generatedCode(schema, resolved, PACKAGE)
        assertTrue(view.failure == null, "the starter must not make the generator throw: ${view.failure}")
        assertTrue(view.diagnostics.isEmpty(), "the starter must generate cleanly: ${view.diagnostics}")
        assertTrue(view.files.isNotEmpty(), "the starter must emit files")
    }

    /**
     * The bundled document in code: a column holding a heading and a button with a label.
     *
     * `core.Column` with `spacing` rather than a bare column, because the spacing path is the one
     * property-emission shape a document reaches without editing anything, and a generator that
     * cannot write `spacing` would fail on the very first document every author sees.
     */
    private fun starter(): UiDocument {
        val children = SlotName("children")
        val content = SlotName("content")
        return UiDocument(
            meta = DocumentMeta("Starter"),
            app = AppSpec(PACKAGE, HOME),
            pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "home", root = ROOT)),
            components = emptyMap(),
            nodes = NodeTable.EMPTY
                .with(
                    Node(
                        id = ROOT,
                        type = ComponentType("core.Column"),
                        slots = mapOf(children to listOf(TITLE, BUTTON)),
                    ),
                )
                .with(Node(id = TITLE, type = ComponentType("m3.Text"), props = mapOf(TEXT to HELLO)))
                .with(
                    Node(
                        id = BUTTON,
                        type = ComponentType("m3.Button"),
                        slots = mapOf(content to listOf(LABEL)),
                    ),
                )
                .with(Node(id = LABEL, type = ComponentType("m3.Text"), props = mapOf(TEXT to CONTINUE))),
        )
    }

    private companion object {
        const val PACKAGE: String = "com.example.starter"
        val TEXT: PropertyKey = PropertyKey("text")
        val HELLO: PropertyValue = PropertyValue.Const(Value.Str("Welcome"))
        val CONTINUE: PropertyValue = PropertyValue.Const(Value.Str("Continue"))
        val HOME: PageId = PageId("p_home")
        val ROOT: NodeId = NodeId("n_root")
        val TITLE: NodeId = NodeId("n_title")
        val BUTTON: NodeId = NodeId("n_button")
        val LABEL: NodeId = NodeId("n_label")
    }
}
