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
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitterId
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.ParamBinding
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.SlotBinding
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.modifier.ModifierSpec

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
 */
public class KotlinGenerator<A : Any, F : Any, T : Any>(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, A, F, T>,
    private val options: CodegenOptions,
    private val extensions: CodegenExtensions = CodegenExtensions.None,
) {
    private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
    private val modifierType: KotlinSymbol = KotlinSymbol("androidx.compose.ui", "Modifier")
    private val printer: KtPrinter = KtPrinter(ImportPolicy(), options.formatting)
    // Per-run scratch, cleared on entry: the recursion below shares one collector.
    private val diagnostics: MutableList<Diagnostic> = mutableListOf()

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

    private fun emitPlanned(planned: PlannedFile, document: ResolvedDocument): GeneratedFile? {
        val pageId = planned.pageId
        if (pageId != null) {
            val page: ResolvedPage = checkNotNull(document.pages[pageId]) {
                "Plan reached page '$pageId' outside the document"
            }
            return emitScreen(page, planned)?.let { GeneratedFile(planned.path, printer.print(it)) }
        }
        val componentId = planned.componentId
        if (componentId != null) {
            val root: ResolvedNode = checkNotNull(document.components[componentId]) {
                "Plan reached component '$componentId' outside the document"
            }
            return emitComponent(componentId.value, root, planned)?.let {
                GeneratedFile(planned.path, printer.print(it))
            }
        }
        return emitApp(document)?.let { GeneratedFile(planned.path, printer.print(it)) }
    }

    // AppRoot hosts the first page by id; routing waits for a navigation strategy.
    private fun emitApp(document: ResolvedDocument): KtFile? {
        val first: ResolvedPage? = document.pages.entries.sortedBy { it.key.value }
            .map { it.value }.firstOrNull()
        if (first == null) {
            return KtFile(options.basePackage, headerText(), listOf(appFunction(null)))
        }
        val screen: KotlinSymbol = KotlinSymbol(options.basePackage + ".screens", first.name + "Screen")
        val call: KtExpr = KtExpr.Call(
            KtExpr.Ref(KtSymbolRef(screen)),
            listOf(KtArg("modifier", KtExpr.Name("modifier"))),
        )
        return KtFile(options.basePackage, headerText(), listOf(appFunction(call)))
    }

    private fun appFunction(content: KtExpr?): KtDeclaration.Function {
        val body: List<KtStmt> = if (content == null) emptyList() else listOf(KtStmt.Expr(content))
        return KtDeclaration.Function("AppRoot", listOf(composable), listOf(modifierParam()), body)
    }

    private fun emitScreen(page: ResolvedPage, planned: PlannedFile): KtFile? {
        val root: KtExpr = emitNode(page.root, true) ?: return null
        val function: KtDeclaration.Function = KtDeclaration.Function(
            page.name + "Screen",
            listOf(composable),
            listOf(modifierParam()),
            listOf(KtStmt.Expr(root)),
        )
        return KtFile(planned.packageName, headerText(), listOf(function))
    }

    // Component functions take no page chrome: same node emission, their own file.
    private fun emitComponent(name: String, root: ResolvedNode, planned: PlannedFile): KtFile? {
        val body: KtExpr = emitNode(root, true) ?: return null
        val function: KtDeclaration.Function = KtDeclaration.Function(
            name,
            listOf(composable),
            listOf(modifierParam()),
            listOf(KtStmt.Expr(body)),
        )
        return KtFile(planned.packageName, headerText(), listOf(function))
    }

    private fun modifierParam(): KtParam =
        KtParam("modifier", modifierType, KtExpr.Ref(KtSymbolRef(modifierType)))

    private fun headerText(): String? =
        if (options.header == HeaderPolicy.Minimal) "// Generated by Forge. Do not edit." else null

    // The whole emitter: a registry lookup plus the binding's params, slots and events.
    private fun emitNode(node: ResolvedNode, isRoot: Boolean): KtExpr? {
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
        modifierArg(binding.modifierParam, node, isRoot)?.let { args += it }
        val kept: List<KeptParam> = keptParams(binding, node)
        for (keptParam in kept) {
            val value: KtExpr = paramValue(keptParam.binding, node) ?: return null
            val positional: Boolean = keptParam.binding.positional == Positional.WhenSole &&
                kept.size == 1 && keptParam.present.size == 1
            args += KtArg(if (positional) null else keptParam.binding.param, value)
        }
        var trailing: KtExpr.Lambda? = null
        for (slot in binding.slots) {
            val lambda: KtExpr.Lambda = slotLambda(slot, node) ?: return null
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

    private fun paramValue(binding: ParamBinding, node: ResolvedNode): KtExpr? {
        val emit = binding.emit
        if (emit is ValueEmit.Direct) {
            val key: PropertyKey? = binding.from.firstOrNull()
            if (key == null) throw CodegenBug("Param '${binding.param}' binds no property")
            val prop: ResolvedProp = checkNotNull(node.props[key]) {
                "Present property '${key.value}' left the node"
            }
            return propLiteral(prop, node)
        }
        if (emit is ValueEmit.Cases) {
            val match = emit.cases.firstOrNull { kase ->
                kase.whenPresent.all { node.props.containsKey(it) }
            }
            if (match == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "No emission case matches")
                return null
            }
            val filled = fillPattern(match.pattern, node) ?: return null
            return KtExpr.Snippet(filled.first, match.imports + filled.second)
        }
        throw CodegenBug("Unknown ValueEmit: " + emit)
    }

    // Every `{key}` becomes the property's literal; unknown or computed keys refuse.
    // Returns the text plus the literals' imports: a value filled into a pattern (a `dp`
    // inside `Arrangement.spacedBy({spacing})`) still needs its own symbols recorded.
    private fun fillPattern(pattern: String, node: ResolvedNode): Pair<String, List<KotlinSymbol>>? {
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
            val prop: ResolvedProp? = node.props[key]
            if (prop == null) {
                refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Pattern key '{$raw}' is absent")
                return null
            }
            val literal: KtExpr.Literal? = literalOf(prop, node)
            if (literal == null) return null
            filled.append(literal.text)
            symbols.addAll(literal.imports)
            index = close + 1
        }
        return filled.toString() to symbols
    }

    // A literal, or a refusal: computed values and unspeakable kinds emit nothing.
    private fun propLiteral(prop: ResolvedProp, node: ResolvedNode): KtExpr? =
        literalOf(prop, node)

    private fun literalOf(prop: ResolvedProp, node: ResolvedNode): KtExpr.Literal? {
        val stored = prop.value
        if (stored is PropertyValue.Computed) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Computed properties emit nothing yet")
            return null
        }
        val literal = LiteralPrinter.emit((stored as PropertyValue.Const).value)
        if (literal == null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Value kind emits nothing yet")
            return null
        }
        return KtExpr.Literal(literal.text, literal.symbols)
    }

    private fun slotLambda(binding: SlotBinding, node: ResolvedNode): KtExpr.Lambda? {
        if (binding.receiver != null) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Scoped slots emit nothing yet")
            return null
        }
        val children: List<ResolvedNode> = node.slots[binding.slot] ?: emptyList()
        val body: MutableList<KtStmt> = mutableListOf()
        for (child in children) {
            body += KtStmt.Expr(emitNode(child, false) ?: return null)
        }
        return KtExpr.Lambda(emptyList(), body)
    }

    // Roots thread the caller's modifier; leaves start from `Modifier`. No entry, no arg.
    private fun modifierArg(param: String?, node: ResolvedNode, isRoot: Boolean): KtArg? {
        val calls: MutableList<KtCall> = mutableListOf()
        for (entry in node.modifiers) calls += emitModifier(entry, node) ?: return null
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

    private fun emitModifier(entry: ResolvedModifier, node: ResolvedNode): KtCall? {
        val spec: ModifierSpec? = schema.modifiers[entry.type]
        if (spec == null) {
            refuse(DiagnosticCodes.CodegenNoBinding, node, "No spec for '${entry.type}'")
            return null
        }
        val emit = spec.emit
        // Shape-selected modifier calls need call-shaped patterns; deferred past the skeleton.
        if (emit.cases.isNotEmpty()) {
            refuse(DiagnosticCodes.CodegenStrategyUnsupported, node, "Modifier cases emit nothing yet")
            return null
        }
        val args: MutableList<KtArg> = mutableListOf()
        for (key in entry.args.keys.sortedBy { it.value }) {
            val prop: ResolvedProp = checkNotNull(entry.args[key]) {
                "Present modifier arg '${key.value}' left the entry"
            }
            args += KtArg(key.value, literalOf(prop, node) ?: return null)
        }
        return KtCall(emit.function, args)
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
