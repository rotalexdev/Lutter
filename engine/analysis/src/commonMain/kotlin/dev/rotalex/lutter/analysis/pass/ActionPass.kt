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
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
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
 * **A spec that declares no parameter closes nothing.** §11.3 makes `params` the whole of what
 * an action takes, and `state.set`, `host.call` and `ui.showSnackbar` declare none, so no
 * section names their argument keys and ruling on them would refuse the arguments §12.3's own
 * example passes. A navigation argument is the target page's `ParamDecl` instead (§13.1).
 *
 * §17.3's fourth action row, `action.state_not_writable`, is not here and cannot be: no field
 * anywhere says which argument of a step is the state it writes, so §12.3's "writable, not
 * derived" has nothing to read. Reaching it needs §11.3 amended rather than a guess in this pass.
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
            val base = scopeOf(document, index, id) ?: continue
            val walk = Walk(document, node, schema, base, checker, found)
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
     * Every argument against the declaration that names it: the spec's own parameter, or the
     * target page's when the step navigates. A key neither declares is `nav.args_mismatch` where
     * a page is known and `arg_invalid` otherwise — §13.1 owns a route, §11.3 owns the rest.
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
        val route = page?.params?.associate { it.name.value to it.type } ?: emptyMap()
        for (param in spec.params) {
            if (!param.required || param.key in step.args) continue
            found += refusal(
                at.copy(property = param.key), event, DiagnosticCodes.ActionArgInvalid,
                "Action '${step.action}' requires argument '${param.key.value}'",
                mapOf("action" to step.action.value, "property" to param.key.value),
            )
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
            val typeRef = params[key] ?: route[key.value]
            if (typeRef == null) {
                if (page != null) {
                    found += refusal(
                        here, event, DiagnosticCodes.NavArgsMismatch,
                        "Argument '${key.value}' is not a parameter of page '${page.id}'",
                        mapOf("page" to page.id.value, "property" to key.value),
                    )
                } else if (spec.params.isNotEmpty()) {
                    found += refusal(
                        here, event, DiagnosticCodes.ActionArgInvalid,
                        "Action '${step.action}' has no argument '${key.value}'",
                        mapOf("action" to step.action.value, "property" to key.value),
                    )
                }
                continue
            }
            checkArgument(key, actual, typeRef, here, event, scope)
        }
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