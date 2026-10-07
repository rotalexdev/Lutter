package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCode
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape
import dev.rotalex.lutter.schema.action.BranchSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.registry.Registry

/**
 * The document's host-function declarations, asked for the one fact a step's call cannot carry.
 *
 * A lookup rather than a list because the declarations are a document field and the resolved
 * form codegen reads does not carry them, so whoever holds the document supplies this.
 */
public fun interface HostDecl {

    /** What [name] declares, or null when no declaration is in reach. */
    public fun declaration(name: String): HostFunctionDecl?
}

/**
 * One action sequence as the statements of a lambda body.
 *
 * The statements run against the receivers the runtime's action environment names — `navigator`,
 * `snackbars`, `host`, `scope` — so the emitted handler reads like the handler that interprets
 * the same sequence. Nothing declares them here: the unit that wires events into a component owns
 * the parameters that supply them.
 *
 * A domain fault is a finding in [found] and a null; only an engine or spec gap throws.
 */
public class ActionEmitter(
    private val actions: Registry<ActionId, *>,
    private val expressions: ExprEmitter,
    private val pages: Map<PageId, ResolvedPage>,
    private val host: HostDecl,
    private val options: CodegenOptions,
    private val found: MutableList<Diagnostic>,
) {

    /**
     * What a `Template` action's pattern needs imported, for a caller that wants it.
     *
     * Dead: the pattern's symbols ride out on the statement as a `Snippet`, and `KtPrinter` finds
     * a symbol anywhere in the tree including a lambda body, so nothing needs this list. Kept
     * because dropping a public member means regenerating the module's ABI dumps, which this unit
     * does not do.
     */
    public val imports: MutableList<KotlinSymbol> = mutableListOf()

    // The receivers this emitter's own statements ran against, and nothing else: a `Template`
    // action's pattern is text and cannot name one the engine does not read.
    private val reads: MutableSet<ActionReceiver> = mutableSetOf()

    private val route: KotlinSymbol = KotlinSymbol(options.basePackage, "Route")

    private val launch: KotlinSymbol = KotlinSymbol("kotlinx.coroutines", "launch")

    /**
     * Every receiver the sequences emitted since the last drain ran against, emptied by reading.
     *
     * Drained rather than accumulated because one screen's handlers say nothing about the next
     * screen's signature, and both files come out of the same document.
     */
    internal fun drainReads(): Set<ActionReceiver> {
        val drained: Set<ActionReceiver> = reads.toSet()
        reads.clear()
        return drained
    }

    /**
     * [sequence] as the statements a handler body is made of, or null once a finding is recorded.
     *
     * A sequence reaching a suspending call is wrapped whole: the wrapper is what makes the body
     * a coroutine, and a step inside it that does not suspend is legal there while the same step
     * outside it would not compile.
     */
    public fun emit(sequence: ActionSequence, at: DiagnosticLocation): List<KtStmt>? {
        val statements: MutableList<KtStmt> = mutableListOf()
        var suspends: Boolean = false
        for ((position, step) in sequence.steps.withIndex()) {
            val here: DiagnosticLocation = at.copy(path = at.path + "step" + position.toString())
            val emitted: Emitted = stepOf(step, here) ?: return null
            statements += emitted.statement
            suspends = suspends || emitted.suspends
        }
        return if (suspends) listOf(launched(statements)) else statements
    }

    // The spec says how a step emits: its own pattern, or the engine's table for the id.
    private fun stepOf(step: ActionStep, at: DiagnosticLocation): Emitted? {
        val spec: ActionSpec? = actions[step.action] as? ActionSpec
        if (spec == null) {
            refuse(DiagnosticCodes.CodegenNoBinding, at, "No ActionSpec for '" + step.action.value + "'")
            return null
        }
        return when (val emit = spec.emit) {
            is ActionEmit.Template -> templated(step, emit, at)
            ActionEmit.Intrinsic -> intrinsic(step, spec, at)
        }
    }

    /**
     * The engine's own emission, keyed by id rather than branched over.
     *
     * A spec claiming engine-owned emission that finds no emitter is a gap on this side of the
     * line, not a document fault, so it throws: the coverage check refuses such a schema when the
     * generator is built, which is what keeps the throw unreachable from a document.
     */
    private fun intrinsic(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val kind: ActionKind = IntrinsicActions.kindOf(step.action) ?: throw CodegenBug(
            "Action '" + step.action.value + "' declares intrinsic emission and has no emitter",
        )
        return when (kind) {
            ActionKind.Navigate -> navigate(step, spec, at)
            ActionKind.Back -> Emitted(KtStmt.Expr(on(ActionReceiver.Navigator, "back")))
            ActionKind.WriteState -> writeState(step, spec, at)
            ActionKind.Conditional -> conditional(step, spec, at)
            ActionKind.CallHost -> callHost(step, spec, at)
            ActionKind.ShowSnackbar -> snackbar(step, spec, at)
        }
    }

    /**
     * `navigator.navigate(Route.Profile)`: the page's own name on the generated route entry.
     *
     * Route arguments have nowhere to go, because the generated navigator takes a route alone,
     * so a step carrying them is refused rather than emitted into a call that cannot exist.
     */
    private fun navigate(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val key: PropertyKey = pageArgument(spec, at) ?: return null
        val reference: Value.Ref = pageReference(step, key, at) ?: return null
        val page: ResolvedPage = namedPage(reference, at) ?: return null
        val carried: List<PropertyKey> = step.args.keys.filter { it != key }
        if (carried.isNotEmpty()) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Page '" + page.name + "' is given route arguments, and the generated navigator takes none",
            )
            return null
        }
        val destination: KtExpr = KtExpr.Ref(KtSymbolRef(route, page.name))
        return Emitted(KtStmt.Expr(on(ActionReceiver.Navigator, "navigate", listOf(KtArg(null, destination)))))
    }

    /** The key of the parameter whose declared type is a page reference, which is what says so. */
    private fun pageArgument(spec: ActionSpec, at: DiagnosticLocation): PropertyKey? {
        val key: PropertyKey? = spec.params
            .firstOrNull { param -> (param.type as? TypeRef.Ref)?.kind == RefKind.Page }
            ?.key
        if (key == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Action '" + spec.id.value + "' declares no page argument",
            )
        }
        return key
    }

    private fun pageReference(step: ActionStep, key: PropertyKey, at: DiagnosticLocation): Value.Ref? {
        val written: PropertyValue? = step.args[key]
        if (written == null) {
            refuseMissing(step, key, at)
            return null
        }
        val reference: Value.Ref? = written.constant() as? Value.Ref
        if (reference == null) {
            refuse(DiagnosticCodes.ActionArgInvalid, at, "Argument '" + key.value + "' is not a reference")
            return null
        }
        if (reference.kind != RefKind.Page) {
            refuse(
                DiagnosticCodes.ActionArgInvalid,
                at,
                "Argument '" + key.value + "' is a " + reference.kind.name.lowercase() + " reference",
            )
            return null
        }
        return reference
    }

    private fun namedPage(reference: Value.Ref, at: DiagnosticLocation): ResolvedPage? {
        // Analysis compares ids as bare strings, so a document can carry one the model refuses to
        // name, and its `require` must not be the way this step reports.
        val page: ResolvedPage? = runCatching { PageId(reference.id) }.getOrNull()?.let { pages[it] }
        if (page == null) {
            refuse(
                DiagnosticCodes.RefDangling,
                at,
                "Page '" + reference.id + "' names nothing in the document",
            )
        }
        return page
    }

    /**
     * `state.count = state.count + 1`: the target read as a read, the value as an expression.
     *
     * The write target arrives as an expression that reads the state, and emitting it through the
     * expression reader is what makes it spell as the same access every other read of that
     * declaration spells — `state.count` in a screen, bare in a component.
     */
    private fun writeState(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val target: PropertyKey = ruleArgument(spec, ArgShape.StateRef, at) ?: return null
        val value: PropertyKey = ruleArgument(spec, ArgShape.TargetValue, at) ?: return null
        val written: KtExpr = argument(step, target, at) ?: return null
        val writtenValue: KtExpr = argument(step, value, at) ?: return null
        return Emitted(KtStmt.Assign(written, writtenValue))
    }

    /**
     * `if (cond) { … } else { … }`, the arms in the order the spec declares them.
     *
     * An arm the step does not carry is an empty block, which is what the runtime answers with a
     * missing alternative and a false condition: the condition chose nothing and nothing follows.
     */
    private fun conditional(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val arms: List<BranchSpec> = spec.branches
        if (arms.size != 2) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "An action with " + arms.size + " arms has no `if` to become",
            )
            return null
        }
        val key: PropertyKey = conditionArgument(spec, at) ?: return null
        val condition: KtExpr = argument(step, key, at) ?: return null
        val consequent: List<KtStmt> = arm(step, arms[0], at) ?: return null
        val alternative: List<KtStmt> = arm(step, arms[1], at) ?: return null
        return Emitted(
            KtStmt.Expr(
                KtExpr.IfElse(
                    condition,
                    KtExpr.Lambda(emptyList(), consequent),
                    KtExpr.Lambda(emptyList(), alternative),
                ),
            ),
        )
    }

    // The one argument whose declared type says what it is for, so the key is read rather than
    // chosen: a plugin conditional is emitted by the same reading.
    private fun conditionArgument(spec: ActionSpec, at: DiagnosticLocation): PropertyKey? {
        val key: PropertyKey? = spec.params.firstOrNull { it.type == TypeRef.Bool }?.key
        if (key == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Action '" + spec.id.value + "' declares no condition",
            )
        }
        return key
    }

    private fun arm(step: ActionStep, branch: BranchSpec, at: DiagnosticLocation): List<KtStmt>? {
        val nested: ActionSequence = step.branches[branch.name] ?: return emptyList()
        return emit(nested, at.copy(path = at.path + branch.name.value))
    }

    /**
     * `host.submit("42")`, wrapped by its caller when the declaration says it suspends.
     *
     * The name is read rather than evaluated because a generated host member is named by the
     * document, and no declaration in reach means nothing says whether the call may suspend.
     */
    private fun callHost(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val rule: ArgRule = positionalRule(spec, at) ?: return null
        val list: ArgShape.Positional = rule.shape as ArgShape.Positional
        val name: String = calleeName(step, list, at) ?: return null
        val declaration: HostFunctionDecl = declarationOf(name, at) ?: return null
        // The rule's own key, not the shape's `callee`: the callee names the function, this names
        // the list of arguments handed to it.
        val arguments: List<KtArg> = argumentsOf(step, rule.key, at) ?: return null
        return Emitted(KtStmt.Expr(on(ActionReceiver.Host, name, arguments)), declaration.suspend)
    }

    private fun positionalRule(spec: ActionSpec, at: DiagnosticLocation): ArgRule? {
        val rule: ArgRule? = spec.argRules.firstOrNull { it.shape is ArgShape.Positional }
        if (rule == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Action '" + spec.id.value + "' declares no argument list",
            )
        }
        return rule
    }

    private fun calleeName(step: ActionStep, list: ArgShape.Positional, at: DiagnosticLocation): String? {
        val written: PropertyValue? = step.args[list.callee]
        if (written == null) {
            refuseMissing(step, list.callee, at)
            return null
        }
        val name: String? = (written.constant() as? Value.Str)?.v
        if (name == null) {
            refuse(
                DiagnosticCodes.ActionArgInvalid,
                at,
                "Argument '" + list.callee.value + "' is not a string",
            )
        }
        return name
    }

    private fun declarationOf(name: String, at: DiagnosticLocation): HostFunctionDecl? {
        val declaration: HostFunctionDecl? = host.declaration(name)
        if (declaration == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Host function '" + name + "' has no declaration in reach, so nothing says whether it suspends",
            )
        }
        return declaration
    }

    // An absent list is the empty one a declared function may want, and the arity that decides is
    // the analysis pass's: it holds the declaration this emitter asks for it by name.
    private fun argumentsOf(step: ActionStep, key: PropertyKey, at: DiagnosticLocation): List<KtArg>? {
        val written: PropertyValue = step.args[key] ?: return emptyList()
        val items: List<PropertyValue> = listItems(written, key, at) ?: return null
        val arguments: MutableList<KtArg> = mutableListOf()
        for (item in items) {
            val value: KtExpr = argumentValue(item, key, at) ?: return null
            arguments += KtArg(null, value)
        }
        return arguments
    }

    // Each element becomes the argument value the pass read out of it: a constant as it is, an
    // expression as the one that produces it.
    private fun listItems(
        written: PropertyValue,
        key: PropertyKey,
        at: DiagnosticLocation,
    ): List<PropertyValue>? {
        val items: List<PropertyValue>? = when (written) {
            is PropertyValue.Const -> (written.value as? Value.ListOf)?.items
                ?.map { PropertyValue.Const(it) }
            is PropertyValue.Computed -> (written.expr as? Expr.ListLiteral)?.items
                ?.map { PropertyValue.Computed(it) }
        }
        if (items == null) {
            refuse(
                DiagnosticCodes.ActionArgInvalid,
                at,
                "Argument '" + key.value + "' is not a list of arguments",
            )
        }
        return items
    }

    /** `snackbars.show(message)`: the one operation the presentation host declares. */
    private fun snackbar(step: ActionStep, spec: ActionSpec, at: DiagnosticLocation): Emitted? {
        val key: PropertyKey = messageArgument(spec, at) ?: return null
        val message: KtExpr = argument(step, key, at) ?: return null
        return Emitted(KtStmt.Expr(on(ActionReceiver.Snackbars, "show", listOf(KtArg(null, message)))))
    }

    // One argument, so its count says which one is the message: the string type names a value
    // rather than a role, and a spec declaring two would leave the choice a guess.
    private fun messageArgument(spec: ActionSpec, at: DiagnosticLocation): PropertyKey? {
        val key: PropertyKey? = spec.params.singleOrNull()?.key
        if (key == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Action '" + spec.id.value + "' declares no single message argument",
            )
        }
        return key
    }

    /**
     * A plugin's own pattern, its `{key}` placeholders filled from the step's arguments.
     *
     * The same grammar and the same rule a component parameter's pattern follows: a pattern spells
     * the whole expression, so only a literal fills one and the text is the statement rather than
     * the arguments of a call this emitter would have to invent.
     */
    private fun templated(step: ActionStep, emit: ActionEmit.Template, at: DiagnosticLocation): Emitted? {
        val filled: StringBuilder = StringBuilder()
        val symbols: MutableList<KotlinSymbol> = emit.imports.toMutableList()
        var index: Int = 0
        while (index < emit.pattern.length) {
            val open: Int = emit.pattern.indexOf('{', index)
            if (open < 0) {
                filled.append(emit.pattern.substring(index))
                break
            }
            val close: Int = emit.pattern.indexOf('}', open)
            if (close < 0) {
                filled.append(emit.pattern.substring(index))
                break
            }
            filled.append(emit.pattern.substring(index, open))
            val raw: String = emit.pattern.substring(open + 1, close)
            val key: PropertyKey? = try {
                PropertyKey(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (key == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, at, "Bad pattern key '{$raw}'")
                return null
            }
            val written: PropertyValue? = step.args[key]
            if (written == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, at, "Pattern key '{$raw}' is absent")
                return null
            }
            val value: KtExpr = argumentValue(written, key, at) ?: return null
            if (value !is KtExpr.Literal) {
                refuse(
                    DiagnosticCodes.CodegenStrategyUnsupported,
                    at,
                    "Pattern key '{$raw}' holds a computed value, and a pattern is text",
                )
                return null
            }
            filled.append(value.text)
            symbols.addAll(value.imports)
            index = close + 1
        }
        return Emitted(KtStmt.Expr(KtExpr.Snippet(filled.toString(), symbols)))
    }

    // The key an argument rule names, read off the spec rather than off the action's id, so a
    // plugin action declaring the same shape is emitted the same way.
    private fun ruleArgument(spec: ActionSpec, shape: ArgShape, at: DiagnosticLocation): PropertyKey? {
        val key: PropertyKey? = spec.argRules.firstOrNull { it.shape == shape }?.key
        if (key == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Action '" + spec.id.value + "' declares no '" + shapeName(shape) + "' argument",
            )
        }
        return key
    }

    private fun argument(step: ActionStep, key: PropertyKey, at: DiagnosticLocation): KtExpr? {
        val written: PropertyValue? = step.args[key]
        if (written == null) {
            refuseMissing(step, key, at)
            return null
        }
        return argumentValue(written, key, at)
    }

    /**
     * One argument as Kotlin: a constant through the literal printer, a computed one through the
     * reader every other computed value gets.
     */
    private fun argumentValue(written: PropertyValue, key: PropertyKey, at: DiagnosticLocation): KtExpr? =
        when (written) {
            is PropertyValue.Const -> literal(written.value, key, at)
            is PropertyValue.Computed -> expressions.emitRaw(written.expr)
        }

    private fun literal(value: Value, key: PropertyKey, at: DiagnosticLocation): KtExpr? {
        val emitted: EmittedLiteral? = LiteralPrinter.emit(value)
        if (emitted == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "Argument '" + key.value + "' holds a value with no Kotlin literal",
            )
            return null
        }
        return KtExpr.Literal(emitted.text, emitted.symbols)
    }

    // An argument read as a value without being evaluated: only a constant names a page or a host
    // function, and an expression naming either has no spelling on this side.
    private fun PropertyValue.constant(): Value? = when (this) {
        is PropertyValue.Const -> value
        is PropertyValue.Computed -> (expr as? Expr.Const)?.value
    }

    // Every intrinsic call goes through here, so this is where the environment the sequence
    // closed over is recorded: the receiver name is a free identifier the screen must supply.
    private fun on(receiver: ActionReceiver, member: String, args: List<KtArg> = emptyList()): KtExpr.Call {
        reads += receiver
        return KtExpr.Call(KtExpr.Member(KtExpr.Name(receiver.name), member), args)
    }

    /**
     * `scope.launch { … }`, the wrapper a suspending sequence needs.
     *
     * Text with the import riding along, because the IR names a library symbol but not a member
     * reached through a receiver the file declares — the same spelling a modifier's own emitted
     * case takes.
     */
    private fun launched(body: List<KtStmt>): KtStmt {
        reads += ActionReceiver.Scope
        return KtStmt.Expr(
            KtExpr.Call(
                KtExpr.Snippet("scope.launch", listOf(launch)),
                emptyList(),
                KtExpr.Lambda(emptyList(), body),
            ),
        )
    }

    /** The refusal every absent required argument is, naming the action and the key. */
    private fun refuseMissing(step: ActionStep, key: PropertyKey, at: DiagnosticLocation): Unit {
        refuse(
            DiagnosticCodes.ActionArgInvalid,
            at,
            "Action '" + step.action.value + "' has no '" + key.value + "' argument",
        )
    }

    private fun shapeName(shape: ArgShape): String = when (shape) {
        ArgShape.StateRef -> "target"
        ArgShape.TargetValue -> "value"
        is ArgShape.Positional -> "positional"
    }

    /**
     * Records one finding.
     *
     * One is enough: a result carrying an error is the refusal, so a second would change nothing
     * about what the caller does with it. Nothing is returned, so a call site reads as the value
     * it stands for and the finding never has to be threaded through a return type.
     */
    private fun refuse(code: DiagnosticCode, at: DiagnosticLocation, message: String): Unit {
        found += Diagnostic(Severity.Error, code, at, message)
    }
}

/**
 * The actions the engine emits itself, and what each one becomes.
 *
 * A table keyed by id rather than a branch over it, for the reason the interpreter's own handler
 * table spells the same six ids: the specs declaring them live in a module this one may not
 * depend on, so the id is the only thing the spec and this side share.
 */
internal object IntrinsicActions {

    private val table: Map<ActionId, ActionKind> = mapOf(
        ActionId("nav.navigate") to ActionKind.Navigate,
        ActionId("nav.back") to ActionKind.Back,
        ActionId("state.set") to ActionKind.WriteState,
        ActionId("flow.if") to ActionKind.Conditional,
        ActionId("host.call") to ActionKind.CallHost,
        ActionId("ui.showSnackbar") to ActionKind.ShowSnackbar,
    )

    /** What [id] becomes, or null when the engine has no emitter for it. */
    fun kindOf(id: ActionId): ActionKind? = table[id]
}

/** What one intrinsic action becomes, once its id has been looked up. */
internal enum class ActionKind {
    Navigate,
    Back,
    WriteState,
    Conditional,
    CallHost,
    ShowSnackbar,
}

/**
 * The receivers a handler's statements name, and what a screen gets them from.
 *
 * [type] is the generated declaration each receiver needs in the base package, and null for the
 * one that comes from the composition rather than from a parameter — so the vocabulary is one
 * list and no caller has to remember which of the four is the odd one out.
 */
internal enum class ActionReceiver(val name: String, val type: String?) {
    Navigator("navigator", "AppNavigator"),
    Snackbars("snackbars", "SnackbarHost"),
    Host("host", "AppHost"),
    Scope("scope", null),
}

/** One step's statement, and whether reaching that statement suspends. */
/**
 * One step's emission.
 *
 * [symbols] travels with the statement because a `Template` action's imports are part of its
 * emission: the pattern is text, so nothing in the emitted expression names the symbols it needs
 * and the printer has no way to find them by walking the tree.
 */
private class Emitted(
    val statement: KtStmt,
    val suspends: Boolean = false,
    val symbols: List<KotlinSymbol> = emptyList(),
)
