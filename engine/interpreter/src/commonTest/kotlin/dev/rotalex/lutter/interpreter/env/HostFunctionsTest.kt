package dev.rotalex.lutter.interpreter.env

import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A registered name resolves to what was registered; anything else is absent, not fatal. */
class HostFunctionsTest {

    private val double: HostFunction = { args -> Value.Int32((args.single() as Value.Int32).v * 2) }

    @Test
    fun `a registered name resolves to the function it was given`() {
        val host = HostFunctions(mapOf("double" to double))

        assertEquals(double, host.find("double"))
    }

    @Test
    fun `a name the application did not register resolves to nothing`() {
        val host = HostFunctions(mapOf("double" to double))

        assertNull(host.find("triple"))
    }

    @Test
    fun `an environment with no host functions resolves nothing at all`() {
        assertNull(HostFunctions.None.find("double"))
    }

    @Test
    fun `a later change to the caller's own map does not change what resolves`() {
        val registered = mutableMapOf("double" to double)
        val host = HostFunctions(registered)

        registered["triple"] = double

        assertNull(host.find("triple"))
    }
}
