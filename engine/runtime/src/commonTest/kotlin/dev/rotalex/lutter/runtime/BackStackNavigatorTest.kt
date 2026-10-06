package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A stack: a push on top, a back off it, and a root that has nowhere to go. */
class BackStackNavigatorTest {

    private val home: PageId = PageId("p_home")
    private val profile: PageId = PageId("p_profile")

    @Test
    fun `a navigator starts on the page it was given`() {
        assertEquals(home, BackStackNavigator(home).current.page)
    }

    @Test
    fun `a navigation puts the page it was given on top`() {
        val navigator = BackStackNavigator(home)

        navigator.navigate(profile, emptyMap())

        assertEquals(profile, navigator.current.page)
    }

    @Test
    fun `a navigation carries the arguments it was given`() {
        val navigator = BackStackNavigator(home)

        navigator.navigate(profile, mapOf(ParamName("id") to Value.Str("42")))

        assertEquals(mapOf(ParamName("id") to Value.Str("42")), navigator.current.args)
    }

    @Test
    fun `a caller that changes the map it passed does not change the destination`() {
        val navigator = BackStackNavigator(home)
        val args = mutableMapOf(ParamName("id") to Value.Str("42"))

        navigator.navigate(profile, args)
        args[ParamName("id")] = Value.Str("43")

        assertEquals(mapOf(ParamName("id") to Value.Str("42")), navigator.current.args)
    }

    @Test
    fun `a page the navigator has never heard of is pushed like any other`() {
        val navigator = BackStackNavigator(home)

        navigator.navigate(PageId("p_nowhere"), emptyMap())

        assertEquals(PageId("p_nowhere"), navigator.current.page)
    }

    @Test
    fun `stepping back at the root reports there was nowhere to go`() {
        assertFalse(BackStackNavigator(home).back())
    }

    @Test
    fun `stepping back at the root leaves the page alone`() {
        val navigator = BackStackNavigator(home)

        navigator.back()

        assertEquals(home, navigator.current.page)
    }

    @Test
    fun `stepping back after a navigation reports there was somewhere to go`() {
        val navigator = pushed()

        assertTrue(navigator.back())
    }

    @Test
    fun `stepping back pops to the page underneath`() {
        val navigator = pushed()

        navigator.back()

        assertEquals(home, navigator.current.page)
    }

    @Test
    fun `a second back at the root refuses again`() {
        val navigator = pushed()

        navigator.back()

        assertFalse(navigator.back())
    }

    private fun pushed(): BackStackNavigator = BackStackNavigator(home).apply {
        navigate(profile, emptyMap())
    }
}
