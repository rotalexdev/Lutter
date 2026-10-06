package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.actions.FlowActions
import dev.rotalex.lutter.builtins.actions.HostActions
import dev.rotalex.lutter.builtins.actions.NavActions
import dev.rotalex.lutter.builtins.actions.StateActions
import dev.rotalex.lutter.builtins.actions.UiActions
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * §11.4's six MVP actions: the key set, the shapes §11.2 and §13.1 spell, and the registrar.
 *
 * **There is deliberately no handler-coverage test here, and its absence is the contract.**
 * `IntrinsicHandlers` performs the navigation pair alone, so a test asserting every registered
 * action has a handler would be red for the four ids that name neither an argument nor a
 * condition. `BuiltinFunctionCoverageTest` is that gate's shape.
 *
 * Every assertion below is a claim PLAN makes, read back through the registry.
 */
class BuiltinActionSpecsTest {

    private val schema: Schema<String, String, ActionSpec, String, String> =
        Schema.build<String, String, ActionSpec, String, String> { registerBuiltinActions() }

    @Test
    fun `the seed set is the six ids the plan names`() {
        assertEquals(
            MVP_IDS,
            schema.actions.all().map { it.id.value },
            "§11.4's MVP set, exactly: a seventh id or a misspelling fails here",
        )
    }

    @Test
    fun `every action is intrinsic, because no plugin action is in the set`() {
        for (spec in schema.actions.all()) {
            assertIs<ActionEmit.Intrinsic>(spec.emit, "'${spec.id.value}' is not engine-owned")
        }
    }

    @Test
    fun `only flow if carries arms`() {
        val branched = schema.actions.all().filter { it.branches.isNotEmpty() }

        assertEquals(listOf(ActionId("flow.if")), branched.map { it.id })
    }

    @Test
    fun `flow if names then and else, and requires only then`() {
        val branches = schema.actions.require(ActionId("flow.if")).branches

        assertEquals(listOf(BranchName("then"), BranchName("else")), branches.map { it.name })
        assertEquals(listOf(true, false), branches.map { it.required })
    }

    @Test
    fun `navigate declares the page argument the plan writes`() {
        val params = schema.actions.require(ActionId("nav.navigate")).params

        assertEquals(listOf(PropertyKey("page")), params.map { it.key })
        assertEquals(TypeRef.Ref(RefKind.Page), params.single().type)
        assertEquals(true, params.single().required)
    }

    @Test
    fun `no spec declares a typed argument the plan does not name`() {
        val declared = schema.actions.all().flatMap { spec -> spec.params.map { it.key } }

        // §13.1 is the only section that spells a typed action argument, and it spells one. The
        // other five declare none in `params` because no `TypeRef` can say what they take — the
        // two `state.set` keys are shapes instead, and are asserted as such below.
        assertEquals(listOf(PropertyKey("page")), declared)
    }

    @Test
    fun `state set names its target and value as shapes, both required`() {
        val rules = schema.actions.require(ActionId("state.set")).argRules

        assertEquals(listOf(PropertyKey("target"), PropertyKey("value")), rules.map { it.key })
        assertEquals(listOf(true, true), rules.map { it.required })
        assertEquals(listOf(ArgShape.StateRef, ArgShape.TargetValue), rules.map { it.shape })
    }

    @Test
    fun `no other spec declares a shape, so no other argument is closed`() {
        val shaped = schema.actions.all().filter { it.argRules.isNotEmpty() }

        assertEquals(listOf(ActionId("state.set")), shaped.map { it.id })
    }

    @Test
    fun `the registrar files each declared spec under its own id`() {
        val declared = NavActions.all + StateActions.all + FlowActions.all +
            HostActions.all + UiActions.all

        assertEquals(MVP_IDS.size, declared.size, "two objects declare one id, or one is undeclared")
        for (spec in declared) {
            assertSame(spec, schema.actions.require(spec.id), "'${spec.id.value}' is not the declared spec")
        }
    }

    private companion object {
        /** §11.4's MVP list, in registry key order. */
        val MVP_IDS: List<String> = listOf(
            "flow.if", "host.call", "nav.back", "nav.navigate", "state.set", "ui.showSnackbar",
        )
    }
}
