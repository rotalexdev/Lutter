package dev.rotalex.lutter.editor

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.ModifierEntry
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Phase 18.5's acceptance: a freeform page authored through the session and nothing else.
 *
 * The claim the phase makes is not "the canvas has handles" — it is that placing, resizing and
 * moving a node in groups **lands in the document**, as ordinary `layout.offset`/`layout.size`
 * modifiers that the codec writes, the runtime applies and the generator emits (D36). So this
 * test never touches a composable: it asks the session the questions the gestures ask and then
 * reads the document back, which is the only place the answer could be wrong without a window
 * showing it.
 *
 * One gesture, one undo step is the other half (D40): the drags are grouped and each group is a
 * single transaction, so stepping back once takes back the whole gesture and not one node of it.
 */
class FreeformAcceptanceTest {

    private val schema = BuiltinEngine.schema()

    @Test
    fun `a page placed through the session carries the placement in its own bytes`() {
        val session = EditorSession.open(freeformPage(), schema)

        assertIs<CommandResult.Ok>(session.moveBy(setOf(FIRST, SECOND), 40f, 24f))
        assertIs<CommandResult.Ok>(session.resizeBy(FIRST, width = 120f, height = 48f, shiftX = 0f, shiftY = 0f))

        // The placement is a modifier on the node, so the document is still a v1 document and the
        // runtime's own applier draws it — no coordinate field was added to the format.
        assertEquals(40f, dp(offsetOf(session, FIRST), Placement.x))
        assertEquals(24f, dp(offsetOf(session, FIRST), Placement.y))
        assertEquals(40f, dp(offsetOf(session, SECOND), Placement.x))
        assertEquals(120f, dp(sizeOf(session, FIRST), Placement.width))
        assertEquals(48f, dp(sizeOf(session, FIRST), Placement.height))

        // ... and it survives the codec, which is what makes it a document rather than a view.
        val bytes = JsonDocumentCodec.encode(session.document)
        val reread = JsonDocumentCodec.decode(bytes).document
        assertEquals(40f, dp(offsetOf(reread, SECOND), Placement.x))
        assertEquals(48f, dp(sizeOf(reread, FIRST), Placement.height))
    }

    @Test
    fun `each gesture is one undo step, whatever it moved`() {
        val session = EditorSession.open(freeformPage(), schema)
        session.moveBy(setOf(FIRST, SECOND), 40f, 24f)
        session.resizeBy(FIRST, width = 120f, height = 48f, shiftX = 0f, shiftY = 0f)

        session.undo()

        // The resize went back; the group move did not, because it was its own transaction.
        assertNull(sizeOf(session, FIRST))
        assertEquals(40f, dp(offsetOf(session, FIRST), Placement.x))
        assertEquals(40f, dp(offsetOf(session, SECOND), Placement.x))

        session.undo()

        assertNull(offsetOf(session, FIRST))
        assertNull(offsetOf(session, SECOND))
    }

    @Test
    fun `a group move composes with what a node already carried`() {
        val session = EditorSession.open(freeformPage(), schema)
        session.moveBy(FIRST, 10f, 10f)

        session.moveBy(setOf(FIRST, SECOND), 40f, 24f)

        assertEquals(50f, dp(offsetOf(session, FIRST), Placement.x))
        assertEquals(34f, dp(offsetOf(session, FIRST), Placement.y))
        assertEquals(40f, dp(offsetOf(session, SECOND), Placement.x))
    }

    private fun offsetOf(document: UiDocument, node: NodeId): ModifierEntry? =
        document.nodes[node]?.modifiers?.firstOrNull { it.type == Placement.offset }

    private fun offsetOf(session: EditorSession, node: NodeId): ModifierEntry? =
        offsetOf(session.document, node)

    private fun sizeOf(session: EditorSession, node: NodeId): ModifierEntry? =
        session.document.nodes[node]?.modifiers?.firstOrNull { it.type == Placement.size }

    private fun dp(entry: ModifierEntry?, key: PropertyKey): Float? =
        (entry?.args?.get(key) as? PropertyValue.Const)?.value?.let { value ->
            (value as? Value.Dp)?.v
        }

    /**
     * A column with two labels, which is the smallest page a freeform gesture can act on.
     *
     * Written here rather than taken from the bundled document so the acceptance has no host: the
     * fixtures this phase's gestures need are one container that admits children and two leaves.
     */
    private fun freeformPage(): UiDocument {
        val table: NodeTable = NodeTable.EMPTY
            .with(Node(id = ROOT, type = COLUMN, slots = mapOf(CHILDREN to listOf(FIRST, SECOND))))
            .with(Node(id = FIRST, type = TEXT, props = mapOf(TEXT_KEY to TEXT_VALUE)))
            .with(Node(id = SECOND, type = TEXT, props = mapOf(TEXT_KEY to TEXT_VALUE)))
        return UiDocument(
            meta = DocumentMeta("Freeform"),
            app = AppSpec("com.example.freeform", PAGE),
            pages = mapOf(PAGE to Page(id = PAGE, name = "Home", route = "/home", root = ROOT)),
            components = emptyMap(),
            nodes = table,
        )
    }

    private companion object {
        val PAGE: PageId = PageId("p_freeform")
        val ROOT: NodeId = NodeId("n_root")
        val FIRST: NodeId = NodeId("n_first")
        val SECOND: NodeId = NodeId("n_second")

        val COLUMN: ComponentType = ComponentType("core.Column")
        val TEXT: ComponentType = ComponentType("m3.Text")
        val CHILDREN: SlotName = SlotName("children")
        val TEXT_KEY: PropertyKey = PropertyKey("text")
        val TEXT_VALUE: PropertyValue = PropertyValue.Const(Value.Str("Freeform"))
    }
}
