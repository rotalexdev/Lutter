package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiRuntime
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec

/**
 * One assembly for the conformance tests: a schema, a runtime and a decode path.
 *
 * The engine is [BuiltinEngine]'s, shared with `:samples:desktop-preview`, so the suite and the
 * sample cannot drift into two different answers about what a running engine is. Building the
 * runtime constructs it, which runs the renderer coverage check; generation runs its own check,
 * so both fail-fast gates execute on every fixture.
 */
internal object ConformanceHarness {

    /** The builtins pack's schema, with the order its five registries are registered in. */
    fun schema(): Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
        BuiltinEngine.schema()

    /** A runtime over [schema]. Construction itself proves renderer and applier coverage. */
    fun runtime(
        schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    ): UiRuntime = BuiltinEngine.runtime(schema)

    /** A host that records nothing: the fixtures navigate nowhere and hold no state. */
    fun environment(): RuntimeEnvironment =
        RuntimeEnvironment(navigator = TestNavigator, diagnostics = {})

    /**
     * The fixture decoded and analyzed, diagnostics included for the tests to assert on.
     *
     * [documents] names the generated constants to read from. It is a parameter because the
     * fixtures are generated into two objects: a screen whose handlers read a receiver takes it
     * as a parameter no registry entry can supply, so those fixtures get no registry entry.
     */
    fun analyze(
        schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
        id: String,
        documents: (String) -> String = FixtureDocuments::json,
    ): AnalysisResult {
        val document: UiDocument =
            JsonDocumentCodec.decode(documents(id).encodeToByteArray()).document
        return BuiltinEngine.analyze(schema, document)
    }

    private object TestNavigator : Navigator {
        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}
