package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.graphics.ImageBitmap
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.LoadedResource
import dev.rotalex.lutter.runtime.ResourceProvider
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
    fun environment(): RuntimeEnvironment = RuntimeEnvironment(
        navigator = TestNavigator,
        diagnostics = {},
        resources = StubResources,
    )

    /**
     * The interpreter path's resource provider: any image-kind load answers a small decoded
     * bitmap, because what the conformance dump reads is the wiring — reference, provider,
     * renderer — and not the pixels. A real host implements its own loaders (§20:1598 puts
     * them per platform); the generated path's spelling is compose-resources' and is proven
     * by compiling the generated screens against this module's own `composeResources`.
     */
    private object StubResources : ResourceProvider {
        override suspend fun load(id: ResourceId): LoadedResource =
            // The 32x32 is the fixture asset's own dimensions, not a tolerance: intrinsic size
            // is what layout reads, and the generated path decodes the real 32x32 bytes. A
            // stub of any other size lays the same fixture out differently on the two sides
            // of the runtime/generated comparison, which that check would read as a break.
            LoadedResource.Image(ImageBitmap(32, 32))

        override suspend fun stringFor(id: ResourceId, locale: String): String? = null
    }

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
