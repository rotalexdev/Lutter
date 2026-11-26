package dev.rotalex.lutter.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo and redo as the frame uses them: one step, with the selection reconciled.
 *
 * The controller owns the stack, but a shell that called it directly would keep a selection
 * pointing at a node the undo just removed, and show an inspector for it. These four cases are
 * exactly the ways that shows up: the step that works, the step after it, the step that has
 * nothing to do, and the step that brings a deleted subtree back.
 */
class UndoRedoTest {

    @Test
    fun undo_takes_back_an_insert_and_drops_the_selection_that_named_it() {
        val session = session()
        val before = session.document.nodes.size
        session.insert(LABEL, session.dropTargets(LABEL).first())
        assertEquals(before + 1, session.document.nodes.size)
        assertTrue(session.selection.value is EditorSelection.Node)

        assertTrue(session.undo())

        assertEquals(before, session.document.nodes.size)
        // The inserted node is gone, so a selection that still named it would be a second
        // document to the inspector: `None` is the only honest answer.
        assertEquals(EditorSelection.None, session.selection.value)
    }

    @Test
    fun redo_puts_the_insert_back() {
        val session = session()
        val before = session.document.nodes.size
        session.insert(LABEL, session.dropTargets(LABEL).first())
        session.undo()

        assertTrue(session.redo())

        assertEquals(before + 1, session.document.nodes.size)
    }

    @Test
    fun an_empty_history_answers_false_and_changes_nothing() {
        val session = session()
        val before = session.document

        assertFalse(session.undo())
        assertFalse(session.redo())

        assertEquals(before, session.document)
    }

    @Test
    fun undo_of_a_delete_restores_the_subtree() {
        val session = session()
        session.select(EditorSelection.Node(LEAF))
        session.remove(LEAF)
        assertNull(session.document.nodes[LEAF])
        assertEquals(EditorSelection.None, session.selection.value)

        assertTrue(session.undo())

        // The node, its type and its property come back as they were: the history holds
        // commands, and a command that is taken back restores what it removed.
        assertNotNull(session.document.nodes[LEAF])
        assertEquals(HELLO, session.document.nodes.require(LEAF).props[TEXT])
        // Removal already reconciled the selection away, and undo does not put it back:
        // deciding what the person meant to select is not the undo's job.
        assertEquals(EditorSelection.None, session.selection.value)
    }
}
