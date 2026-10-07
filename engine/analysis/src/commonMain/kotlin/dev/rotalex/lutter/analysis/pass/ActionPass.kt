package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCode
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.typing.ExprScope
import dev.rotalex.lutter.analysis.typing.TypeChecker
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * Pass 6: every step of every handler against the `ActionSpec` that describes it (§17.1).
 *
 * What no earlier pass reaches: whether the action is registered, whether the step carries what
 * its spec requires and the arms it names, and whether each argument fits — a constant against
 * the declared type, a computed one through the §10.4 checker, which nothing in the pipeline
 * runs over a handler today.
 *
 * **Two lists of arguments, and the gap between them is closed by shape.** §11.3 makes `params`
 * the typed half; only `ui.showSnackbar` declares none, so a key no declaration names is a finding
 * only where the spec declares something at all. `state.set`'s two keys are in `argRules` because
 * the target is a read of a state and the value is typed by the declaration that read names; a
 * host call's `args` is in there for the same reason, typed position by position by the
 * declaration its `name` names. A navigation argument is the target page's `ParamDecl` (§13.1).
 *
 * §17.3's fourth action row, `action.state_not_writable`, is here: the rule it enforces is
 * §12.3's "writable, not derived, type-compatible", and a spec's `StateRef` rule is what names
 * the argument that write targets.
 */
internal class ActionPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) : AnalysisPass {
    override val name: String = "action"
    override val blocking: Boolean = false

    override fun run(document: UiDocument): List<Diagnostic> {
        val found = mutableListOf<Diagnostic>()
        val index = DocumentIndex(document)
        val checker = TypeChecker(schema, document.dataModels)
        for (id in document.nodes.ids()) {
            val node = document.nodes[id] ?: continue
            val owned = ownerDeclsOf(document, index, id) ?: continue
            val base = scopeOf(document, owned)
            val walk = Walk(document, node, schema, base, statesOf(document, owned.state), checker, found)
            for ((event, sequence) in node.events) walk.handler(sequence, event, emptyList())
        }
        return found
    }
}

/**
 * One node's handlers.
 *
 * A class rather than a dozen parameters per rule: every finding below is about a step, and the
 * node, the event and the scope are the same for all of them.
 */
private class Walk(
    private val document: UiDocument,
    private val node: Node,
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
    private val base: ExprScope,
    private val states: Map<StateId, StateDecl>,
    private val checker: TypeChecker,
    private val found: MutableList<Diagnostic>,
) {

    /**
     * One sequence: a whole handler, or an arm inside one.
     *
     * [path] is the branch chain walked to reach it, which is what tells two findings in the same
     * handler apart: `then/step/0` is a step and `else/step/0` is another one.
     */
    fun handler(sequence: ActionSequence, event: EventKey, path: List<String>) {
        val scope = base.copy(eventArgs = eventArgs(event))
        for ((position, step) in sequence.steps.withIndex()) {
            visit(step, event, scope, path + "step" + position.toString())
            for ((branch, nested) in step.branches) handler(nested, event, path + branch.value)
        }
    }

    /** One step: registered, shaped as its spec says, and its arguments typechecked. */
    private fun visit(step: ActionStep, event: EventKey, scope: ExprScope, path: List<String>) {
        val at = DiagnosticLocation(nodeId = node.id, event = event, path = path)
        // The cast is total, as it is for a function in the checker: an assembly binding a stub
        // in the actions slot has no spec here, and a step nothing describes is an unknown action.
        val spec = schema.actions[step.action] as? ActionSpec
        if (spec == null) {
            found += refusal(
                at, event, DiagnosticCodes.ActionUnknown,
                "Action '${step.action}' is not declared by the schema",
                mapOf("action" to step.action.value),
            )
            return
        }
        checkBranches(step, spec, at, event)
        checkArgs(step, spec, targetPage(step, spec, at, event), at, event, scope)
    }

    /**
     * §11.3's arms, both ways: one the spec requires and the step omits, and one the step
     * carries that the spec does not name. Both are the step's shape rather than a value's, which
     * is why they report as `arg_invalid` — the code pass 3 already gives a modifier whose
     * required argument is missing.
     */
    private fun checkBranches(
        step: ActionStep,
        spec: ActionSpec,
        at: DiagnosticLocation,
        event: EventKey,
    ) {
        val declared = spec.branches.map { it.name }
        for (branch in spec.branches) {
            if (!branch.required || branch.name in step.branches) continue
            found += refusal(
                at, event, DiagnosticCodes.ActionArgInvalid,
                "Action '${step.action}' requires branch '${branch.name.value}'",
                mapOf("action" to step.action.value, "branch" to branch.name.value),
            )
        }
        for (name in step.branches.keys) {
            if (name in declared) continue
            found += refusal(
                at.copy(path = at.path + name.value), event, DiagnosticCodes.ActionArgInvalid,
                "Action '${step.action}' has no branch '${name.value}'",
                mapOf("action" to step.action.value, "branch" to name.value),
            )
        }
    }

    /**
     * The page a navigation step arrives at, taken from the parameter whose declared type is a
     * page reference — which is how a navigation action is recognised, rather than by its id.
     *
     * Null for an action with no page parameter, for a step that omits it, and for a computed
     * one: a target has to be readable before the arguments beside it can be matched against it.
     */
    private fun targetPage(
        step: ActionStep,
        spec: ActionSpec,
        at: DiagnosticLocation,
        event: EventKey,
    ): Page? {
        val destination = spec.params.firstOrNull { param ->
            val declared = param.type
            declared is TypeRef.Ref && declared.kind == RefKind.Page
        } ?: return null
        val constant = (step.args[destination.key] as? PropertyValue.Const)?.value ?: return null
        val ref = constant as? Value.Ref ?: return null
        // A reference of the wrong kind is pass 4's `ref.kind_mismatch`, raised by the type check.
        if (ref.kind != RefKind.Page) return null
        val target = document.pages.entries.firstOrNull { it.key.value == ref.id }
        if (target == null) {
            // Ids are compared as strings and never rebuilt from a document, so a malformed one
            // is a finding rather than a throw out of the pass: `ReferenceIndex`'s rule as well.
            found += refusal(
                at.copy(property = destination.key), event, DiagnosticCodes.RefDangling,
                "Reference to page '${ref.id}' names nothing in the document",
                mapOf("kind" to RefKind.Page.name, "id" to ref.id, "property" to destination.key.value),
            )
            return null
        }
        return target.value
    }

    /**
     * Every argument against the declaration that names it: the spec's own parameter, the spec's
     * own rule, or the target page's when the step navigates. A key none declares is
     * `nav.args_mismatch` where a page is known and `arg_invalid` otherwise — §13.1 owns a
     * route, §11.3 owns the rest.
     */
    private fun checkArgs(
        step: ActionStep,
        spec: ActionSpec,
        page: Page?,
        at: DiagnosticLocation,
        event: EventKey,
        scope: ExprScope,
    ) {
        val params = spec.params.associate { it.key to it.type }
        val rules = spec.argRules.associateBy { it.key }
        val route = page?.params?.associate { it.name.value to it.type } ?: emptyMap()
        val target = writeTarget(step, spec, at, event)
        val host = hostFunction(step, spec, at, event)
        for (param in spec.params) {
            if (!param.required || param.key in step.args) continue
            found += refusal(
                at.copy(property = param.key), event, DiagnosticCodes.ActionArgInvalid,
                "Action '${step.action}' requires argument '${param.key.value}'",
                mapOf("action" to step.action.value, "property" to param.key.value),
            )
        }
        for (rule in spec.argRules) {
            if (rule.key !in step.args) {
                if (rule.required) {
                    found += refusal(
                        at.copy(property = rule.key), event, DiagnosticCodes.ActionArgInvalid,
                        "Action '${step.action}' requires argument '${rule.key.value}'",
                        mapOf("action" to step.action.value, "property" to rule.key.value),
                    )
                } else if (rule.shape is ArgShape.Positional) {
                    // An omitted positional list is an empty one, and only the callee's arity
                    // says whether that is right. A zero-parameter function wants it absent; one
                    // that takes arguments is a finding here rather than a failure surfacing from
                    // inside the host, which is the only place a missing argument is otherwise seen.
                    checkPositional(
                        step, rule.key, PropertyValue.Const(Value.ListOf(emptyList())),
                        host, at, event, scope,
                    )
                }
                continue
            }
        }
        if (page != null) {
            for (param in page.params) {
                if (!param.required || PropertyKey(param.name.value) in step.args) continue
                found += refusal(
                    at, event, DiagnosticCodes.NavArgsMismatch,
                    "Page '${page.id}' requires argument '${param.name.value}'",
                    mapOf("page" to page.id.value, "property" to param.name.value),
                )
            }
        }
        for ((key, actual) in step.args) {
            val here = at.copy(property = key)
            val rule = rules[key]
            val typeRef = params[key] ?: route[key.value]
            when {
                rule != null -> checkRule(step, rule, actual, target, host, here, event, scope)
                typeRef != null -> checkArgument(key, actual, typeRef, here, event, scope)
                // A spec that declares nothing closes nothing: no section names `ui.showSnackbar`'s
                // keys, so refusing one would refuse a document §11.4 admits.
                page != null -> found += refusal(
                    here, event, DiagnosticCodes.NavArgsMismatch,
                    "Argument '${key.value}' is not a parameter of page '${page.id}'",
                    mapOf("page" to page.id.value, "property" to key.value),
                )

                spec.params.isNotEmpty() || spec.argRules.isNotEmpty() -> found += refusal(
                    here, event, DiagnosticCodes.ActionArgInvalid,
                    "Action '${step.action}' has no argument '${key.value}'",
                    mapOf("action" to step.action.value, "property" to key.value),
                )
            }
        }
    }

    /**
     * The declaration a step writes, read off the spec rather than off the action's id, so a
     * plugin action declaring the same shape is checked the same way.
     *
     * Null for a spec with no state-naming rule, for a step that omits it or passes something
     * that names no state, and for one whose id no declaration holds — which is `ref.dangling`,
     * the answer a page reference to a page that is not there gets.
     */
    private fun writeTarget(
        step: ActionStep,
        spec: ActionSpec,
        at: DiagnosticLocation,
        event: EventKey,
    ): StateDecl? {
        val key = spec.argRules.firstOrNull { it.shape == ArgShape.StateRef }?.key ?: return null
        val id = stateNamed(step.args[key]) ?: return null
        val decl = states[id]
        if (decl != null) return decl

        found += refusal(
            at.copy(property = key), event, DiagnosticCodes.RefDangling,
            "Reference to state '${id.value}' names nothing in scope",
            mapOf("kind" to "State", "id" to id.value, "property" to key.value),
        )
        return null
    }

    /**
     * The state [actual] names, or null when it names none.
     *
     * The only shape tried, for the reason `IntrinsicHandlers` reads the same one: a document
     * constant is one of §9.1's four reference kinds and none of them is a state.
     */
    private fun stateNamed(actual: PropertyValue?): StateId? {
        val expr = (actual as? PropertyValue.Computed)?.expr ?: return null
        return ((expr as? Expr.Ref)?.target as? RefTarget.State)?.id
    }

    /** One argument against the rule that names it, which no `TypeRef` could have typed. */
    private fun checkRule(
        step: ActionStep,
        rule: ArgRule,
        actual: PropertyValue,
        target: StateDecl?,
        host: HostFunctionDecl?,
        at: DiagnosticLocation,
        event: EventKey,
        scope: ExprScope,
    ) {
        when (val shape = rule.shape) {
            ArgShape.StateRef -> {
                if (stateNamed(actual) == null) {
                    found += refusal(
                        at, event, DiagnosticCodes.ActionArgInvalid,
                        "Argument '${rule.key.value}' of action '${step.action}' must name a state",
                        mapOf("action" to step.action.value, "property" to rule.key.value),
                    )
                }
            }

            ArgShape.TargetValue -> checkWrittenValue(rule.key, actual, target, at, event, scope)

            is ArgShape.Positional -> checkPositional(step, rule.key, actual, host, at, event, scope)
        }
    }

    /**
     * §11.6's declared parameter list, against the arguments the step carries.
     *
     * The declaration is a document field, so this is the only place one is read: the interpreter
     * reaches `HostFunctions`, a name-to-lambda map, and no `HostFunctionDecl` ever reaches a
     * running step.
     *
     * A list written any other way is not checked. A computed read of a list-valued state is a
     * legal document, and pass 6 reports only what it can prove — the same answer
     * [checkWrittenValue] gives when no declaration is in reach.
     */
    private fun checkPositional(
        step: ActionStep,
        key: PropertyKey,
        actual: PropertyValue,
        host: HostFunctionDecl?,
        at: DiagnosticLocation,
        event: EventKey,
        scope: ExprScope,
    ) {
        val decl = host ?: return
        val items = listItems(actual) ?: return

        if (items.size != decl.params.size) {
            found += refusal(
                at.copy(property = key), event, DiagnosticCodes.ActionArgInvalid,
                "Host function '${decl.name}' takes ${decl.params.size} arguments, not ${items.size}",
                mapOf(
                    "action" to step.action.value,
                    "property" to key.value,
                    "function" to decl.name,
                    "expected" to decl.params.size.toString(),
                    "found" to items.size.toString(),
                ),
            )
            return
        }

        for ((position, item) in items.withIndex()) {
            checkArgument(key, item, decl.params[position].type, at, event, scope)
        }
    }

    /**
     * The declaration a positional rule is checked against, resolved once per step.
     *
     * The callee is a second argument, so it is read off the shape rather than off the action's
     * id: a plugin action declaring the same two arguments is checked the same way, which is the
     * rule [writeTarget] follows for the write target.
     *
     * A name no document declares is `ref.dangling`, the answer a page reference to a page that is
     * not there gets: nothing implements the name, so a step calling it can only fail at run time.
     * A name this pass cannot read statically — a computed one that is not a literal — leaves
     * nothing to check, exactly as an unreadable navigation target does.
     */
    private fun hostFunction(
        step: ActionStep,
        spec: ActionSpec,
        at: DiagnosticLocation,
        event: EventKey,
    ): HostFunctionDecl? {
        val callee = when (val shape = spec.argRules.firstOrNull { it.shape is ArgShape.Positional }?.shape) {
            is ArgShape.Positional -> shape.callee
            else -> return null
        }

        val name = when (val written = step.args[callee]) {
            is PropertyValue.Const -> written.value
            is PropertyValue.Computed -> (written.expr as? Expr.Const)?.value
            null -> null
        } as? Value.Str ?: return null

        val decl = document.hostFunctions.firstOrNull { it.name == name.v }
        if (decl != null) return decl

        found += refusal(
            at.copy(property = callee), event, DiagnosticCodes.RefDangling,
            "Host function '${name.v}' is not declared by the document",
            mapOf("kind" to "HostFunction", "id" to name.v, "property" to callee.value),
        )
        return null
    }

    /**
     * The elements a positional argument spells out, as the value each position would hold.
     *
     * Null for anything this pass cannot read whole, which is a silent gap rather than a finding:
     * see [checkPositional].
     */
    private fun listItems(actual: PropertyValue): List<PropertyValue>? = when (actual) {
        is PropertyValue.Const -> (actual.value as? Value.ListOf)?.items?.map { PropertyValue.Const(it) }
        is PropertyValue.Computed ->
            (actual.expr as? Expr.ListLiteral)?.items?.map { PropertyValue.Computed(it) }
    }

    /**
     * §12.3's write rule, both halves: a derived declaration is computed on read and has nowhere
     * to land a write, and the written value is typed by the declaration rather than by the spec.
     *
     * With no declaration to read there is no type either, so nothing is checked — the rule that
     * refused the target has already said which of the two faults it is.
     */
    private fun checkWrittenValue(
        key: PropertyKey,
        actual: PropertyValue,
        target: StateDecl?,
        at: DiagnosticLocation,
        event: EventKey,
        scope: ExprScope,
    ) {
        val decl = target ?: return
        if (decl.derived != null) {
            found += refusal(
                at, event, DiagnosticCodes.ActionStateNotWritable,
                "State '${decl.id.value}' is derived and cannot be written",
                mapOf("property" to key.value, "state" to decl.id.value),
            )
        }
        checkArgument(key, actual, decl.type, at, event, scope)
    }

    /**
     * One argument against [typeRef], split the way pass 3 and pass 5 split a property: a
     * constant is compared here, a computed one goes to the checker whose verdicts are §10.4's.
     *
     * So a value of the wrong type reports the same codes from a handler as from a property, and
     * the message differs only in the word for the position.
     */
    private fun checkArgument(
        key: PropertyKey,
        actual: PropertyValue,
        typeRef: TypeRef,
        at: DiagnosticLocation,
        event: EventKey,
        scope: ExprScope,
    ) {
        when (actual) {
            is PropertyValue.Const -> checkConstant(key, actual.value, typeRef, at, event)
            is PropertyValue.Computed ->
                found += checker.check(actual.expr, typeRef, scope, at).diagnostics
        }
    }

    /**
     * A constant argument, with pass 3's two rules kept apart: a reference of the wrong kind is
     * a kind error rather than a type one, which is what pass 4 reports for a property.
     */
    private fun checkConstant(
        key: PropertyKey,
        value: Value,
        typeRef: TypeRef,
        at: DiagnosticLocation,
        event: EventKey,
    ) {
        val expected = typeRef as? TypeRef.Ref
        if (expected != null && value is Value.Ref && value.kind != expected.kind) {
            found += refusal(
                at, event, DiagnosticCodes.RefKindMismatch,
                "Reference '${key.value}' expects kind '${expected.kind}' but found '${value.kind}'",
                mapOf("property" to key.value, "kind" to expected.kind.name, "found" to value.kind.name),
            )
            return
        }
        if (!acceptsValue(typeRef, value)) {
            found += refusal(
                at, event, DiagnosticCodes.PropTypeMismatch,
                "Argument '${key.value}' expects ${labelOf(typeRef)} but found ${labelOf(value)}",
                mapOf("property" to key.value, "expected" to labelOf(typeRef), "found" to labelOf(value)),
            )
        }
    }

    /**
     * The handler's arguments, from the component's own `EventSpec` (§11.2), which is the only
     * place one is introduced.
     *
     * A handler on an event the spec does not declare binds none, and that is not a finding here:
     * no §17.3 row covers an unknown event, and every argument naming one is refused by the
     * checker as unresolved rather than quietly given a type.
     */
    private fun eventArgs(event: EventKey): Map<String, TypeRef> {
        val declared = schema.components[node.type]?.events?.firstOrNull { it.key == event }
        return declared?.args?.associate { it.name to it.type } ?: emptyMap()
    }

    /** One finding, rendered the way the other passes render one, plus the handler it is in. */
    private fun refusal(
        at: DiagnosticLocation,
        event: EventKey,
        code: DiagnosticCode,
        detail: String,
        args: Map<String, String>,
    ): Diagnostic = Diagnostic(
        Severity.Error,
        code,
        at,
        "$detail (node '${node.id}', event '$event')",
        mapOf("node" to node.id.value, "event" to event.value) + args,
    )
}