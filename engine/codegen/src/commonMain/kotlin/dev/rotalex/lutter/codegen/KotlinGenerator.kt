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
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitterId
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
        state = StateEmitter(document, options, schema.functions)
        val plan: GenPlan = planDocument(document, options.basePackage)
        refuseCollisions(plan)
        val files: MutableList<GeneratedFile> = mutableListOf()
        if (diagnostics.none { it.severity == Severity.Error }) {
            for (planned in plan.files) emitPlanned(planned, document)?.let { files += it }
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
    // `App.kt` and `state/AppState.kt` both carry no id.
    private fun emitPlanned(planned: PlannedFile, document: ResolvedDocument): GeneratedFile? {
        val file: KtFile? = when (planned.kind) {
            PlannedFileKind.App -> emitApp(document)
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

    /** §16.5's `state/AppState.kt`: the holder and the composition local, in their own package. */
    private fun emitAppState(planned: PlannedFile, document: ResolvedDocument): KtFile? {
        val emitter: StateEmitter = stateEmitter() ?: return null
        if (refuseState(document.appState, DiagnosticLocation())) return null
        val declarations: List<KtDeclaration> = emitter.appState() ?: return null
        return KtFile(planned.packageName, headerText(), declarations)
    }

    // AppRoot hosts the first page by id; routing waits for a navigation strategy.
    private fun emitApp(document: ResolvedDocument): KtFile? {
        val first: ResolvedPage? = document.pages.entries.sortedBy { it.key.value }
            .map { it.value }.firstOrNull()
        val body: List<KtStmt> = if (first == null) {
            emptyList()
        } else {
            val screen: KotlinSymbol = KotlinSymbol(options.basePackage + ".screens", first.name + "Screen")
            val call: KtStmt = KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.Ref(KtSymbolRef(screen)),
                    listOf(KtArg("modifier", KtExpr.Name("modifier"))),
                ),
            )
            // §12.1's app row wraps the whole tree; with no app state the screen composes bare.
            stateEmitter()?.appProvider(call) ?: listOf(call)
        }
        val function: KtDeclaration.Function =
            KtDeclaration.Function("AppRoot", listOf(composable), null, listOf(modifierParam()), body)
        return KtFile(options.basePackage, headerText(), listOf(function))
    }

    // §12.1's page row: the state class, the remember function and the screen's parameter.
    private fun emitScreen(page: ResolvedPage, planned: PlannedFile): KtFile? {
        val at: DiagnosticLocation = DiagnosticLocation(pageId = page.id)
        if (refuseState(page.state, at)) return null
        val emitter: StateEmitter = stateEmitter() ?: return null
        val root: KtExpr = emitNode(page.root, true, emptyList()) ?: return null
        val declarations: MutableList<KtDeclaration> = mutableListOf()
        val params: MutableList<KtParam> = mutableListOf(modifierParam())
        val held: List<KtDeclaration>? = emitter.pageState(page)
        if (held != null) {
            declarations += held
            params += emitter.pageParameter(page)
        }
        declarations += KtDeclaration.Function(
            page.name + "Screen",
            listOf(composable),
            null,
            params,
            listOf(KtStmt.Expr(root)),
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
        val statements: List<KtStmt> = emitter.componentLocals(held).orEmpty() + listOf(KtStmt.Expr(body))
        val function: KtDeclaration.Function = KtDeclaration.Function(
            componentId.value,
            listOf(composable),
            null,
            listOf(modifierParam()),
            statements,
        )
        return KtFile(planned.packageName, headerText(), listOf(function))
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
        KtParam("modifier", modifierType, KtExpr.Ref(KtSymbolRef(modifierType)))

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
        if (binding.events.isNotEmpty()) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Events emit nothing yet")
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
            return literalOf(prop, spec.properties, node)
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

    // Every `{key}` becomes the property's literal; unknown or computed keys refuse.
    // Returns the text plus the literals' imports: a value filled into a pattern (a `dp`
    // inside `Arrangement.spacedBy({spacing})`) still needs its own symbols recorded.
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
            val literal: KtExpr.Literal? = literalOf(prop, declared, node, entries)
            if (literal == null) return null
            filled.append(literal.text)
            symbols.addAll(literal.imports)
            index = close + 1
        }
        return filled.toString() to symbols
    }

    // A literal, or a refusal: computed values and unspeakable kinds emit nothing.
    private fun literalOf(
        prop: ResolvedProp,
        declared: List<PropertySpec<*>>,
        node: ResolvedNode,
        entries: Map<String, KotlinSymbol> = emptyMap(),
    ): KtExpr.Literal? {
        val stored = prop.value
        if (stored is PropertyValue.Computed) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Computed properties emit nothing yet")
            return null
        }
        val constant: Value = (stored as PropertyValue.Const).value
        if (constant is Value.Enum) return enumLiteral(constant.entry, declared, prop.key, node, entries)
        val literal = LiteralPrinter.emit(constant)
        if (literal == null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Value kind emits nothing yet")
            return null
        }
        return KtExpr.Literal(literal.text, literal.symbols)
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
                args += KtArg(key.value, literalOf(prop, spec.params, node, entries) ?: return null)
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

    private class KeptParam(val binding: ParamBinding, val present: List<PropertyKey>)
}
