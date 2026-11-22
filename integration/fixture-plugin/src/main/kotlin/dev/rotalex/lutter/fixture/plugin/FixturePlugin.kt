package dev.rotalex.lutter.fixture.plugin

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.plugin.PluginDescriptor
import dev.rotalex.lutter.schema.plugin.SchemaContribution
import dev.rotalex.lutter.schema.types.TypeSpec

/**
 * A plugin as a third party writes one (phase 16, P5).
 *
 * This module exists so the rest of the phase has something real to package and load: P6's Gradle
 * plugin turns it into a `*.lutterplugin`, and P7's CLI loads that container and installs what is
 * inside. Nothing in `:engine:*` names this file — the component reaches the engine through
 * `SchemaBuilder.component` and `SchemaContribution`, which are §7.4's published extension points.
 *
 * Two choices worth stating, because they are the ones a reader of a fixture should be able to
 * check rather than trust:
 *
 * - **`dependsOn` is empty.** A fixture that declared a dependency and then relied on the loader to
 *   ignore it would be a fixture teaching the wrong lesson; this plugin genuinely needs nothing but
 *   the engine's own API.
 * - **The component type is namespaced and not `doc.`.** `ComponentType` requires a namespace, and
 *   `doc.` is §8.4's reserved prefix for document-declared components, so a plugin squatting on it
 *   would shadow the overlay's namespace.
 */
public object FixturePlugin : SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {

    /**
     * The fully-qualified name a manifest's `entryPoints.schema` names — `object`, so the loader
     * finds it through `Class.forName(...).kotlin.objectInstance` rather than a constructor.
     *
     * It is a constant beside the declaration instead of a string in the packaging plugin, so a
     * rename breaks the compile rather than the load.
     */
    public const val SCHEMA_ENTRY_POINT: String =
        "dev.rotalex.lutter.fixture.plugin.FixturePlugin"

    /** How this plugin identifies itself to `SchemaBuilder.install` and to a document. */
    override public val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId("forge.fixture"),
        version = "1.0.0",
        // The engine API this plugin was built against. Equal to `PluginApiVersion.Supported`, so a
        // container carrying this manifest loads in this tree and a newer engine would report the
        // mismatch by number rather than by a crash.
        apiVersion = 1,
    )

    /** The badge's line of text: required, so a badge always says what it is about. */
    public val label: PropertySpec<String> = prop("label", TypeRef.Str, required = true)

    /** The one line drawn under the label; an engine type, found in the registry the host shares. */
    public val content: SlotName = SlotName("content")

    /** The spec the validator and the generator both read. */
    public val spec: ComponentSpec = componentSpec(ComponentType("fixture.badge.Badge"), version = 1) {
        metadata(
            displayName = "Fixture badge",
            category = Category.Basic,
            description = "The component the phase 16 fixture plugin contributes.",
            keywords = listOf("fixture", "badge", "plugin"),
        )
        property(label)
        slot("content", Cardinality.ZeroOrOne, ChildFilter.Only(setOf(ComponentType("m3.Text"))))
        composeCall(KotlinSymbol("com.forge.fixture", "FixtureBadge")) {
            param("label", from = label)
            slot("content", LambdaTarget.Trailing)
        }
    }

    override fun contribute(
        builder: SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    ): Unit {
        builder.component(spec)
    }

}
