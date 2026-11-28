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
 * What a handle drag commits: `layout.size` always, `layout.offset` only when the grip moved the
 * origin — and both as one undo step.
 *
 * These are the properties the canvas leans on: a resize from the right edge leaves the offset
 * alone, a resize from the left edge writes one, and undoing a gesture takes back both commands
 * at once.
 */
class EditorResizeTest {

    private fun sizeOf(session: EditorSession): ModifierEntry? =
        session.document.nodes[LEAF]?.modifiers?.firstOrNull { it.type == Placement.size }

    private fun offsetOf(session: EditorSession): ModifierEntry? =
        session.document.nodes[LEAF]?.modifiers?.firstOrNull { it.type == Placement.offset }

    @Test
    fun `a resize writes both sides`() {
        val session = session()

        assertIs<CommandResult.Ok>(session.resizeBy(LEAF, width = 120f, height = 60f, shiftX = 0f, shiftY = 0f))

        val entry = sizeOf(session)
        assertEquals(PropertyValue.Const(Value.Dp(120f)), entry?.args?.get(Placement.width))
        assertEquals(PropertyValue.Const(Value.Dp(60f)), entry?.args?.get(Placement.height))
    }

    @Test
    fun `a resize that moves no origin leaves the offset alone`() {
        val session = session()

        session.resizeBy(LEAF, width = 120f, height = 60f, shiftX = 0f, shiftY = 0f)

        assertEquals(null, offsetOf(session))
        assertEquals(listOf(Placement.size), session.document.nodes[LEAF]?.modifiers?.map { it.type })
    }

    @Test
    fun `a resize that moves the origin writes the offset it took`() {
        val session = session()

        session.resizeBy(LEAF, width = 120f, height = 60f, shiftX = 10f, shiftY = 5f)

        val entry = offsetOf(session)
        assertEquals(PropertyValue.Const(Value.Dp(10f)), entry?.args?.get(Placement.x))
        assertEquals(PropertyValue.Const(Value.Dp(5f)), entry?.args?.get(Placement.y))
    }

    @Test
    fun `a resize composes with the offset a move left`() {
        val session = session()
        session.moveBy(LEAF, 40f, 24f)

        session.resizeBy(LEAF, width = 120f, height = 60f, shiftX = 10f, shiftY = -4f)

        val entry = offsetOf(session)
        assertEquals(PropertyValue.Const(Value.Dp(50f)), entry?.args?.get(Placement.x))
        assertEquals(PropertyValue.Const(Value.Dp(20f)), entry?.args?.get(Placement.y))
    }

    @Test
    fun `one resize is one undo step`() {
        val session = session()

        session.resizeBy(LEAF, width = 120f, height = 60f, shiftX = 10f, shiftY = 5f)
        assertTrue(session.undo())

        assertEquals(null, sizeOf(session))
        assertEquals(null, offsetOf(session))
        assertTrue(session.document.nodes[LEAF]?.modifiers?.isEmpty() == true)
    }
}
