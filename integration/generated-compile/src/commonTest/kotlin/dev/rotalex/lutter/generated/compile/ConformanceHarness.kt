package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.builtins.compose.registerBuiltinModifierAppliers
import dev.rotalex.lutter.builtins.compose.registerBuiltinRenderers
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.Implementations
import dev.rotalex.lutter.runtime.ModifierApplierRegistryBuilder
import dev.rotalex.lutter.runtime.Navigator
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiRuntime
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec

/**
 * One assembly for the conformance tests: a schema, a runtime and a decode path.
 *
 * Building the runtime constructs it, which runs the renderer coverage check;
 * generation runs its own check, so both fail-fast gates execute on every fixture.
 */
internal object ConformanceHarness {
    /** The walking-skeleton schema: the builtin component specs and the §31.2 modifier set. */
    fun schema(): Schema<ComponentSpec, ModifierSpec, Unit, Unit, Unit> =
        Schema.build {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
        }

    /** A runtime over [schema]. Construction itself proves renderer and applier coverage. */
    fun runtime(schema: Schema<ComponentSpec, ModifierSpec, Unit, Unit, Unit>): UiRuntime {
        val renderers = RendererRegistryBuilder().apply { registerBuiltinRenderers() }.build()
        val modifiers = ModifierApplierRegistryBuilder()
            .apply { registerBuiltinModifierAppliers() }
            .build()
        return UiRuntime(renderers, modifiers, Implementations.None, schema)
    }

    /** A host that records nothing: the fixtures navigate nowhere and hold no state. */
    fun environment(): RuntimeEnvironment =
        RuntimeEnvironment(navigator = TestNavigator, diagnostics = {})

    /** The fixture decoded and analyzed, diagnostics included for the tests to assert on. */
    fun analyze(
        schema: Schema<ComponentSpec, ModifierSpec, Unit, Unit, Unit>,
        id: String,
    ): AnalysisResult {
        val document: UiDocument =
            JsonDocumentCodec.decode(FixtureDocuments.json(id).encodeToByteArray()).document
        return Analyzer(schema).analyze(document)
    }

    private object TestNavigator : Navigator {
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}
