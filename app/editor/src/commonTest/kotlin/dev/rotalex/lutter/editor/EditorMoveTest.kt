package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a drag commits: one `layout.offset` per gesture, added to whatever the node already had.
 *
 * The properties here are the ones a gesture leans on — both axes written, a second gesture
 * composing with the first, and one undo step per gesture — so the canvas can preview a drag in
 * its own state and commit it once on release.
 */
class EditorMoveTest {

    private fun offsetOf(session: EditorSession): ModifierEntry? =
        session.document.nodes[LEAF]?.modifiers?.firstOrNull { it.type == Placement.offset }

    @Test
    fun `a move writes both axes on a node that had no offset`() {
        val session = session()

        assertIs<CommandResult.Ok>(session.moveBy(LEAF, 40f, -12f))

        val entry = offsetOf(session)
        assertEquals(listOf(Placement.offset), session.document.nodes[LEAF]?.modifiers?.map { it.type })
        assertEquals(PropertyValue.Const(Value.Dp(40f)), entry?.args?.get(Placement.x))
        assertEquals(PropertyValue.Const(Value.Dp(-12f)), entry?.args?.get(Placement.y))
    }

    @Test
    fun `a second move adds to the first`() {
        val session = session()
        session.moveBy(LEAF, 40f, 24f)

        session.moveBy(LEAF, -10f, 6f)

        val entry = offsetOf(session)
        assertEquals(PropertyValue.Const(Value.Dp(30f)), entry?.args?.get(Placement.x))
        assertEquals(PropertyValue.Const(Value.Dp(30f)), entry?.args?.get(Placement.y))
        assertEquals(1, session.document.nodes[LEAF]?.modifiers?.count { it.type == Placement.offset })
    }

    @Test
    fun `one move is one undo step`() {
        val session = session()
        session.moveBy(LEAF, 40f, 24f)

        assertTrue(session.undo())

        assertEquals(null, offsetOf(session))
        assertTrue(session.document.nodes[LEAF]?.modifiers?.isEmpty() == true)
    }

    @Test
    fun `a move keeps the selection`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        session.moveBy(LEAF, 8f, 8f)

        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }
}
