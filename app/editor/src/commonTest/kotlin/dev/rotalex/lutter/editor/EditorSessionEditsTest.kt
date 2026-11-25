package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural edits through the session: the four commands a canvas dispatches, and what each
 * one does to the selection.
 *
 * A second panel is what makes a move observable: `LEAF` starts in `ROOT`'s slot and ends in
 * `TRAY`'s, and both lists are asserted rather than only the destination — a move that copied
 * instead of relocating would satisfy the destination half alone.
 */
class EditorSessionEditsTest {

    private fun twoPanels(): UiDocument {
        val table: NodeTable = NodeTable.EMPTY
            .with(Node(id = ROOT, type = PANEL, slots = mapOf(CHILDREN to listOf(LEAF, TRAY))))
            .with(Node(id = LEAF, type = LABEL, props = mapOf(TEXT to HELLO)))
            .with(Node(id = TRAY, type = PANEL))
        return UiDocument(
            meta = DocumentMeta("Edits"),
            app = AppSpec("com.example.editor", HOME),
            pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
            components = emptyMap(),
            nodes = table,
        )
    }

    @Test
    fun `an insertion lands at the offered target and selects the new node`() {
        val session = session()
        val target = session.dropTargets(LABEL).single { it.parent == ROOT }

        assertIs<CommandResult.Ok>(session.insert(LABEL, target))

        val siblings = session.document.nodes.require(ROOT).slots.getValue(CHILDREN)
        assertEquals(2, siblings.size)
        val inserted = siblings.last()
        assertEquals(LABEL, session.document.nodes.require(inserted).type)
        assertEquals(EditorSelection.Node(inserted), session.selection.value)
        assertNotNull(session.inspector())
    }

    @Test
    fun `an insertion the planner refuses leaves the document and the selection alone`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))
        val before = session.document

        val result = session.insert(LABEL, DropTarget(parent = ABSENT, slot = CHILDREN, index = 0))

        assertIs<CommandResult.Rejected>(result)
        assertEquals(before, session.document)
        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }

    @Test
    fun `a slot that is already full is not offered`() {
        // `CARD.content` holds exactly one child, so once `LEAF` is there no second child fits
        // and the target list must stop offering it.
        val card = ComponentType("test.Card")
        val content = SlotName("content")
        val schema = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            component(
                componentSpec(card, 1) {
                    metadata("Card", Category.Layout)
                    slot(SlotSpec(content, Cardinality.ExactlyOne))
                    intrinsic()
                },
            )
            component(
                componentSpec(LABEL, 1) {
                    metadata("Label", Category.Basic)
                    intrinsic()
                },
            )
        }
        val table = NodeTable.EMPTY
            .with(Node(id = ROOT, type = card, slots = mapOf(content to listOf(LEAF))))
            .with(Node(id = LEAF, type = LABEL))
        val document = UiDocument(
            meta = DocumentMeta("Full"),
            app = AppSpec("com.example.editor", HOME),
            pages = mapOf(HOME to Page(id = HOME, name = "Home", route = "/home", root = ROOT)),
            components = emptyMap(),
            nodes = table,
        )

        val targets = session(document, schema).dropTargets(LABEL)

        assertTrue(targets.none { it.parent == ROOT && it.slot == content })
    }

    @Test
    fun `a duplicate selects the copy and leaves the original where it was`() {
        val session = session()

        assertIs<CommandResult.Ok>(session.duplicate(LEAF))

        // `NodeTable.ids()` is a `Sequence`, so the filtered result needs materializing before
        // it has a `size` — and materializing also pins the answer to this revision.
        val labels = session.document.nodes.ids().filter {
            session.document.nodes.require(it).type == LABEL
        }.toList()
        assertEquals(2, labels.size)
        assertTrue(LEAF in labels)
        val copy = session.selection.value as? EditorSelection.Node
        assertNotNull(copy)
        assertTrue(copy.id != LEAF)
        assertTrue(copy.id in labels)
    }

    @Test
    fun `a move relocates the node, keeps it selected and empties the old slot`() {
        val session = session(twoPanels())

        assertIs<CommandResult.Ok>(
            session.move(LEAF, DropTarget(parent = TRAY, slot = CHILDREN, index = 0)),
        )

        assertEquals(listOf(TRAY), session.document.nodes.require(ROOT).slots.getValue(CHILDREN))
        assertEquals(listOf(LEAF), session.document.nodes.require(TRAY).slots.getValue(CHILDREN))
        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }

    @Test
    fun `a removal of the selected node clears the selection`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        assertIs<CommandResult.Ok>(session.remove(LEAF))

        assertTrue(LEAF !in session.document.nodes)
        assertEquals(EditorSelection.None, session.selection.value)
    }

    @Test
    fun `a removal of some other node keeps the selection`() {
        val session = session(twoPanels())
        session.select(EditorSelection.Node(TRAY))

        assertIs<CommandResult.Ok>(session.remove(LEAF))

        assertEquals(EditorSelection.Node(TRAY), session.selection.value)
    }
}

private val TRAY: NodeId = NodeId("n_tray")
