package dev.rotalex.lutter.editor

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a palette tap puts in the document.
 *
 * The bug this pins was found on a phone, not in a test: seven taps on the palette produced seven
 * empty purple pills. `m3.Button` is a pill that draws its `content` slot, and the insert put
 * nothing there; `m3.Text` draws its `text`, and the insert set no property. The editor was
 * faithful to "properties are analysis's to default" and had no answer at all for structure.
 *
 * These assertions run against the **builtin** schema, because the template's vocabulary is the
 * builtin one — `builtins-compose` is a test-scope edge in this module for exactly this reason.
 * The hand-built fixture schema has no `m3.Text`, and the last test says what happens there: a
 * bare node, because there is no placeholder to add and guessing one would be the editor inventing
 * a component.
 */
class PaletteTemplateTest {

    private val schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
        BuiltinEngine.schema()

    /** One page whose root is a column: the container every palette entry has a slot in. */
    private fun columnDocument(): UiDocument = UiDocument(
        meta = DocumentMeta("Palette"),
        app = AppSpec("com.example.palette", HOME),
        pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(Node(id = ROOT, type = ComponentType("core.Column"))),
    )

    private fun insertedOnto(session: EditorSession, type: ComponentType): Node {
        val target = DropTarget(parent = ROOT, slot = CHILDREN, index = 0)
        assertIs<CommandResult.Ok>(session.insert(type, target), "the insert must be accepted")
        val selection = assertIs<EditorSelection.Node>(session.selection.value)
        return session.document.nodes.require(selection.id)
    }

    @Test
    fun `a button arrives with the text it cannot draw without`() {
        val session = EditorSession.open(columnDocument(), schema)

        val button = insertedOnto(session, ComponentType("m3.Button"))

        assertEquals(ComponentType("m3.Button"), button.type)
        val content = assertNotNull(button.slots[SlotName("content")], "a button with no content draws a pill")
        assertEquals(1, content.size)
        val label = session.document.nodes.require(content.single())
        assertEquals(ComponentType("m3.Text"), label.type)
        assertEquals(PropertyValue.Const(Value.Str("Button")), label.props[TEXT])
    }

    @Test
    fun `a text arrives with the word it cannot draw without`() {
        val session = EditorSession.open(columnDocument(), schema)
        val before = session.document.nodes.size

        val text = insertedOnto(session, ComponentType("m3.Text"))

        assertEquals(PropertyValue.Const(Value.Str("Text")), text.props[TEXT])
        assertTrue(text.slots.isEmpty(), "a text has no slot to put a placeholder in")
        assertEquals(before + 1, session.document.nodes.size, "one node, not a node and a child")
    }

    @Test
    fun `a column arrives with one child so the insert is visible`() {
        val session = EditorSession.open(columnDocument(), schema)

        val column = insertedOnto(session, ComponentType("core.Column"))

        val children = assertNotNull(column.slots[CHILDREN])
        assertEquals(1, children.size)
        assertEquals(ComponentType("m3.Text"), session.document.nodes.require(children.single()).type)
    }

    @Test
    fun `the placeholder is not what the inspector opens on`() {
        val session = EditorSession.open(columnDocument(), schema)

        val button = insertedOnto(session, ComponentType("m3.Button"))

        // Selecting the label would put a `Text` in the fields of the thing the author just
        // added; the selection is the node `insert` landed.
        assertEquals(EditorSelection.Node(button.id), session.selection.value)
    }

    @Test
    fun `a schema without the label type inserts the bare node rather than guessing`() {
        val session = EditorSession.open(editorDocument(), editorSchema())
        val before = session.document.nodes.size

        assertIs<CommandResult.Ok>(session.insert(LABEL, DropTarget(parent = ROOT, slot = CHILDREN, index = 1)))

        assertEquals(before + 1, session.document.nodes.size)
    }
}
