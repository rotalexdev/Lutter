package dev.rotalex.lutter.generated.compile

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The conformance assembly binds the action slot to `ActionSpec` and registers §11.4's six.
 *
 * The registrar's own test cannot see this: it builds a schema of its own. What fails here is the
 * assembly dropping `registerBuiltinActions()` — or the slot going back to a stub, which would
 * leave the specs registered nowhere and every future handler test asserting against nothing.
 */
class ActionRegistryTest {

    @Test
    fun `the assembly schema carries the six MVP action ids`() {
        assertEquals(
            listOf("flow.if", "host.call", "nav.back", "nav.navigate", "state.set", "ui.showSnackbar"),
            ConformanceHarness.schema().actions.all().map { it.id.value },
            "the conformance assembly does not carry §11.4's MVP set",
        )
    }
}
