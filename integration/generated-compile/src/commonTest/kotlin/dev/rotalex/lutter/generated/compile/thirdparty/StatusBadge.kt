package dev.rotalex.lutter.generated.compile.thirdparty

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.RendererRegistryBuilder
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.Positional
import dev.rotalex.lutter.schema.component.PropertyRule
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
 * A component that exists nowhere in the engine, and the two registration surfaces it needs.
 *
 * PLAN §7.4's recipe, all four steps, in this one file: [StatusBadgeSpec] is step (1) the spec,
 * [StatusBadgeRenderer] is step (2) the renderer, [StatusBadgeContribution] is step (3) the schema
 * half and [registerStatusBadge] the runtime half. Step (4) is the test, which is a separate file
 * because a fixture and the claims about it are different things.
 *
 * ### Why this lives in a test source set
 *
 * PLAN §7.4 says a component defined in a *test-only module*, and `:integration:generated-compile`
 * is one: its `commonMain` holds two files whose only content is paths and a package, and every
 * assertion in it is in a test source set. Putting the component in `commonTest` rather than
 * `commonMain` keeps that true at the source-set level too, and has a second effect worth having:
 * KGP's ABI validation covers main compilations only, so nothing here can move an `api/*.api` dump.
 * The alternative — a class in `commonTest` of an `engine:*` module — would be *in* that module for
 * every purpose `ModuleGraphRules` cares about, which is the distinction this test exists to blur
 * and could not.
 *
 * ### Why no engine file mentions any of this
 *
 * `forge.status.Badge` is not in `:engine:builtins`, `m3.Text` is not modified, and
 * `registerBuiltinRenderers` does not grow a line. The component reaches the engine through four
 * published extension points and nothing else: `SchemaBuilder.component`, `RendererRegistryBuilder.register`,
 * `SchemaContribution.contribute`, and `ComponentRenderer`. If a fourth stage needed a fifth one,
 * §7.4's claim would be false and this file would not compile.
 */
internal object StatusBadgeSpec {

    /** The plugin's own namespace. Namespaced because `ComponentType` requires it, and because
     *  `doc.` is §8.4's reserved prefix and a plugin must not squat on it. */
    const val TYPE: String = "forge.status.Badge"

    /** The badge's line of text. Required, so a badge always says what it is about. */
    val label: PropertySpec<String> = prop("label", TypeRef.Str, required = true)

    /**
     * A colour-role token, not a literal colour, so the badge follows the selected theme.
     *
     * Declared as `Token(Color)` and *not* as an untyped token: §5.4's `Value.Token` carries its
     * own `TokenKind`, and `ThemeTokenValueKind.accepts` compares that kind against the one the
     * property declares. A document writing `md.typography.bodyLarge` here with the kind the
     * property did not declare is refused by `prop.type_mismatch` — which is the corpus defect
     * this shape exists to keep fixed.
     */
    val accent: PropertySpec<TokenName?> =
        prop("accent", TypeRef.Nullable(TypeRef.Token(TokenKind.Color)))

    /** How many unread items the badge counts. The bound is a rule, not a narrower type. */
    val unread: PropertySpec<Int> = prop("unread", TypeRef.Int32)

    /** The one line drawn under the label. */
    val content: SlotName = SlotName("content")

    /**
     * The engine type that line has to be.
     *
     * A plugin naming an *engine* type in its own `ChildFilter` is the claim worth making here:
     * the filter is compared against the component registry, and the registry is shared, so the
     * two halves can only agree because the plugin found the engine's entry rather than
     * re-declaring it. `m3.Text` is also the only child type with both a renderer and a codegen
     * binding, so a document that satisfies this filter generates and draws.
     */
    val contentType: ComponentType = ComponentType("m3.Text")

    /** The spec the renderer, validation and codegen all share. */
    val spec: ComponentSpec = componentSpec(ComponentType(TYPE), version = 1) {
        metadata(
            displayName = "Status badge",
            category = Category.Basic,
            description = "A label with an accent colour, an unread count and one line of detail.",
            keywords = listOf("badge", "status", "notification"),
        )
        property(label)
        property(accent)
        property(unread)
        // A third-party declarative rule, from §17.4's family, on a property the engine has
        // never heard of. `SchemaPass` reads `spec.rules` off the registry, so this is enforced
        // without a line of engine code.
        rule(PropertyRule.Range(unread.key, min = 0.0, max = 99.0))
        // Cardinality and filter together, deliberately: §7.1 gives them as two independent facts
        // and `SchemaPass.checkSlots` evaluates them as two independent checks, so the test has to
        // be able to fail one without the other.
        slot("content", Cardinality.ExactlyOne, ChildFilter.Only(setOf(contentType)))
        composeCall(KotlinSymbol("com.forge.status", "StatusBadge")) {
            // §7.4's own line: a lone property shortens to a positional argument.
            param("label", from = label, positional = Positional.WhenSole)
            param("accent", from = accent)
            param("unread", from = unread)
            slot("content", LambdaTarget.Trailing)
        }
    }
}

/**
 * Step (2): the live half, drawing what step (1) declared.
 *
 * `ComponentRenderer.Render` is `@Composable` and receives only a [ResolvedNode] and a
 * [RenderScope] — never a document, never a schema, never a registry. That is the whole of what a
 * third-party renderer is allowed to touch, and it is why the engine needs no knowledge of this
 * object at all.
 *
 * Every read goes through [RenderScope.props] by property handle, so the key is the one the spec
 * declared rather than a string this file and the spec could disagree about. The accent is
 * resolved through [RenderScope.theme] rather than drawn as its raw name, which is the runtime's
 * half of §14.2's token row and is the side that makes the kind mismatch the test asserts a
 * disagreement rather than a typo.
 */
internal object StatusBadgeRenderer : ComponentRenderer {

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope) {
        val props = scope.props(node)
        Column(modifier = scope.modifierFor(node)) {
            // Positional, not `text =`: the parameter's name is Compose's, not this file's, and
            // a named argument here would be a claim about a library rather than about the engine.
            Text(
                props[StatusBadgeSpec.label].orEmpty(),
                style = props[StatusBadgeSpec.accent]?.let { scope.theme.textStyle(it) },
            )
            Text((props[StatusBadgeSpec.unread] ?: 0).toString())
            scope.RenderSlot(node, StatusBadgeSpec.content)
        }
    }
}

/**
 * Step (3), schema half: what §8.2 calls the facet implemented by builtins *and by plugins*.
 *
 * The five type parameters are the builder's own, bound to the concrete spec types an assembly
 * uses. A plugin instantiates `SchemaContribution` at the same instantiation its host assembly
 * does, which is why `contribute` can add a `ComponentSpec` to a `SchemaBuilder` whose other four
 * slots are `ModifierSpec`, `ActionSpec`, `FunctionSpec` and `TypeSpec` without a cast.
 *
 * ### What the descriptor is for, today
 *
 * `dependsOn` names `forge.builtins`, and nothing reads it. `PluginDescriptor` is a record
 * (§27.1); §27.2's check belongs to a loader that does not exist, and §8.2's `Schema` has no
 * `contributors` field, so a built schema cannot report who contributed to it either. The
 * dependency is declared anyway, because the recipe says to declare it and because a test that
 * only exercised the working half would let a later change to the descriptor pass unnoticed.
 */
internal object StatusBadgeContribution :
    SchemaContribution<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId("forge.status"),
        version = "0.3.0",
        apiVersion = 1,
        dependsOn = listOf(PluginRequirement(PluginId("forge.builtins"), "1.0")),
    )

    override fun contribute(
        builder: SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    ): Unit {
        builder.component(StatusBadgeSpec.spec)
    }
}

/**
 * Step (3), runtime half — the shape `registerBuiltinRenderers` has, one function per pack.
 *
 * PLAN §36's row says "`SchemaContribution` + `RuntimeContribution`", and §33.2's file manifest
 * names `contribution/RuntimeContribution.kt`. **That file does not exist**, and neither does the
 * type: `RuntimeContribution` appears in this repository only in `PLAN.md`. The runtime facet that
 * does exist is the pair of registry builders, and this function is the plugin's half of it —
 * one `register` call against the engine's own `ComponentRenderer` interface.
 *
 * The missing type is recorded rather than papered over: naming this `RuntimeContribution` would
 * make the row look ticked while the extension point §7.4 promises still has no interface to
 * implement, only a builder to call.
 */
internal fun RendererRegistryBuilder.registerStatusBadge(): Unit =
    register(StatusBadgeSpec.spec.type, StatusBadgeRenderer)