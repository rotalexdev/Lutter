package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.builtinFunctionImpls
import dev.rotalex.lutter.builtins.registerBuiltinActions
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinFunctions
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.runtime.Implementations
import dev.rotalex.lutter.runtime.ModifierApplierRegistryBuilder
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import dev.rotalex.lutter.runtime.UiRuntime
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec

/**
 * The builtins pack assembled into a running engine: a schema, a runtime over it, a document.
 *
 * Every registry the pack owns is registered by whoever assembles it, in one order, so an
 * application and a test suite that each spell it out are two answers to one question. Building
 * the runtime catches a missing renderer or applier; nothing catches a spec registered against
 * the wrong registry, which is the failure this object exists to make unspellable.
 */
public object BuiltinEngine {

    /** The pack's schema: components, modifiers, enums, seed functions and the MVP actions. */
    public fun schema(): Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
        Schema.build {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
            registerBuiltinEnums()
            registerBuiltinFunctions()
            registerBuiltinActions()
        }

    /** A runtime over [schema]. Its construction is the renderer and applier coverage check. */
    public fun runtime(
        schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    ): UiRuntime {
        val renderers = RendererRegistryBuilder().apply { registerBuiltinRenderers() }.build()
        val modifiers = ModifierApplierRegistryBuilder()
            .apply { registerBuiltinModifierAppliers() }
            .build()
        return UiRuntime(renderers, modifiers, Implementations(builtinFunctionImpls()), schema)
    }

    /** The document lowered, findings included, for a caller that reads them rather than renders. */
    public fun analyze(
        schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
        document: UiDocument,
    ): AnalysisResult = Analyzer(schema).analyze(document)

    /**
     * The document lowered, or a refusal naming why.
     *
     * For the caller that renders: a finding about a page is a blank window, so it belongs in
     * the failure a window cannot open with rather than on the screen it opens with.
     */
    public fun resolve(
        schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
        document: UiDocument,
    ): ResolvedDocument {
        val result = analyze(schema, document)
        check(!result.hasErrors) { "The document does not resolve: ${result.diagnostics}" }
        return checkNotNull(result.resolved) { "The document resolved nothing" }
    }
}
