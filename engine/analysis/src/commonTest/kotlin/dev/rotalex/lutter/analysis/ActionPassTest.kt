package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCode
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.NodeScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.action.ActionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass 6: a step against its spec, and a handler against the event that raised it.
 *
 * Each rule carries the document that breaks it and the document that does not, because a pass
 * that only proves what it refuses cannot be told apart from one that refuses everything. The
 * negatives are the load-bearing half: `ui.showSnackbar` still declares nothing and no section
 * names its argument keys, so a handler passing it arguments is legal and stays so — while
 * `state.set`'s two keys are declared as shapes, and every key but those two is now refused.
 */
class ActionPassTest {

    private val analyzer: Analyzer<ActionSpec, String, String> = Analyzer(actionSchema())
    private val home: PageId = PageId("p_home")
    private val profile: PageId = PageId("p_profile")
    private val press: EventKey = EventKey("onPress")

    // ---------------------------------------------------------------------------------
    // action.unknown
    // ---------------------------------------------------------------------------------

    @Test
    fun `a step naming an action no schema registers reports action unknown`() {
        val findings = analyzer.analyze(field { handler(step("vendor.track")) }).diagnostics

        assertEquals(listOf(DiagnosticCodes.ActionUnknown.value), findings.map { it.code.value })
        assertEquals(ChangeEvent, findings.single().location.event)
    }

    @Test
    fun `a handler of registered actions reports nothing`() {
        assertClean(
            fieldWith(listOf(count)) {
                handler(
                    write(count.id, constOf(Value.Int32(1))),
                    step("ui.showSnackbar", args = mapOf(PropertyKey("message") to constOf(Value.Str("saved")))),
                    step("nav.back"),
                )
            },
        )
    }

    @Test
    fun `an action declaring no argument accepts a key nothing names`() {
        // The other half of the rule above: a spec that declares nothing closes nothing, because
        // no section names `ui.showSnackbar`'s keys and §11.4 admits the document anyway.
        val message = mapOf(PropertyKey("message") to constOf(Value.Str("saved")))
        assertClean(field { handler(step("ui.showSnackbar", args = message)) })
    }

    // ---------------------------------------------------------------------------------
    // action.arg_invalid
    // ---------------------------------------------------------------------------------

    @Test
    fun `a step omitting an argument the spec requires reports arg invalid`() {
        val finding = findingOf(DiagnosticCodes.ActionArgInvalid, field { handler(step("nav.navigate")) })

        assertEquals("page", finding.args["property"], "the required argument is named in args")
        assertEquals(ChangeEvent, finding.location.event)
    }

    @Test
    fun `a flow if without its required arm reports arg invalid`() {
        val finding = findingOf(DiagnosticCodes.ActionArgInvalid, field { handler(step("flow.if")) })

        assertEquals("then", finding.args["branch"], "the required arm is named in args")
    }

    @Test
    fun `an arm the spec does not name reports arg invalid`() {
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            field {
                handler(
                    step(
                        "flow.if",
                        branches = mapOf(
                            BranchName("then") to sequenceOf(step("nav.back")),
                            BranchName("otherwise") to sequenceOf(step("nav.back")),
                        ),
                    ),
                )
            },
        )

        assertEquals("otherwise", finding.args["branch"])
        assertEquals(
            "otherwise",
            finding.location.path.lastOrNull(),
            "the arm is the path's last step: ${finding.location.path}",
        )
    }

    @Test
    fun `an argument no declaration names reports arg invalid`() {
        // A computed target is not readable, so the arguments beside it cannot be matched against
        // a page and the closed set is the spec's own parameter alone.
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            field {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(
                            PropertyKey("page") to computed(Value.Ref(RefKind.Page, home.value)),
                            PropertyKey("nope") to constOf(Value.Str("x")),
                        ),
                    ),
                )
            },
        )

        assertEquals("nope", finding.args["property"])
    }

    @Test
    fun `a flow if carrying both arms its spec names reports nothing`() {
        assertClean(
            field {
                handler(
                    step(
                        "flow.if",
                        args = mapOf(PropertyKey("cond") to computed(Value.Int32(1))),
                        branches = mapOf(
                            BranchName("then") to sequenceOf(step("nav.back")),
                            BranchName("else") to sequenceOf(step("ui.showSnackbar")),
                        ),
                    ),
                )
            },
        )
    }

    // ---------------------------------------------------------------------------------
    // `host.call`: arguments a declaration types position by position
    // ---------------------------------------------------------------------------------

    @Test
    fun `a host call's arguments the declaration admits reports nothing`() {
        assertClean(callingHost { handler(hostCall(submit = argsOf(Value.Str("a-1")))) })
    }

    @Test
    fun `a host call passing too many arguments reports arg invalid`() {
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            callingHost {
                handler(hostCall(submit = argsOf(Value.Str("a-1"), Value.Str("a-2"))))
            },
        )

        assertEquals("args", finding.args["property"])
    }

    @Test
    fun `a host call passing too few arguments reports arg invalid`() {
        val finding = findingOf(DiagnosticCodes.ActionArgInvalid, callingHost { handler(hostCall(submit = argsOf())) })

        assertEquals("args", finding.args["property"])
    }

    @Test
    fun `an argument of a type the position does not admit reports prop type mismatch`() {
        val finding = findingOf(
            DiagnosticCodes.PropTypeMismatch,
            callingHost { handler(hostCall(submit = argsOf(Value.Int32(7)))) },
        )

        assertEquals("args", finding.args["property"])
    }

    @Test
    fun `a host call naming a function no document declares reports ref dangling`() {
        val finding = findingOf(
            DiagnosticCodes.RefDangling,
            callingHost { handler(hostCall(submit = argsOf(Value.Str("a-1")), callee = "absent")) },
        )

        assertEquals("absent", finding.args["id"])
    }

    @Test
    fun `a host call carrying no argument list to a callee taking one reports arg invalid`() {
        // Was asserted clean, and that was the hole: `args` is optional so a zero-parameter
        // callee need not spell an empty list, which means absence has to be judged as the empty
        // list it is. `submit` takes one argument, so omitting them is wrong here.
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            callingHost { handler(hostCall(submit = null)) },
        )

        assertEquals("args", finding.args["property"])
    }

    @Test
    fun `a host call carrying no argument list to a callee taking none reports nothing`() {
        val document = field { handler(hostCall(submit = null, callee = "ping")) }
            .copy(hostFunctions = listOf(HostFunctionDecl("ping", emptyList(), returns = TypeRef.Bool)))

        assertClean(document)
    }

    // ---------------------------------------------------------------------------------
    // `state.set`: arguments the spec declares as shapes
    // ---------------------------------------------------------------------------------

    @Test
    fun `a write naming a state and a value it accepts reports nothing`() {
        assertClean(fieldWith(listOf(count)) { handler(write(count.id, constOf(Value.Int32(7)))) })
    }

    @Test
    fun `a constant where a state ref is required reports arg invalid`() {
        val target = constOf(Value.Str("s_count"))
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            fieldWith(listOf(count)) {
                handler(stateSet(mapOf(TargetKey to target, ValueKey to constOf(Value.Int32(1)))))
            },
        )

        assertEquals("target", finding.args["property"])
    }

    @Test
    fun `a read of something other than a state reports arg invalid`() {
        // A computed read is not enough: only `RefTarget.State` names one, and this is the read
        // §11.2 introduces an event argument under.
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            fieldWith(listOf(count)) {
                val target = PropertyValue.Computed(Expr.Ref(RefTarget.EventArg("text")))
                handler(stateSet(mapOf(TargetKey to target, ValueKey to constOf(Value.Int32(1)))))
            },
        )

        assertEquals("target", finding.args["property"])
    }

    @Test
    fun `a write that omits the target reports arg invalid`() {
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            fieldWith(listOf(count)) { handler(stateSet(mapOf(ValueKey to constOf(Value.Int32(1))))) },
        )

        assertEquals("target", finding.args["property"])
    }

    @Test
    fun `a write that omits the value reports arg invalid`() {
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            fieldWith(listOf(count)) { handler(stateSet(mapOf(TargetKey to stateRead(count.id)))) },
        )

        assertEquals("value", finding.args["property"])
    }

    @Test
    fun `an argument no rule names reports arg invalid`() {
        val finding = findingOf(
            DiagnosticCodes.ActionArgInvalid,
            fieldWith(listOf(count)) {
                // `value` is present so the unnamed key is the only finding: a required rule left
                // out would report its own absence and the assertion could not tell them apart.
                handler(
                    stateSet(
                        mapOf(
                            UNKNOWN to constOf(Value.Str("x")),
                            TargetKey to stateRead(count.id),
                            ValueKey to constOf(Value.Int32(1)),
                        ),
                    ),
                )
            },
        )

        assertEquals("nope", finding.args["property"])
    }

    @Test
    fun `a value argument read from state is evaluated rather than refused`() {
        // The whole point of the second shape: `value` carries a `PropertyValue`, so an
        // expression over the state it writes is what the check is asked about.
        val written = PropertyValue.Computed(incremented(count.id))
        assertClean(fieldWith(listOf(count)) { handler(write(count.id, written)) })
    }

    // ---------------------------------------------------------------------------------
    // action.state_not_writable
    // ---------------------------------------------------------------------------------

    @Test
    fun `a write to a derived state reports state not writable`() {
        val finding = findingOf(
            DiagnosticCodes.ActionStateNotWritable,
            fieldWith(listOf(count, doubled)) { handler(write(doubled.id, constOf(Value.Int32(1)))) },
        )

        assertEquals("s_doubled", finding.args["state"])
    }

    @Test
    fun `a write of a value the declaration does not accept reports prop type mismatch`() {
        val finding = findingOf(
            DiagnosticCodes.PropTypeMismatch,
            fieldWith(listOf(count)) { handler(write(count.id, constOf(Value.Str("x")))) },
        )

        assertEquals("value", finding.args["property"])
    }

    @Test
    fun `a write to a state no declaration holds reports ref dangling`() {
        val finding = findingOf(
            DiagnosticCodes.RefDangling,
            fieldWith(listOf(count)) { handler(write(GHOST, constOf(Value.Int32(1)))) },
        )

        assertEquals("s_ghost", finding.args["id"])
    }

    // ---------------------------------------------------------------------------------
    // nav.args_mismatch
    // ---------------------------------------------------------------------------------

    @Test
    fun `a route argument the target page does not declare reports nav args mismatch`() {
        // `id` is carried so the required half of §13.1's rule is satisfied and the unknown name
        // is the only thing left to be wrong.
        val finding = findingOf(
            DiagnosticCodes.NavArgsMismatch,
            navigating {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(
                            PropertyKey("page") to toProfile(),
                            PropertyKey("id") to constOf(Value.Str("7")),
                            PropertyKey("nope") to constOf(Value.Str("x")),
                        ),
                    ),
                )
            },
        )

        assertEquals("nope", finding.args["property"])
    }

    @Test
    fun `a route argument the target page requires and the step omits reports nav args mismatch`() {
        val finding = findingOf(
            DiagnosticCodes.NavArgsMismatch,
            navigating {
                handler(step("nav.navigate", args = mapOf(PropertyKey("page") to toProfile())))
            },
        )

        assertEquals("id", finding.args["property"])
        assertEquals(profile.value, finding.args["page"])
    }

    @Test
    fun `a route argument the target page declares reports nothing`() {
        assertClean(
            navigating {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(
                            PropertyKey("page") to toProfile(),
                            PropertyKey("id") to constOf(Value.Str("7")),
                        ),
                    ),
                )
            },
        )
    }

    @Test
    fun `a route argument of the wrong type reports the type codes`() {
        // §13.1's rule is "(name, type, required)", and the type leg is §10.4's: a value that
        // does not fit is the checker's finding, wherever it was found.
        val finding = findingOf(
            DiagnosticCodes.ExprTypeMismatch,
            navigating {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(
                            PropertyKey("page") to toProfile(),
                            PropertyKey("id") to PropertyValue.Computed(sum()),
                        ),
                    ),
                )
            },
        )

        assertEquals("id", finding.args["property"], "the argument is named, not the node")
        assertEquals(ChangeEvent, finding.location.event)
    }

    @Test
    fun `a target page no document declares reports ref dangling`() {
        // §13.1 asks for the target to exist, and no pass reached into a handler, so this is the
        // only place that question is put.
        val finding = findingOf(
            DiagnosticCodes.RefDangling,
            field {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(PropertyKey("page") to constOf(Value.Ref(RefKind.Page, "p_ghost"))),
                    ),
                )
            },
        )

        assertEquals("p_ghost", finding.args["id"])
    }

    @Test
    fun `a page reference of the wrong kind reports ref kind mismatch`() {
        val finding = findingOf(
            DiagnosticCodes.RefKindMismatch,
            field {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(PropertyKey("page") to constOf(Value.Ref(RefKind.Resource, "logo"))),
                    ),
                )
            },
        )

        assertEquals("Page", finding.args["kind"])
    }

    @Test
    fun `a constant argument that does not fit its declaration reports prop type mismatch`() {
        val finding = findingOf(
            DiagnosticCodes.PropTypeMismatch,
            field {
                handler(step("nav.navigate", args = mapOf(PropertyKey("page") to constOf(Value.Int32(3)))))
            },
        )

        assertEquals("page", finding.args["property"])
    }

    // ---------------------------------------------------------------------------------
    // The arguments are typechecked at all, which no earlier pass does
    // ---------------------------------------------------------------------------------

    @Test
    fun `a computed argument is typechecked, which no earlier pass is`() {
        val finding = findingOf(
            DiagnosticCodes.ExprTypeMismatch,
            field { handler(step("nav.navigate", args = mapOf(PropertyKey("page") to computed(Value.Int32(3))))) },
        )

        // The event on the location is what says this came from pass 6: pass 5 never descends
        // into a handler and would have carried no event at all.
        assertEquals(ChangeEvent, finding.location.event)
        assertEquals("n_1", finding.args["node"])
    }

    @Test
    fun `a computed argument that fits its declaration reports nothing`() {
        assertClean(
            field {
                handler(
                    step(
                        "nav.navigate",
                        args = mapOf(PropertyKey("page") to computed(Value.Ref(RefKind.Page, home.value))),
                    ),
                )
            },
        )
    }

    // ---------------------------------------------------------------------------------
    // §11.2's event argument
    // ---------------------------------------------------------------------------------

    @Test
    fun `an event argument inside a handler binds the declared type`() {
        // The argument is only reached through a declaration that gives it a type, so the page's
        // `id: str` is what proves the binding happened — an unresolved one is a refusal, which
        // is what the next test shows.
        assertClean(navigating { handler(step("nav.navigate", args = routeTo(RefTarget.EventArg("text")))) })
    }

    @Test
    fun `an event argument the spec does not declare reports unresolved ref`() {
        val finding = findingOf(
            DiagnosticCodes.ExprUnresolvedRef,
            navigating { handler(step("nav.navigate", args = routeTo(RefTarget.EventArg("other")))) },
        )

        assertEquals(ChangeEvent, finding.location.event)
    }

    // ---------------------------------------------------------------------------------
    // §17.2's location sorts by the event it names
    // ---------------------------------------------------------------------------------

    @Test
    fun `two findings on one node order by the event they are in`() {
        // Everything but the event is equal, the message included, and the sort is stable: nothing
        // but the comparator's event key can put these two in this order.
        val here = sameFinding(DiagnosticLocation(nodeId = NODE, event = ChangeEvent))
        val there = sameFinding(DiagnosticLocation(nodeId = NODE, event = press))

        val sorted = DiagnosticSorter.sort(listOf(there, here))

        assertEquals(listOf(ChangeEvent, press), sorted.map { it.location.event })
    }

    // ---------------------------------------------------------------------------------
    // Documents
    // ---------------------------------------------------------------------------------

    /** One page whose root is a `m3.Field`, the one component here that declares an event. */
    private fun field(block: NodeScope.() -> Unit): UiDocument =
        homeDocument { node(FieldType) { prop("value", Value.Str("x")); block() } }

    /** The same page, declaring the host function a call step names, and nothing else. */
    private fun callingHost(block: NodeScope.() -> Unit): UiDocument =
        field(block).copy(hostFunctions = listOf(SubmitDecl))

    /** The same page, declaring [state], for a write that needs a declaration to be checked against. */
    private fun fieldWith(state: List<StateDecl>, block: NodeScope.() -> Unit): UiDocument =
        homeDocument(state) { node(FieldType) { prop("value", Value.Str("x")); block() } }

    /**
     * Two pages: home holds the handler and profile is the destination a `nav.navigate` names,
     * carrying the one route parameter the navigation rules need to be exercised against.
     */
    private fun navigating(block: NodeScope.() -> Unit): UiDocument = buildDocument("demo") {
        page(name = "Home", route = "home", id = home) {
            node(FieldType) {
                prop("value", Value.Str("x"))
                block()
            }
        }
        page(name = "Profile", route = "profile", id = profile, params = listOf(PROFILE_ID)) {
            node(TextType) { prop("text", Value.Str("hi")) }
        }
    }

    /** One handler on [ChangeEvent] running [steps] in order. */
    private fun NodeScope.handler(vararg steps: ActionStep) {
        event(ChangeEvent, ActionSequence(steps.toList()))
    }

    private fun step(
        action: String,
        args: Map<PropertyKey, PropertyValue> = emptyMap(),
        branches: Map<BranchName, ActionSequence> = emptyMap(),
    ): ActionStep = ActionStep(ActionId(action), args, branches)

    private fun sequenceOf(step: ActionStep): ActionSequence = ActionSequence(listOf(step))

    /** `state.set` with [args], spelled by key so a negative can leave one out. */
    private fun stateSet(args: Map<PropertyKey, PropertyValue>): ActionStep = step("state.set", args = args)

    /**
     * `host.call` naming [callee], carrying [submit] as its positional argument list.
     *
     * Both are nullable so a negative can leave either out: the list is optional, because a
     * declared function may take no parameters, and a step may name a function it passes nothing to.
     */
    private fun hostCall(submit: PropertyValue?, callee: String = "submit"): ActionStep {
        val args = mutableMapOf<PropertyKey, PropertyValue>(HostNameKey to constOf(Value.Str(callee)))
        if (submit != null) args[HostArgsKey] = submit
        return step("host.call", args = args)
    }

    /** The `Value.ListOf` spelling of a positional list, which is what a literal argument is. */
    private fun argsOf(vararg values: Value): PropertyValue = constOf(Value.ListOf(values.toList()))

    /** A well formed write of [value] into [target]. */
    private fun write(target: StateId, value: PropertyValue): ActionStep =
        stateSet(mapOf(TargetKey to stateRead(target), ValueKey to value))

    /** The computed read a target argument is written as, which is what names a state. */
    private fun stateRead(id: StateId): PropertyValue = PropertyValue.Computed(Expr.Ref(RefTarget.State(id)))

    /** `state.count + 1`: the expression §12.3's own example passes as the value written. */
    private fun incremented(id: StateId): Expr =
        Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(id)), Expr.Const(Value.Int32(1)))

    private fun toProfile(): PropertyValue = constOf(Value.Ref(RefKind.Page, profile.value))

    /** The route to profile carrying [target] as its `id`. */
    private fun routeTo(target: RefTarget): Map<PropertyKey, PropertyValue> = mapOf(
        PropertyKey("page") to toProfile(),
        PropertyKey("id") to PropertyValue.Computed(Expr.Ref(target)),
    )

    // ---------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------

    /** The single finding carrying [code]; more than one means the pass cannot be read. */
    private fun findingOf(code: DiagnosticCode, document: UiDocument): Diagnostic {
        val findings = analyzer.analyze(document).diagnostics
        val matching = findings.filter { it.code == code }
        assertEquals(1, matching.size, "expected one '$code' among ${findings.map { it.code.value }}")
        return matching.single()
    }

    /**
     * No findings, and a resolved document behind them.
     *
     * The second half is the stronger claim: a finding-free document that failed to lower would
     * mean a pass had thrown rather than reported.
     */
    private fun assertClean(document: UiDocument) {
        val result = analyzer.analyze(document)
        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics}")
        assertNotNull(result.resolved, "a clean document did not resolve")
    }

    private fun sameFinding(at: DiagnosticLocation): Diagnostic = Diagnostic(
        Severity.Error,
        DiagnosticCodes.ActionArgInvalid,
        at,
        "Action 'nav.navigate' requires argument 'page'",
        mapOf("node" to NODE.value),
    )

    private fun computed(value: Value): PropertyValue = PropertyValue.Computed(Expr.Const(value))

    private fun sum(): Expr = Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(1)))

    private companion object {
        val NODE: NodeId = NodeId("n_1")
        val PROFILE_ID: ParamDecl = ParamDecl(ParamName("id"), TypeRef.Str)

        /** Held, so a write to it is legal and only its value can be wrong. */
        val COUNT: StateId = StateId("s_count")

        /** Derived, so §12.3's "not derived" rule has something to refuse. */
        val DOUBLED: StateId = StateId("s_doubled")

        /** A name no declaration in any fixture holds. */
        val GHOST: StateId = StateId("s_ghost")

        /** A key no spec declares. */
        val UNKNOWN: PropertyKey = PropertyKey("nope")

        val count: StateDecl = StateDecl(COUNT, "count", TypeRef.Int32, Value.Int32(0))

        val doubled: StateDecl = StateDecl(
            DOUBLED, "doubled", TypeRef.Int32,
            derived = Expr.Binary(BinaryOp.Add, Expr.Ref(RefTarget.State(COUNT)), Expr.Const(Value.Int32(1))),
        )
    }
}