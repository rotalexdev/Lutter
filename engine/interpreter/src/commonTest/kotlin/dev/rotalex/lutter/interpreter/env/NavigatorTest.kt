package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A destination reaches the navigator the environment carries, in the arguments it was given. */
class NavigatorTest {

    private val page: PageId = PageId("p_profile")

    @Test
    fun `a navigation arrives as the page it named`() {
        val went = GoingTo()

        went.navigate(page, emptyMap())

        assertEquals(page, went.opened)
    }

    @Test
    fun `a navigation arrives with the arguments it carried`() {
        val went = GoingTo()

        went.navigate(page, mapOf(ParamName("id") to Value.Str("42")))

        assertEquals(mapOf(ParamName("id") to Value.Str("42")), went.args)
    }

    @Test
    fun `stepping back reports that there was somewhere to go`() {
        assertFalse(GoingTo().back())
    }

    private class GoingTo : Navigator {
        var opened: PageId? = null
        var args: Map<ParamName, Value> = emptyMap()

        override fun navigate(page: PageId, args: Map<ParamName, Value>) {
            opened = page
            this.args = args
        }

        override fun back(): Boolean = false
    }
}
