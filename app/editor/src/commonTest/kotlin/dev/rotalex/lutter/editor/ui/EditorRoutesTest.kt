package dev.rotalex.lutter.editor.ui

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame's navigation, without a window.
 *
 * The three moves the frame makes are list operations on the back stack, so they are asserted
 * here rather than through a UI test: what a phone shows after a tap is the composition's
 * business, and what the stack holds afterwards is this file's.
 */
class EditorRoutesTest {

    private fun stack(vararg routes: NavKey): MutableList<NavKey> = routes.toMutableList()

    @Test
    fun startsAtTheEditor() {
        val backStack = stack(EditorRoute)

        assertEquals(listOf<NavKey>(EditorRoute), backStack)
    }

    @Test
    fun switchingToCodeReplacesTheEditorInsteadOfStackingIt() {
        val backStack = stack(EditorRoute)

        backStack.switchTo(CodeRoute)

        assertEquals(listOf<NavKey>(CodeRoute), backStack)
    }

    @Test
    fun switchingBackToTheEditorLeavesOneEntry() {
        val backStack = stack(CodeRoute)

        backStack.switchTo(EditorRoute)

        assertEquals(listOf<NavKey>(EditorRoute), backStack)
    }

    @Test
    fun switchingToATopLevelDropsWhatWasPushedOnTop() {
        val backStack = stack(EditorRoute)
        backStack.push(SettingsRoute)

        backStack.switchTo(CodeRoute)

        assertEquals(listOf<NavKey>(CodeRoute), backStack)
    }

    @Test
    fun settingsIsPushedOverTheScreenThatOpenedIt() {
        val backStack = stack(CodeRoute)

        backStack.push(SettingsRoute)

        assertEquals(listOf<NavKey>(CodeRoute, SettingsRoute), backStack)
    }

    @Test
    fun backFromSettingsReturnsToTheScreenUnderIt() {
        val backStack = stack(EditorRoute)
        backStack.push(SettingsRoute)

        assertTrue(backStack.pop())

        assertEquals(listOf<NavKey>(EditorRoute), backStack)
    }

    @Test
    fun backAtTheRootIsThePlatformsToHandle() {
        val backStack = stack(EditorRoute)

        assertFalse(backStack.pop())

        assertEquals(listOf<NavKey>(EditorRoute), backStack)
    }

    @Test
    fun thePrimaryNavigationOffersTheTwoScreensAndNotSettings() {
        assertEquals(listOf<NavKey>(EditorRoute, CodeRoute), PrimaryRoutes)
        assertEquals("Editor", labelOf(EditorRoute))
        assertEquals("Code", labelOf(CodeRoute))
        assertEquals("Settings", labelOf(SettingsRoute))
    }
}
