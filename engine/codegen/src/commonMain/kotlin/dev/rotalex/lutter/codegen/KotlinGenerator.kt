package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCode
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.PropOrigin
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedModifier
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.doc.HostFunctionDecl
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.TypedExpr
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitterId
import dev.rotalex.lutter.schema.component.EventBinding
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.ParamBinding
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.SlotBinding
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/** Plugin-owned emission, consulted only for `Custom` bindings. */
public fun interface CustomEmitter {
    public fun emit(node: ResolvedNode): KtExpr
}

/** Override table for `Custom` bindings; empty means none are provided. */
public class CodegenExtensions(public val emitters: Map<EmitterId, CustomEmitter>) {
    public companion object {
        /** No overrides: every `Custom` binding fails as `codegen.no_binding`. */
        public val None: CodegenExtensions = CodegenExtensions(emptyMap())
    }
}

/**
 * Resolved documents to Kotlin, driven by `CodegenBinding` data, never per-type code.
 *
 * Refuses error-carrying input and unemittable documents with diagnostics, not exceptions;
 * a thrown [CodegenBug] always means the generator or a spec is wrong, never the document.
 *
 * The function registry is [FunctionSpec] rather than a type parameter: `schema.functions` is
 * how an expression call resolves, so a generator bound to anything else could not emit one.
 */
public class KotlinGenerator<A : Any, T : Any>(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, A, FunctionSpec, T>,
    private val options: CodegenOptions,
    private val extensions: CodegenExtensions = CodegenExtensions.None,
) {
    private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
    private val modifierType: KotlinSymbol = KotlinSymbol("androidx.compose.ui", "Modifier")
    private val printer: KtPrinter = KtPrinter(ImportPolicy(), options.formatting)
    // Per-run scratch, cleared on entry: the recursion below shares one collector.
    private val diagnostics: MutableList<Diagnostic> = mutableListOf()
    // §12.2's seam: one emitter per document, built on entry and rebuilt per run so a second
    // `generate` over another document cannot read the first one's declarations.
    private var state: StateEmitter? = null
    // The screen-site [StateRead], built per run beside [state]. A state class member builds its
    // own reader over its siblings, because `state.count` inside one compiles and reads nothing.
    private var expressions: ExprEmitter? = null
    // Per-run beside the two above. Reads the generator's own diagnostics list, so a refused step
    // is the finding and not a second one beside it.
    private var actions: ActionEmitter? = null

    private val rememberCoroutineScope: KotlinSymbol =
        KotlinSymbol("androidx.compose.runtime", "rememberCoroutineScope")

    // Fail fast on missing bindings, like UiRuntime does on missing renderers.
    init {
        CodegenCoverage.check(schema, extensions)
    }

    /** Generates [document]; empty files plus errors is the refusal, never partial output. */
    public fun generate(document: ResolvedDocument, incoming: List<Diagnostic> = emptyList()): CodegenResult {
        if (incoming.any { it.severity == Severity.Error }) {
            return CodegenResult(GeneratedFiles(emptyList()), incoming)
        }
        diagnostics.clear()
        screenReceivers.clear()
        // Before the plan and before any emitter: §16.7 asks for a located finding rather than a
        // red compile harness, and the reserved set is the one thing a document cannot know.
        diagnostics += ReservedCodegenNames.findings(document, options)
        state = StateEmitter(document, options, schema.functions)
        val reader: ExprEmitter = ExprEmitter(schema.functions, StateRead { id -> state?.readInScreen(id) })
        expressions = reader
        actions = ActionEmitter(
            schema.actions,
            reader,
            document.pages,
            HostDecl { name -> document.hostFunctions.firstOrNull { it.name == name } },
            options,
            diagnostics,
        )
        val plan: GenPlan = planDocument(document, options.basePackage)
        refuseCollisions(plan)
        val files: MutableList<GeneratedFile> = mutableListOf()
        if (diagnostics.none { it.severity == Severity.Error }) {
            // `App.kt` is planned first, but it calls every screen, so it is emitted last: what
            // those screens need from their caller is only known once they have been emitted. The
            // output order stays the plan's, so nothing downstream sees a different file list.
            val written: MutableMap<String, GeneratedFile> = LinkedHashMap()
            for (planned in plan.files) {
                if (planned.kind == PlannedFileKind.App) continue
                emitPlanned(planned, document)?.let { written[it.path] = it }
            }
            for (planned in plan.files) {
                if (planned.kind != PlannedFileKind.App) continue
                emitPlanned(planned, document)?.let { written[it.path] = it }
            }
            for (planned in plan.files) written[planned.path]?.let { files += it }
        }
        if (diagnostics.any { it.severity == Severity.Error }) {
            return CodegenResult(GeneratedFiles(emptyList()), incoming + diagnostics)
        }
        return CodegenResult(GeneratedFiles(files), incoming + diagnostics)
    }

    // One function name per file: two pages called Home would emit the same screen twice.
    private fun refuseCollisions(plan: GenPlan): Unit {
        val names: List<String> = plan.files.map { it.path }
        if (names.size != names.toSet().size) {
            diagnostics += Diagnostic(
                Severity.Error,
                DiagnosticCodes.CodegenNameCollision,
                DiagnosticLocation(),
                "Generated file paths collide: " + names.sorted().toString(),
            )
        }
    }

    // The kind decides which emitter owns the file, because the id fields alone cannot:
    // `App.kt`, `Navigation.kt`, `AppHost.kt` and `state/AppState.kt` all carry no id.
    private fun emitPlanned(planned: PlannedFile, document: ResolvedDocument): GeneratedFile? {
        val file: KtFile? = when (planned.kind) {
            PlannedFileKind.App -> options.navigation.emitAppRoot(document, fileContext())
            PlannedFileKind.Navigation -> emitNavigation(planned, document)
            PlannedFileKind.AppHost -> emitAppHost(planned, document)
            PlannedFileKind.AppState -> emitAppState(planned, document)
            PlannedFileKind.Screen -> {
                val pageId = checkNotNull(planned.pageId) { "Plan marked '${planned.path}' with no page" }
                val page: ResolvedPage = checkNotNull(document.pages[pageId]) {
                    "Plan reached page '$pageId' outside the document"
                }
                emitScreen(page, planned)
            }

            PlannedFileKind.Component -> {
                val componentId = checkNotNull(planned.componentId) {
                    "Plan marked '${planned.path}' with no component declaration"
                }
                val root: ResolvedNode = checkNotNull(document.components[componentId]) {
                    "Plan reached component '$componentId' outside the document"
                }
                emitComponent(componentId, root, planned, document)
            }
        }
        return file?.let { GeneratedFile(planned.path, printer.print(it)) }
    }

    /**
     * The context one emitted file is written into.
     *
     * Every file that has one sits in the base package, which is what `pkg` can be a constant;
     * `receivers` is read by `AppRoot` alone and is empty for the rest. The app-state wrapper is
     * offered to all of them because §12.1's provider belongs to whichever file declares it, and a
     * document with no app state makes the wrapper the identity.
     */
    private fun fileContext(): FileContext = FileContext(
        pkg = options.basePackage,
        header = headerText(),
        receivers = screenReceivers.mapValues { (_, held) -> held.sortedBy(ActionReceiver::member) },
        appState = { content -> stateEmitter()?.appProvider(content) ?: listOf(content) },
    )

    /**
     * §16.5's `Navigation.kt`, the strategy's own declarations.
     *
     * Refused for a document with no page: the navigator's stack is seeded with a route, and a
     * document that declares none has nothing to seed it with.
     */
    private fun emitNavigation(planned: PlannedFile, document: ResolvedDocument): KtFile? {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        if (pages.isEmpty()) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                DiagnosticLocation(),
                "A document with no page has no route for its navigator to start on",
            )
            return null
        }
        return KtFile(planned.packageName, headerText(), options.navigation.emitRoutes(pages, fileContext()))
    }

    /**
     * §16.5's `AppHost.kt`: the interfaces the embedding app implements.
     *
     * `SnackbarHost` sits here rather than in `Navigation.kt` because it is not strategy-owned: a
     * different navigation strategy would have to re-declare a presentation host it has no stake
     * in. §16.5 lists no file for it, which is a gap in the plan rather than a decision.
     */
    private fun emitAppHost(planned: PlannedFile, document: ResolvedDocument): KtFile? {
        val declarations: List<HostFunctionDecl> = document.hostFunctions
        val repeated: String? = declarations.groupBy { it.name }
            .filterValues { it.size > 1 }.keys.firstOrNull()
        if (repeated != null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                DiagnosticLocation(),
                "Host function '" + repeated + "' is declared twice, and one member cannot hold both",
            )
            return null
        }
        val members: MutableList<KtDeclaration.Function> = mutableListOf()
        for (declaration in declarations) {
            members += hostMember(declaration) ?: return null
        }
        return KtFile(
            planned.packageName,
            headerText(),
            listOf(
                KtDeclaration.Interface(ReservedCodegenNames.AppHostName, emptyList(), false, members),
                KtDeclaration.Interface(
                    ReservedCodegenNames.SnackbarHostName,
                    emptyList(),
                    false,
                    listOf(snackbarShow()),
                ),
            ),
        )
    }

    /**
     * One `HostFunctionDecl` as the member `host.call` calls.
     *
     * The return type is written only when the declaration names one, and a type nothing can
     * spell is a finding rather than a guess: a member declared `Unit` that the hand-written
     * implementation returns something from does not compile, which is loud but late.
     */
    private fun hostMember(declaration: HostFunctionDecl): KtDeclaration.Function? {
        val parameters: MutableList<KtParam> = mutableListOf()
        for (param in declaration.params) {
            val label: String = "Host function '" + declaration.name + "' parameter '" + param.name.value + "'"
            val spelled: KtExpr = spelledHostType(label, param.type) ?: return null
            parameters += KtParam(param.name.value, spelled, null)
        }
        val returned: KtExpr? = declaration.returns?.let {
            spelledHostType("Host function '" + declaration.name + "' return", it) ?: return null
        }
        return KtDeclaration.Function(
            name = declaration.name,
            annotations = emptyList(),
            type = returned,
            params = parameters,
            body = emptyList(),
            suspending = declaration.suspend,
        )
    }

    /** The interpreter's own one operation, so the two sides of a snackbar are the same call. */
    private fun snackbarShow(): KtDeclaration.Function = KtDeclaration.Function(
        name = "show",
        annotations = emptyList(),
        type = null,
        params = listOf(KtParam("message", KtExpr.Name("String"), null)),
        body = emptyList(),
    )

    private fun spelledHostType(label: String, typeRef: TypeRef): KtExpr? =
        when (val spelling = KotlinTypes.spellingFor(typeRef)) {
            is TypeSpelling.Spelled -> spelling.expr
            is TypeSpelling.Unspelled -> {
                refuse(
                    DiagnosticCodes.CodegenNoTypeSpelling,
                    DiagnosticLocation(),
                    label + " is typed '" + typeRef.serialTag +
                        "', which has no Kotlin spelling: " + spelling.reason,
                )
                null
            }
        }

    /** §16.5's `state/AppState.kt`: the holder and the composition local, in their own package. */
    private fun emitAppState(planned: PlannedFile, document: ResolvedDocument): KtFile? {
        val emitter: StateEmitter = stateEmitter() ?: return null
        if (refuseState(document.appState, DiagnosticLocation())) return null
        val declarations: List<KtDeclaration> = emitter.appState() ?: return null
        return KtFile(planned.packageName, headerText(), declarations)
    }

    // §12.1's page row: the state class, the remember function and the screen's parameter.
    private fun emitScreen(page: ResolvedPage, planned: PlannedFile): KtFile? {
        val at: DiagnosticLocation = DiagnosticLocation(pageId = page.id)
        if (refuseState(page.state, at)) return null
        val emitter: StateEmitter = stateEmitter() ?: return null
        val root: KtExpr = emitNode(page.root, true, emptyList()) ?: return null
        val reads: Set<ActionReceiver> = drainReads()
        recordDemand(page.id, reads)
        val declarations: MutableList<KtDeclaration> = mutableListOf()
        val params: MutableList<KtParam> = mutableListOf()
        params += environmentParams(reads)
        params += modifierParam()
        val held: List<KtDeclaration>? = emitter.pageState(page)
        if (held != null) {
            declarations += held
            params += emitter.pageParameter(page)
        }
        declarations += KtDeclaration.Function(
            page.name + ReservedCodegenNames.ScreenSuffix,
            listOf(composable),
            null,
            params,
            actionLocals(reads) + listOf(KtStmt.Expr(root)),
        )
        return KtFile(planned.packageName, headerText(), declarations)
    }

    // Component functions take no page chrome: same node emission, their own file.
    private fun emitComponent(
        componentId: ComponentDeclId,
        root: ResolvedNode,
        planned: PlannedFile,
        document: ResolvedDocument,
    ): KtFile? {
        val emitter: StateEmitter = stateEmitter() ?: return null
        val at: DiagnosticLocation = DiagnosticLocation(componentDeclId = componentId)
        val held: List<ResolvedState> = document.componentState[componentId].orEmpty()
        if (refuseState(held, at)) return null
        val body: KtExpr = emitNode(root, true, emptyList()) ?: return null
        // Drained and not recorded: nothing the generator emits calls a component function — its
        // body is inlined into the slot lambda that holds it — so there is no caller whose
        // signature a component's receivers could belong to.
        val reads: Set<ActionReceiver> = drainReads()
        val statements: List<KtStmt> =
            emitter.componentLocals(held).orEmpty() + actionLocals(reads) + listOf(KtStmt.Expr(body))
        val function: KtDeclaration.Function = KtDeclaration.Function(
            componentId.value,
            listOf(composable),
            null,
            environmentParams(reads) + modifierParam(),
            statements,
        )
        return KtFile(planned.packageName, headerText(), listOf(function))
    }

    /** The receivers the handlers in the file just written closed over, emptied by reading. */
    private fun drainReads(): Set<ActionReceiver> = actionEmitter()?.drainReads() ?: emptySet()

    /**
     * What every screen written so far needs its caller to supply, per page.
     *
     * Recorded per page rather than as one set because `AppRoot` calls every screen: a receiver
     * only one page's handlers reached for is not a parameter of another page's screen, so it
     * cannot be handed to one. Emitted last and handed to the strategy, which declares each
     * receiver as a parameter and passes it down under the same name — §11.6 already routes the
     * generated `AppHost` that way. Cleared per run beside [diagnostics], because one generator
     * serves many documents and a receiver the previous document's handlers used is not one this
     * one's screens declare.
     */
    private fun recordDemand(page: PageId, reads: Set<ActionReceiver>): Unit {
        screenReceivers.getOrPut(page) { mutableSetOf() } += reads
    }

    private val screenReceivers: MutableMap<PageId, MutableSet<ActionReceiver>> = mutableMapOf()

    private fun actionEmitter(): ActionEmitter? = actions

    /**
     * One parameter per environment receiver the file's handlers used, sorted by name.
     *
     * Demand-driven because these parameters have no default: a document with no event would
     * otherwise be handed three it never passes, and a required parameter nothing supplies is a
     * screen its own `AppRoot` cannot call. Sorted so two runs over one document agree.
     */
    private fun environmentParams(reads: Set<ActionReceiver>): List<KtParam> =
        reads.filter { it.type != null }
            .sortedBy { it.member }
            .map { receiver ->
                val type: KtExpr =
                    KtExpr.Ref(KtSymbolRef(KotlinSymbol(options.basePackage, checkNotNull(receiver.type))))
                KtParam(receiver.member, type, null)
            }

    /**
     * `val scope = rememberCoroutineScope()` when a handler launched a coroutine.
     *
     * The one receiver that is a local rather than a parameter: §11.5's scope is owned by the
     * composition, so a screen reads it out of itself the way it reads its own state.
     */
    private fun actionLocals(reads: Set<ActionReceiver>): List<KtStmt> {
        if (ActionReceiver.Scope !in reads) return emptyList()
        return listOf(
            KtStmt.LocalProperty(
                name = ActionReceiver.Scope.member,
                type = null,
                mutable = false,
                initializer = KtExpr.Call(KtExpr.Ref(KtSymbolRef(rememberCoroutineScope)), emptyList()),
                delegate = null,
            ),
        )
    }

    /**
     * Records the first declaration in [declarations] the strategy cannot emit; true when it did.
     *
     * One finding and a null file, the same shape every other refusal here takes: the result is
     * empty files once any error is recorded, so a second finding would change nothing.
     */
    private fun refuseState(declarations: List<ResolvedState>, at: DiagnosticLocation): Boolean {
        val emitter: StateEmitter = stateEmitter() ?: return true
        for (declaration in declarations) {
            val finding: Diagnostic = emitter.refusal(declaration, at) ?: continue
            diagnostics += finding
            return true
        }
        return false
    }

    private fun stateEmitter(): StateEmitter? = state

    private fun modifierParam(): KtParam =
        KtParam(
            "modifier",
            KtExpr.Ref(KtSymbolRef(modifierType)),
            KtExpr.Ref(KtSymbolRef(modifierType)),
        )

    private fun headerText(): String? =
        if (options.header == HeaderPolicy.Minimal) "// Generated by Forge. Do not edit." else null

    // The whole emitter: a registry lookup plus the binding's params, slots and events.
    // `scopes` are the receivers the ancestors opened, innermost first: what the node's own
    // modifiers resolve against, and read from this walk rather than from `ResolvedNode.scopes`
    // because a set has no innermost.
    private fun emitNode(node: ResolvedNode, isRoot: Boolean, scopes: List<ScopeId>): KtExpr? {
        val spec: ComponentSpec? = schema.components[node.type]
        if (spec == null) {
            refuse(DiagnosticCodes.CodegenNoBinding, node, "No spec for '${node.type}'")
            return null
        }
        val binding: CodegenBinding = spec.codegen
        if (binding is CodegenBinding.Custom) {
            val emitter: CustomEmitter? = extensions.emitters[binding.emitterId]
            if (emitter == null) {
                refuse(DiagnosticCodes.CodegenNoBinding, node, "No emitter for '${binding.emitterId}'")
                return null
            }
            return emitter.emit(node)
        }
        if (binding !is CodegenBinding.ComposeCall) {
            refuse(DiagnosticCodes.CodegenNoBinding, node, "No compose call for '${node.type}'")
            return null
        }
        unboundEvent(binding, node)?.let { unbound ->
            refuse(DiagnosticCodes.CodegenNoBinding, node, "Event '$unbound' has no parameter to receive it")
            return null
        }
        val args: MutableList<KtArg> = mutableListOf()
        val kept: List<KeptParam> = keptParams(binding, node)
        for (keptParam in kept) {
            val value: KtExpr = paramValue(keptParam, spec, node) ?: return null
            val positional: Boolean = keptParam.binding.positional == Positional.WhenSole &&
                kept.size == 1 && keptParam.present.size == 1
            args += KtArg(if (positional) null else keptParam.binding.param, value)
        }
        // A handler argument is always named and a positional value argument has to precede every
        // named one, so the handlers land between the values and `modifier`: the component's own
        // argument comes first, and `modifier` stays where the rest of the file expects it.
        for (event in binding.events) {
            args += KtArg(event.param, eventLambda(event, node) ?: return null)
        }
        // `modifier` is always named and Compose declares it after the value arguments, so a
        // positional one has to precede it: `Text("Left", modifier = …)`, never the reverse.
        modifierArg(binding.modifierParam, node, isRoot, scopes)?.let { args += it }
        var trailing: KtExpr.Lambda? = null
        for (slot in binding.slots) {
            val lambda: KtExpr.Lambda = slotLambda(slot, node, spec, scopes) ?: return null
            val target = slot.target
            if (target is LambdaTarget.Trailing && trailing == null) {
                trailing = lambda
            } else if (target is LambdaTarget.NamedParam) {
                args += KtArg(target.name, lambda)
            } else {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Two trailing slots")
                return null
            }
        }
        return KtExpr.Call(KtExpr.Ref(KtSymbolRef(binding.function)), args, trailing)
    }

    // Present and not defaulted away; absent reads as omitted, never as null.
    private fun keptParams(
        binding: CodegenBinding.ComposeCall,
        node: ResolvedNode,
    ): List<KeptParam> {
        val kept: MutableList<KeptParam> = mutableListOf()
        for (param in binding.params) {
            val present: List<PropertyKey> = param.from.filter { node.props.containsKey(it) }
            if (present.isEmpty()) continue
            if (param.omitWhenDefault && present.all { isDefault(node, it) }) continue
            kept += KeptParam(param, present)
        }
        return kept
    }

    private fun isDefault(node: ResolvedNode, key: PropertyKey): Boolean {
        val prop: ResolvedProp? = node.props[key]
        if (prop == null) return false
        return prop.origin == PropOrigin.Default
    }

    // One parameter's value: the single property the node carries, or the case they select.
    private fun paramValue(
        kept: KeptParam,
        spec: ComponentSpec,
        node: ResolvedNode,
    ): KtExpr? {
        val binding: ParamBinding = kept.binding
        val emit = binding.emit
        if (emit is ValueEmit.Direct) {
            // `from` is a preference list, not an order to fill from: only a property the node
            // carries can supply the value, and two of them say nothing about which one meant.
            if (kept.present.size > 1) {
                refuse(
                    DiagnosticCodes.CodegenStrategyUnsupported,
                    node,
                    "Param '" + binding.param + "' has several present sources",
                )
                return null
            }
            val key: PropertyKey = kept.present.singleOrNull()
                ?: throw CodegenBug("Param '${binding.param}' binds no present property")
            val prop: ResolvedProp = checkNotNull(node.props[key]) {
                "Present property '${key.value}' left the node"
            }
            return valueOf(prop, spec.properties, node)
        }
        if (emit is ValueEmit.Cases) {
            val match = emit.cases.firstOrNull { kase ->
                kase.whenPresent.all { node.props.containsKey(it) }
            }
            if (match == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "No emission case matches")
                return null
            }
            val filled = fillPattern(match.pattern, node.props, spec.properties, node) ?: return null
            return KtExpr.Snippet(filled.first, match.imports + filled.second)
        }
        throw CodegenBug("Unknown ValueEmit: " + emit)
    }

// Every `{key}` becomes the property's literal, spliced into text: an unknown key and a
    // computed one both refuse here, the second because a pattern has no place to put a read.
    // Returns the text plus the literals' imports: a `dp` inside `Arrangement.spacedBy({spacing})`
    // still needs its own symbols recorded.
    private fun fillPattern(
        pattern: String,
        values: Map<PropertyKey, ResolvedProp>,
        declared: List<PropertySpec<*>>,
        node: ResolvedNode,
        entries: Map<String, KotlinSymbol> = emptyMap(),
    ): Pair<String, List<KotlinSymbol>>? {
        val filled: StringBuilder = StringBuilder()
        val symbols: MutableList<KotlinSymbol> = mutableListOf()
        var index: Int = 0
        while (index < pattern.length) {
            val open: Int = pattern.indexOf('{', index)
            if (open < 0) {
                filled.append(pattern.substring(index))
                break
            }
            val close: Int = pattern.indexOf('}', open)
            if (close < 0) {
                filled.append(pattern.substring(index))
                break
            }
            filled.append(pattern.substring(index, open))
            val raw: String = pattern.substring(open + 1, close)
            val key: PropertyKey = try {
                PropertyKey(raw)
            } catch (_: IllegalArgumentException) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Bad pattern key '{$raw}'")
                return null
            }
            val prop: ResolvedProp? = values[key]
            if (prop == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Pattern key '{$raw}' is absent")
                return null
            }
            val value: KtExpr? = valueOf(prop, declared, node, entries)
            if (value == null) return null
            // A pattern is text and the value is spliced into it, so only a literal fills one: a
            // computed read has no spelling here, and §16.2's symbols are references, not text.
            if (value !is KtExpr.Literal) {
                refuse(
                    DiagnosticCodes.CodegenStrategyUnsupported,
                    node,
                    "Pattern key '{$raw}' holds a computed value, and a pattern is text",
                )
                return null
            }
            filled.append(value.text)
            symbols.addAll(value.imports)
            index = close + 1
        }
        return filled.toString() to symbols
    }

    /**
     * A property's value as a Kotlin expression, or a refusal.
     *
     * A computed value is §10.5's codegen column: the checked expression, spelled by [ExprEmitter]
     * against the [StateRead] for the site the read appears in. A constant emits exactly as it
     * did before either arm existed, which is what keeps a document with no computed property
     * byte-identical.
     */
    private fun valueOf(
        prop: ResolvedProp,
        declared: List<PropertySpec<*>>,
        node: ResolvedNode,
        entries: Map<String, KotlinSymbol> = emptyMap(),
    ): KtExpr? {
        val constant: Value = when (val stored = prop.value) {
            is PropertyValue.Computed -> return computedOf(prop)
            is PropertyValue.Const -> stored.value
        }
        if (constant is Value.Enum) return enumLiteral(constant.entry, declared, prop.key, node, entries)
        val literal = LiteralPrinter.emit(constant)
        if (literal == null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Value kind emits nothing yet")
            return null
        }
        return KtExpr.Literal(literal.text, literal.symbols)
    }

    /**
     * A computed value as its checked expression, read the way a screen reads state.
     *
     * Both nulls are breaches rather than findings, and §16.7 is why: pass 5 typechecks every
     * computed value, pass 7 attaches what it produced, and pass 7 runs only over a document no
     * pass has errored on. A `Computed` reaching here without one is a lost record.
     */
    private fun computedOf(prop: ResolvedProp): KtExpr {
        // `checkNotNull` would answer a plain IllegalStateException, and §16.7 reserves this
        // throw for CodegenBug so a caller can tell a breach from a domain refusal.
        val key: String = prop.key.value
        val typed: TypedExpr = prop.typed ?: throw CodegenBug(
            "Computed property '" + key + "' carries no checked expression; " +
                "pass 5 refused an unchecked one",
        )
        val emitter: ExprEmitter = expressions ?: throw CodegenBug(
            "Computed property '" + key + "' reached emission with no reader",
        )
        return emitter.emit(typed)
    }

    // PLAN §16.6: an enum entry emits `EnumEntrySpec.kotlin`, which is the only place the symbol
    // is known. Text rather than a reference, because a filled pattern is text as well. An
    // enclosing scope that narrows the axis declares its own symbol for the entry and wins.
    private fun enumLiteral(
        entry: String,
        declared: List<PropertySpec<*>>,
        key: PropertyKey,
        node: ResolvedNode,
        entries: Map<String, KotlinSymbol>,
    ): KtExpr.Literal? {
        val id: TypeId? = enumIdOf(declared.firstOrNull { it.key == key }?.type)
        val symbol: KotlinSymbol? = entries[entry] ?: id?.let { typeId ->
            enumEntries(typeId).firstOrNull { it.name == entry }?.kotlin
        }
        if (symbol == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                node,
                "Enum entry '" + entry + "' has no registered symbol",
            )
            return null
        }
        // A member-qualified name splits in two: the owner is what the import names.
        val owner: KotlinSymbol = KotlinSymbol(symbol.packageName, symbol.name.substringBeforeLast('.'))
        return KtExpr.Literal(symbol.name, listOf(owner))
    }

    // A `Value.Enum` carries no type id: the property's declared type is where it lives, through
    // a nullable wrapper when the property is one.
    private fun enumIdOf(declared: TypeRef?): TypeId? {
        val inner: TypeRef? = (declared as? TypeRef.Nullable)?.inner ?: declared
        return (inner as? TypeRef.Enum)?.id
    }

    // The enum spec behind a type id. The types registry is generic over the assembly's own type
    // slot, so this reads it as a downcast: an assembly binding a stub there gets no entries and
    // the entry refuses rather than emitting an unqualified name.
    @Suppress("UNCHECKED_CAST")
    private fun enumEntries(id: TypeId): List<EnumEntrySpec> =
        (schema.types[id] as? EnumTypeSpec)?.entries ?: emptyList()

    /**
     * An event the node carries that no [EventBinding] receives, or null when every one is bound.
     *
     * The mirror of the refusal inside [eventLambda]: a sequence with nowhere to go would be
     * dropped here while the interpreter runs it, so the document is not refused for carrying a
     * handler but for the schema having no parameter to carry it in. The smallest by name, so
     * two runs over one document name the same event.
     */
    private fun unboundEvent(binding: CodegenBinding.ComposeCall, node: ResolvedNode): EventKey? =
        node.events.keys
            .filter { event -> binding.events.none { it.event == event } }
            .minByOrNull { it.value }

    /**
     * One `EventBinding` as the lambda its Compose parameter receives.
     *
     * The three fields are three different facts and the sequence is the join: `event` selects it
     * out of [ResolvedNode.events], `lambdaParams` names the lambda's own parameters, and `param`
     * names the parameter it is written as. A binding whose event the node carries no sequence for
     * refuses rather than emitting an empty lambda, because a handler parameter has no default to
     * omit it with and `{}` is a handler that runs and does nothing.
     */
    private fun eventLambda(binding: EventBinding, node: ResolvedNode): KtExpr.Lambda? {
        val emitter: ActionEmitter = actionEmitter() ?: return null
        val sequence: ActionSequence = node.events[binding.event] ?: run {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                node,
                "Event '" + binding.event + "' has no handler, and '" + binding.param + "' has no default",
            )
            return null
        }
        val at: DiagnosticLocation = DiagnosticLocation(nodeId = node.id, event = binding.event)
        // A refused step recorded the finding itself, so the null here adds nothing of its own.
        val body: List<KtStmt> = emitter.emit(sequence, at) ?: return null
        return KtExpr.Lambda(binding.lambdaParams, body)
    }

    private fun slotLambda(
        binding: SlotBinding,
        node: ResolvedNode,
        spec: ComponentSpec,
        scopes: List<ScopeId>,
    ): KtExpr.Lambda? {
        if (binding.receiver != null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Scoped slots emit nothing yet")
            return null
        }
        val children: List<ResolvedNode> = node.slots[binding.slot] ?: emptyList()
        // The slot's own scopes go in front: a row inside a box is aligned by the row.
        val provided: Set<ScopeId> =
            spec.slots.firstOrNull { it.name == binding.slot }?.provides ?: emptySet()
        val inner: List<ScopeId> = provided.toList() + scopes
        val body: MutableList<KtStmt> = mutableListOf()
        for (child in children) {
            body += KtStmt.Expr(emitNode(child, false, inner) ?: return null)
        }
        return KtExpr.Lambda(emptyList(), body)
    }

    // Roots thread the caller's modifier; leaves start from `Modifier`. No entry, no arg.
    private fun modifierArg(
        param: String?,
        node: ResolvedNode,
        isRoot: Boolean,
        scopes: List<ScopeId>,
    ): KtArg? {
        val calls: MutableList<KtExpr> = mutableListOf()
        for (entry in node.modifiers) calls += emitModifier(entry, node, scopes) ?: return null
        if (param == null) {
            if (calls.isEmpty()) return null
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Modifiers without a parameter")
            return null
        }
        if (isRoot && calls.isEmpty()) return KtArg(param, KtExpr.Name("modifier"))
        if (calls.isEmpty()) return null
        val root: KtExpr = if (isRoot) KtExpr.Name("modifier") else KtExpr.Ref(KtSymbolRef(modifierType))
        return KtArg(param, KtExpr.Chain(root, calls))
    }

    private fun emitModifier(entry: ResolvedModifier, node: ResolvedNode, scopes: List<ScopeId>): KtExpr? {
        val spec: ModifierSpec? = schema.modifiers[entry.type]
        if (spec == null) {
            refuse(DiagnosticCodes.CodegenNoBinding, node, "No spec for '${entry.type}'")
            return null
        }
        val emit = spec.emit
        // The nearest open scope narrows an enum argument, and a modifier that needs one refuses
        // outside every scope it declares: there is no receiver to emit the call against.
        val scope: ScopeId? = scopes.firstOrNull { it in emit.scopeEntries }
        if (emit.scopeEntries.isNotEmpty() && scope == null) {
            refuse(
                DiagnosticCodes.CodegenStrategyUnsupported,
                node,
                "Modifier '" + entry.type + "' has no enclosing scope to read through",
            )
            return null
        }
        val entries: Map<String, KotlinSymbol> = scope?.let { emit.scopeEntries.getValue(it) } ?: emptyMap()
        if (emit.cases.isEmpty()) {
            val args: MutableList<KtArg> = mutableListOf()
            for (key in entry.args.keys.sortedBy { it.value }) {
                val prop: ResolvedProp = checkNotNull(entry.args[key]) {
                    "Present modifier arg '${key.value}' left the entry"
                }
                args += KtArg(key.value, valueOf(prop, spec.params, node, entries) ?: return null)
            }
            // A scope member's name resolves through the receiver, so it is not an import.
            return KtCall(emit.function, args, imported = !emit.scopeMember)
        }
        // First declared case whose keys the entry carries, so the order a spec writes is the
        // precedence it means: `padding`'s `all` wins when a document also names an axis.
        val match = emit.cases.firstOrNull { cased ->
            cased.whenPresent.all { entry.args.containsKey(it) }
        }
        if (match == null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "No emission case matches")
            return null
        }
        val filled = fillPattern(match.pattern, entry.args, spec.params, node, entries) ?: return null
        // The pattern spells the whole call, so the chained function is an import of its own
        // unless it is a scope member, whose name the parent content lambda already resolves.
        val imported: List<KotlinSymbol> = if (emit.scopeMember) emptyList() else listOf(emit.function)
        return KtExpr.Snippet(filled.first, imported + match.imports + filled.second)
    }

    private fun refuse(code: DiagnosticCode, node: ResolvedNode, message: String): Unit {
        diagnostics += Diagnostic(
            Severity.Error,
            code,
            DiagnosticLocation(nodeId = node.id),
            message + " at '" + node.id + "'",
        )
    }

    /**
     * The same refusal for a fault about the document rather than about one node.
     *
     * A file with no node of its own — `App.kt`, `Navigation.kt` — has nowhere to point, so the
     * location is the caller's and the message stands on its own without a node to name.
     */
    private fun refuse(code: DiagnosticCode, at: DiagnosticLocation, message: String): Unit {
        diagnostics += Diagnostic(Severity.Error, code, at, message)
    }

    private class KeptParam(val binding: ParamBinding, val present: List<PropertyKey>)
}
