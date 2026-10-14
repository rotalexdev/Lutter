package dev.rotalex.lutter.generated.compile.thirdparty

import dev.rotalex.lutter.builtins.builtinFunctionImpls
import dev.rotalex.lutter.builtins.registerBuiltinActions
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinFunctions
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.builtins.compose.registerBuiltinModifierAppliers
import dev.rotalex.lutter.builtins.compose.registerBuiltinRenderers
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
 * The concrete instantiation every engine consumer uses: `BuiltinEngine`'s, spelled once.
 *
 * Named rather than repeated so a change of instantiation is one edit, and so the four assemblies
 * below cannot drift into four different answers to "what is a schema here".
 */
internal typealias EngineSchema = Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>

/**
 * An engine assembled by a test: the builtins pack, plus one component that is not in it.
 *
 * `BuiltinEngine` is the engine's own assembly and cannot be used here, and the reason is the
 * point of the whole file. It spells out its own five registrations and its own two registry
 * builds; a plugin that wanted to join it would have to edit it. So this file re-spells them with
 * one line added to each — which is the claim, made executable: **the plugin's addition is a line,
 * not a change.**
 *
 * The five `registerBuiltin*` calls and the two `registerBuiltin*` renderer/applier registrations
 * are copied from `BuiltinEngine`, deliberately rather than reused. Reusing them would mean
 * `BuiltinEngine` grew a parameter and the engine file changed, which is the one outcome §36's row
 * rules out. A copy that can drift is the lesser cost here, and the drift is visible: if the
 * builtins pack grows a sixth registry, this file stops compiling at the call rather than
 * silently assembling a smaller engine than the one it is claiming to match.
 */
internal object ThirdPartyEngine {

    /**
     * The builtins pack and nothing else — what an assembly that had never heard of the plugin
     * would build, and the negative half of the renderer-coverage assertion.
     */
    fun builtinSchema(): EngineSchema =
        Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
            registerBuiltinEnums()
            registerBuiltinFunctions()
            registerBuiltinActions()
        }

    /** The same pack with [StatusBadgeContribution] applied last, in §8.2's "plugins at composition time". */
    fun schema(): EngineSchema = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
        registerBuiltinSpecs()
        registerBuiltinModifiers()
        registerBuiltinEnums()
        registerBuiltinFunctions()
        registerBuiltinActions()
        StatusBadgeContribution.contribute(this)
    }

    /**
     * A running engine over [schema].
     *
     * [withPluginRenderers] exists so the test can build the runtime an assembly that forgot the
     * plugin's renderer would build. `UiRuntime`'s `init` runs `RuntimeCoverage.check`, which is
     * the engine's §4.5 fail-fast: a schema component with no renderer refuses construction rather
     * than drawing a blank box. Without the switch, "the plugin's renderer was required" could
     * only be asserted by the exception the plugin's own registration prevents.
     */
    fun runtime(schema: EngineSchema, withPluginRenderers: Boolean = true): UiRuntime {
        val renderers = RendererRegistryBuilder().apply {
            registerBuiltinRenderers()
            if (withPluginRenderers) registerStatusBadge()
        }.build()
        val modifiers = ModifierApplierRegistryBuilder()
            .apply { registerBuiltinModifierAppliers() }
            .build()
        return UiRuntime(renderers, modifiers, Implementations(builtinFunctionImpls()), schema)
    }
}