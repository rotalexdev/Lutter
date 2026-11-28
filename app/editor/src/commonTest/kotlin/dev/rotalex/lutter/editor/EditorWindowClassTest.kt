package dev.rotalex.lutter.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three boundaries, and the one thing that matters about them: they are Material's.
 *
 * A phone in portrait is compact, a small tablet window is medium, and a desk is expanded; the
 * numbers here are the ones `WindowSizeClass` uses, so an arrangement chosen by this value and one
 * chosen by the platform cannot disagree.
 */
class EditorWindowClassTest {

    @Test
    fun `a phone width is compact`() {
        assertEquals(EditorWindowClass.Compact, EditorWindowClass.of(320))
        assertEquals(EditorWindowClass.Compact, EditorWindowClass.of(599))
    }

    @Test
    fun `the medium boundary includes its own width`() {
        assertEquals(EditorWindowClass.Medium, EditorWindowClass.of(600))
        assertEquals(EditorWindowClass.Medium, EditorWindowClass.of(839))
    }

    @Test
    fun `the expanded boundary includes its own width`() {
        assertEquals(EditorWindowClass.Expanded, EditorWindowClass.of(840))
        assertEquals(EditorWindowClass.Expanded, EditorWindowClass.of(1024))
    }

    @Test
    fun `a degenerate width is still compact rather than an error`() {
        assertEquals(EditorWindowClass.Compact, EditorWindowClass.of(0))
        assertEquals(EditorWindowClass.Compact, EditorWindowClass.of(-1))
    }
}
