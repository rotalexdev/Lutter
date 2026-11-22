package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSessionTest {

    @Test
    fun `a fresh session has nothing selected and no inspector`() {
        val session = session()

        assertEquals(EditorSelection.None, session.selection.value)
        assertNull(session.inspector())
    }

    @Test
    fun `selecting a node gives the inspector its rows`() {
        val session = session()

        session.select(EditorSelection.Node(LEAF))

        val model = session.inspector()
        assertNotNull(model)
        assertEquals(LEAF, model.node)
    }

    @Test
    fun `setProperty applies the value and keeps the selection`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        val result = session.setProperty(LEAF, TEXT, "\"Goodbye\"")

        assertIs<PropertyWrite.Applied>(result)
        assertEquals(
            PropertyValue.Const(Value.Str("Goodbye")),
            session.document.nodes.require(LEAF).props[TEXT],
        )
        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
    }

    @Test
    fun `setProperty on unparseable text writes nothing`() {
        val session = session()
        val before = session.document

        val result = session.setProperty(LEAF, TEXT, "\"unterminated")

        assertIs<PropertyWrite.Unparseable>(result)
        assertEquals(before, session.document)
    }

    @Test
    fun `a command the planner refuses is reported with the engine's reasons`() {
        val session = session()
        val before = session.document

        // ABSENT is not in the document, so the planner refuses the command it would produce.
        val result = session.setProperty(ABSENT, TEXT, "\"nowhere\"")

        assertIs<PropertyWrite.Refused>(result)
        assertTrue(result.reasons.isNotEmpty())
        assertEquals(before, session.document)
    }

    @Test
    fun `removing the selected node clears the selection`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        assertIs<CommandResult.Ok>(
            session.dispatch(Command.RemoveNode(LEAF)),
        )

        assertEquals(EditorSelection.None, session.selection.value)
        assertNull(session.inspector())
    }

    @Test
    fun `a refused command keeps a selection the document still backs`() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))

        assertIs<CommandResult.Rejected>(
            session.dispatch(Command.RemoveNode(ABSENT)),
        )

        // A refusal is a fact about the command, not about the node that happens to be
        // selected, so a selection the document still holds survives it untouched.
        assertEquals(EditorSelection.Node(LEAF), session.selection.value)
        assertNotNull(session.inspector())
    }

    @Test
    fun `a selection naming a node outside the document is cleared`() {
        val session = session()
        session.select(EditorSelection.Node(ABSENT))

        // Nothing is dispatched here: the reconciliation that follows a command also runs on a
        // selection that never pointed at anything, and it is what keeps the inspector from
        // having to guard against a stale id.
        session.dispatch(Command.RemoveNode(ABSENT))

        assertEquals(EditorSelection.None, session.selection.value)
    }

    @Test
    fun `the palette comes from the session's own schema`() {
        assertTrue(session().palette().flatMap { it.entries }.any { it.type == LABEL })
    }
}
