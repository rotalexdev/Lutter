package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.policy.DropTarget
import dev.rotalex.lutter.model.ids.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a multi-selection is, and what it does when the document changes under it.
 *
 * The invariant is one representation per state: none, one, or several. A set of one is a [Node]
 * and not a `Nodes` of size one, which is what keeps the inspector and the dock from having to
 * answer the same question twice.
 */
class EditorSelectionTest {

    private fun secondLeaf(session: EditorSession): NodeId {
        session.insert(LABEL, DropTarget(parent = ROOT, slot = CHILDREN, index = 0))
        return (session.selection.value as EditorSelection.Node).id
    }

    @Test
    fun `one id is a node`() {
        val session = session()

        session.selectNodes(setOf(LEAF))

        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }

    @Test
    fun `several ids are a set with the first as primary`() {
        val session = session()
        val second: NodeId = secondLeaf(session)

        session.selectNodes(linkedSetOf(LEAF, second))

        val expected = EditorSelection.Nodes(ids = setOf(LEAF, second), primary = LEAF)
        assertEquals(expected, session.selection.value)
    }

    @Test
    fun `an id this document does not contain is dropped`() {
        val session = session()

        session.selectNodes(setOf(LEAF, ABSENT))

        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }

    @Test
    fun `an empty set selects nothing`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        session.selectNodes(emptySet())

        assertEquals(EditorSelection.None, session.selection.value)
    }

    @Test
    fun `the inspector shows the primary of a multi-selection`() {
        val session = session()
        val second: NodeId = secondLeaf(session)
        session.selectNodes(linkedSetOf(second, LEAF))

        val model = session.inspector()

        assertEquals(second, model?.node)
    }

    @Test
    fun `removing one of two selected nodes keeps the other selected`() {
        val session = session()
        val second: NodeId = secondLeaf(session)
        session.selectNodes(linkedSetOf(LEAF, second))

        session.remove(LEAF)

        assertEquals(EditorSelection.Node(second), session.selection.value)
    }

    @Test
    fun `removing every selected node clears the selection`() {
        val session = session()
        val second: NodeId = secondLeaf(session)
        session.selectNodes(linkedSetOf(LEAF, second))

        session.remove(setOf(LEAF, second))

        assertEquals(EditorSelection.None, session.selection.value)
        assertNull(session.document.nodes[LEAF])
        assertNull(session.document.nodes[second])
    }

    @Test
    fun `a group delete is one undo step`() {
        val session = session()
        val second: NodeId = secondLeaf(session)

        assertIs<CommandResult.Ok>(session.remove(setOf(LEAF, second)))
        assertTrue(session.undo())

        assertEquals(LABEL, session.document.nodes[LEAF]?.type)
        assertEquals(LABEL, session.document.nodes[second]?.type)
    }
}
