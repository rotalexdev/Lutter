package dev.rotalex.lutter.cli.plugin

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
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
 * A plugin this module's tests package into a container and then load (phase 16, P7).
 *
 * Deliberately **not** `:integration:fixture-plugin`'s `FixturePlugin`: `ALLOWED_IN_TESTS` has no
 * line for `:tools:cli`, so a test-scoped edge to that module would fail `verifyModuleGraph`, and
 * reaching it through the filesystem would tie these tests to another module's build directory.
 * The lesson P5 already taught — a plugin is a `SchemaContribution` and nothing more — is what
 * lets a ten-line fixture stand in for a third party's.
 *
 * The identity differs from the integration fixture for a second reason: an assertion that the
 * loaded manifest's `id` is `forge.clitest` cannot accidentally pass because some other plugin
 * in the tree happens to be called `forge.fixture`.
 */
public object CliFixturePlugin :
    SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {

    /** The FQCN a manifest's `entryPoints.schema` names. Read from here, never retyped. */
    public const val SCHEMA_ENTRY_POINT: String = "dev.rotalex.lutter.cli.plugin.CliFixturePlugin"

    /** The plugin's identity: what a document's `meta.plugins` has to name. */
    public const val PLUGIN_ID: String = "forge.clitest"

    /** The plugin's version. Exact string equality, per ADR-018. */
    public const val PLUGIN_VERSION: String = "1.0.0"

    /** The component the loaded plugin makes valid. */
    public const val COMPONENT_TYPE: String = "cli.notice.Notice"

    override public val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId(PLUGIN_ID),
        version = PLUGIN_VERSION,
        apiVersion = 1,
    )

    /** The notice's line of text. */
    public val text: PropertySpec<String> = prop("text", TypeRef.Str, required = true)

    /** What the validator and the generator read once the plugin is installed. */
    public val spec: ComponentSpec = componentSpec(ComponentType(COMPONENT_TYPE), version = 1) {
        metadata(
            displayName = "CLI fixture notice",
            category = Category.Basic,
            description = "The component the CLI's plugin-loading fixture contributes.",
            keywords = listOf("fixture", "notice", "cli"),
        )
        property(text)
        composeCall(KotlinSymbol("com.forge.clifixture", "CliFixtureNotice")) {
            param("text", from = text)
        }
    }

    override fun contribute(
        builder: SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    ): Unit {
        builder.component(spec)
    }
}
