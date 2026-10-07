package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape
import dev.rotalex.lutter.schema.action.BranchSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One handler's statements, asserted as the source the printer writes for them.
 *
 * Hand-built specs over hand-built registries, as the expression emitter's own tests are: the two
 * facts under test are which statement an action becomes and which receiver it runs against, and
 * both are decided from the spec and the resolved document rather than from an analyzer.
 */
class ActionEmitterTest {

    private val basePackage: String = "com.example.app"

    private val count: StateId = StateId("s_count")
    private val home: PageId = PageId("p_home")
    private val profile: PageId = PageId("p_profile")

    private val pageKey: PropertyKey = PropertyKey("page")
    private val targetKey: PropertyKey = PropertyKey("target")
    private val valueKey: PropertyKey = PropertyKey("value")
    private val conditionKey: PropertyKey = PropertyKey("cond")
    private val nameKey: PropertyKey = PropertyKey("name")
    private val argsKey: PropertyKey = PropertyKey("args")
    private val messageKey: PropertyKey = PropertyKey("message")
    private val eventKey: PropertyKey = PropertyKey("event")
    private val thenArm: BranchName = BranchName("then")
    private val elseArm: BranchName = BranchName("else")

    @Test
    fun `a navigation step names the generated route`() {
        val step = ActionStep(
            action = ActionId("nav.navigate"),
            args = mapOf(pageKey to PropertyValue.Const(Value.Ref(RefKind.Page, profile.value))),
        )

        assertEquals(
            """
            package com.example.app.screens

            import com.example.app.Route

            public fun onClick() {
                navigator.navigate(Route.Profile)
            }

            """.trimIndent(),
            source(sequenceOf(step)),
        )
    }

    @Test
    fun `a navigation step carrying route arguments is refused`() {
        val step = ActionStep(
            action = ActionId("nav.navigate"),
            args = mapOf(
                pageKey to PropertyValue.Const(Value.Ref(RefKind.Page, profile.value)),
                PropertyKey("id") to PropertyValue.Const(Value.Str("42")),
            ),
        )

        assertEquals(
            listOf("codegen.strategy_unsupported"),
            refusals(sequenceOf(step)).map { it.code.value },
        )
    }

    @Test
    fun `a back step emits the navigator's own call`() {
        val sequence = sequenceOf(ActionStep(action = ActionId("nav.back")))

        assertEquals("navigator.back()", body(sequence))
    }

    @Test
    fun `a state write emits a direct assignment through the screen's own spelling`() {
        val step = ActionStep(
            action = ActionId("state.set"),
            args = mapOf(
                targetKey to PropertyValue.Computed(Expr.Ref(RefTarget.State(count))),
                valueKey to PropertyValue.Computed(
                    Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(1))),
                ),
            ),
        )

        assertEquals("state.count = state.count + 1", body(sequenceOf(step)))
    }

    @Test
    fun `a snackbar step hands its message to the presentation host`() {
        val step = ActionStep(
            action = ActionId("ui.showSnackbar"),
            args = mapOf(messageKey to PropertyValue.Const(Value.Str("Saved"))),
        )

        assertEquals("snackbars.show(\"Saved\")", body(sequenceOf(step)))
    }

    @Test
    fun `a host call that does not suspend is emitted on its own`() {
        val sequence = sequenceOf(hostStep(Value.Str("42")))

        assertEquals("host.submit(\"42\")", body(sequence, declaring("submit", suspending = false)))
    }

    @Test
    fun `a suspending host call runs inside a launched block`() {
        val sequence = sequenceOf(hostStep(Value.Str("42")))

        // The whole file, because the wrapper is a member reached through a receiver and its
        // import only exists if the printer was told which symbol the text needs.
        assertEquals(
            """
            package com.example.app.screens

            import kotlinx.coroutines.launch

            public fun onClick() {
                scope.launch {
                    host.submit("42")
                }
            }

            """.trimIndent(),
            source(sequence, declaring("submit", suspending = true)),
        )
    }

    @Test
    fun `a sequence mixing a suspending step wraps every step in one block`() {
        // Wrapping only the call would let the step before it run again while the call is in
        // flight, which is the ordering the interpreter's sequential runner guarantees.
        val sequence = ActionSequence(
            listOf(ActionStep(action = ActionId("nav.back")), hostStep(Value.Str("42"))),
        )

        assertEquals(
            """
            scope.launch {
                navigator.back()
                host.submit("42")
            }
            """.trimIndent(),
            body(sequence, declaring("submit", suspending = true)),
        )
    }

    @Test
    fun `a host call with no declaration in reach is refused`() {
        val sequence = sequenceOf(hostStep(Value.Str("42")))

        assertEquals(
            listOf("codegen.strategy_unsupported"),
            refusals(sequence).map { it.code.value },
        )
    }

    @Test
    fun `a host argument reads state rather than a constant`() {
        val sequence = sequenceOf(hostReading(Expr.Ref(RefTarget.State(count))))

        assertEquals("host.submit(state.count)", body(sequence, declaring("submit", suspending = false)))
    }

    @Test
    fun `a conditional emits an if with both arms`() {
        val step = ActionStep(
            action = ActionId("flow.if"),
            args = mapOf(conditionKey to PropertyValue.Computed(readsMoreThanZero())),
            branches = mapOf(
                thenArm to sequenceOf(ActionStep(action = ActionId("nav.back"))),
                elseArm to sequenceOf(snackbar("Empty")),
            ),
        )

        assertEquals(
            """
            if (state.count > 0) {
                navigator.back()
            } else {
                snackbars.show("Empty")
            }
            """.trimIndent(),
            body(sequenceOf(step)),
        )
    }

    @Test
    fun `a conditional without an alternative emits an empty one`() {
        // The runtime answers a false condition and no alternative with nothing done, which is
        // what an `if` whose alternative holds nothing already means.
        val step = ActionStep(
            action = ActionId("flow.if"),
            args = mapOf(conditionKey to PropertyValue.Computed(readsMoreThanZero())),
            branches = mapOf(thenArm to sequenceOf(ActionStep(action = ActionId("nav.back")))),
        )

        assertEquals(
            """
            if (state.count > 0) {
                navigator.back()
            } else {}
            """.trimIndent(),
            body(sequenceOf(step)),
        )
    }

    @Test
    fun `an arm nests its own conditional`() {
        val inner = ActionStep(
            action = ActionId("flow.if"),
            args = mapOf(conditionKey to PropertyValue.Computed(readsMoreThanZero())),
            branches = mapOf(thenArm to sequenceOf(ActionStep(action = ActionId("nav.back")))),
        )
        val step = ActionStep(
            action = ActionId("flow.if"),
            args = mapOf(conditionKey to PropertyValue.Computed(readsMoreThanZero())),
            branches = mapOf(thenArm to sequenceOf(inner)),
        )

        assertEquals(
            """
            if (state.count > 0) {
                if (state.count > 0) {
                    navigator.back()
                } else {}
            } else {}
            """.trimIndent(),
            body(sequenceOf(step)),
        )
    }

    @Test
    fun `a template action fills its placeholders from the step`() {
        val step = ActionStep(
            action = ActionId("analytics.log"),
            args = mapOf(eventKey to PropertyValue.Const(Value.Str("open"))),
        )

        assertEquals(
            """
            package com.example.app.screens

            import com.example.analytics.Analytics

            public fun onClick() {
                Analytics.track("open")
            }

            """.trimIndent(),
            source(sequenceOf(step), specs = intrinsic + analytics),
        )
    }
    @Test
    fun `a template action refuses a placeholder no argument fills`() {
        val step = ActionStep(action = ActionId("analytics.log"))

        assertEquals(
            listOf("codegen.strategy_unsupported"),
            refusals(sequenceOf(step), intrinsic + analytics).map { it.code.value },
        )
    }

    @Test
    fun `an intrinsic action with no emitter is a bug rather than a finding`() {
        val sequence = sequenceOf(ActionStep(action = ActionId("nav.goto")))

        val failure = assertFailsWith<CodegenBug> { body(sequence, specs = intrinsic + goto) }

        assertEquals("Action 'nav.goto' declares intrinsic emission and has no emitter", failure.message)
    }

    @Test
    fun `an action no spec declares reports no binding`() {
        val sequence = sequenceOf(ActionStep(action = ActionId("nav.teleport")))

        assertEquals(listOf("codegen.no_binding"), refusals(sequence).map { it.code.value })
    }

    private fun snackbar(message: String): ActionStep = ActionStep(
        action = ActionId("ui.showSnackbar"),
        args = mapOf(messageKey to PropertyValue.Const(Value.Str(message))),
    )

    private fun hostStep(argument: Value): ActionStep = ActionStep(
        action = ActionId("host.call"),
        args = mapOf(
            nameKey to PropertyValue.Const(Value.Str("submit")),
            argsKey to PropertyValue.Const(Value.ListOf(listOf(argument))),
        ),
    )

    private fun hostReading(expr: Expr): ActionStep = ActionStep(
        action = ActionId("host.call"),
        args = mapOf(
            nameKey to PropertyValue.Const(Value.Str("submit")),
            argsKey to PropertyValue.Computed(Expr.ListLiteral(listOf(expr))),
        ),
    )

    private fun readsMoreThanZero(): Expr =
        Expr.Binary(BinaryOp.Gt, Expr.Ref(RefTarget.State(count)), Expr.Const(Value.Int32(0)))

    private fun declaring(name: String, suspending: Boolean): HostDecl {
        val declaration = HostFunctionDecl(name, emptyList(), null, suspending)
        return HostDecl { asked -> if (asked == name) declaration else null }
    }

    private fun sequenceOf(vararg steps: ActionStep): ActionSequence = ActionSequence(steps.toList())

    /** The whole file the printer writes for one handler, which is where an import shows. */
    private fun source(
        sequence: ActionSequence,
        host: HostDecl = HostDecl { null },
        specs: List<ActionSpec> = intrinsic,
    ): String {
        val found = mutableListOf<Diagnostic>()
        val statements = emitter(specs, host, found).emit(sequence, DiagnosticLocation())
        assertTrue(found.isEmpty(), "got $found")
        return KtPrinter().print(
            KtFile(
                basePackage + ".screens",
                null,
                listOf(KtDeclaration.Function("onClick", emptyList(), null, emptyList(), checkNotNull(statements))),
            ),
        )
    }

    private fun body(
        sequence: ActionSequence,
        host: HostDecl = HostDecl { null },
        specs: List<ActionSpec> = intrinsic,
    ): String = source(sequence, host, specs)
        .substringAfter("public fun onClick() {\n")
        .removeSuffix("}\n")
        .trimIndent()

    /** What [sequence] was refused for; the statements are not what a test reads here. */
    private fun refusals(
        sequence: ActionSequence,
        specs: List<ActionSpec> = intrinsic,
    ): List<Diagnostic> {
        val found = mutableListOf<Diagnostic>()
        emitter(specs, HostDecl { null }, found).emit(sequence, DiagnosticLocation())
        return found
    }

    private fun emitter(
        specs: List<ActionSpec>,
        host: HostDecl,
        found: MutableList<Diagnostic>,
    ): ActionEmitter {
        val builder = RegistryBuilder<ActionId, ActionSpec>()
        for (spec in specs) builder.register(spec.id, spec)
        return ActionEmitter(
            builder.build(),
            reader(),
            document().pages,
            host,
            CodegenOptions(basePackage),
            found,
        )
    }

    /** The reader a screen site gets: the real state emitter answering the reads. */
    private fun reader(): ExprEmitter {
        val functions: Registry<FunctionId, FunctionSpec> = RegistryBuilder<FunctionId, FunctionSpec>().build()
        val state = StateEmitter(document(), CodegenOptions(basePackage), functions)
        return ExprEmitter(functions, StateRead { id -> state.readInScreen(id) })
    }

    private fun document(): ResolvedDocument {
        val pages = mapOf(
            home to ResolvedPage(home, "Home", "home", node(), listOf(held)),
            profile to ResolvedPage(profile, "Profile", "profile", node()),
        )
        return ResolvedDocument(pages, emptyMap(), emptyMap(), ResolvedTheme(null))
    }

    private fun node(): ResolvedNode = ResolvedNode(
        NodeId("n_root"),
        ComponentType("test.Text"),
        emptyMap(),
        emptyList(),
        emptyMap(),
        emptySet(),
    )

    private val held: ResolvedState =
        ResolvedState(StateDecl(count, "count", TypeRef.Int32, Value.Int32(0)), null)

    // The six MVP actions, declared the way their specs declare them: the same ids, the same keys
    // and the same argument shapes, because the module those specs live in is not one this can see.
    private val navigate: ActionSpec = ActionSpec(
        id = ActionId("nav.navigate"),
        metadata = ActionMetadata("Navigate"),
        params = listOf(prop<String>("page", TypeRef.Ref(RefKind.Page), required = true)),
        emit = ActionEmit.Intrinsic,
    )

    private val back: ActionSpec = ActionSpec(
        id = ActionId("nav.back"),
        metadata = ActionMetadata("Back"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    private val setState: ActionSpec = ActionSpec(
        id = ActionId("state.set"),
        metadata = ActionMetadata("Set state"),
        params = emptyList(),
        argRules = listOf(
            ArgRule(targetKey, ArgShape.StateRef, required = true),
            ArgRule(valueKey, ArgShape.TargetValue, required = true),
        ),
        emit = ActionEmit.Intrinsic,
    )

    private val conditional: ActionSpec = ActionSpec(
        id = ActionId("flow.if"),
        metadata = ActionMetadata("If"),
        params = listOf(prop<String>("cond", TypeRef.Bool, required = true)),
        branches = listOf(BranchSpec(thenArm, required = true), BranchSpec(elseArm)),
        emit = ActionEmit.Intrinsic,
    )

    private val callHost: ActionSpec = ActionSpec(
        id = ActionId("host.call"),
        metadata = ActionMetadata("Call host function"),
        params = listOf(prop<String>("name", TypeRef.Str, required = true)),
        argRules = listOf(ArgRule(argsKey, ArgShape.Positional(nameKey))),
        emit = ActionEmit.Intrinsic,
    )

    private val showSnackbar: ActionSpec = ActionSpec(
        id = ActionId("ui.showSnackbar"),
        metadata = ActionMetadata("Show snackbar"),
        params = listOf(prop<String>("message", TypeRef.Str, required = true)),
        emit = ActionEmit.Intrinsic,
    )

    private val intrinsic: List<ActionSpec> =
        listOf(navigate, back, setState, conditional, callHost, showSnackbar)

    /** An action the engine has no emitter for: the shape a spec gets wrong, not a document. */
    private val goto: ActionSpec = ActionSpec(
        id = ActionId("nav.goto"),
        metadata = ActionMetadata("Goto"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    private val analytics: ActionSpec = ActionSpec(
        id = ActionId("analytics.log"),
        metadata = ActionMetadata("Log"),
        params = emptyList(),
        emit = ActionEmit.Template(
            // The placeholder is replaced by the *rendered* literal, so it sits outside the
            // quotes: PLAN.md:606 spells the shape as `Arrangement.spacedBy({spacing})`.
            "Analytics.track({event})",
            listOf(KotlinSymbol("com.example.analytics", "Analytics")),
        ),
    )
}
