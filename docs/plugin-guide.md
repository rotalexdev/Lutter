# Authoring plugins

Everything the engine renders, validates, and emits code for comes out of a schema: a
component, a modifier, an enum, a function, or a type. The engine ships one schema pack —
the built-ins — as ordinary Kotlin (`engine:builtins` and `engine:builtins-compose`), and it
composes that pack the same way a plugin composes itself. There is no special machinery:
**a plugin is a Kotlin library that contributes registrations at four points, and the host
composes them with one added line each.**

This guide walks through the whole recipe with a real instance: the repository's
integration tests define a component that exists nowhere in the engine — a status badge at
`forge.status.Badge` — and every claim below is executable there, in
`integration/generated-compile/src/commonTest/kotlin/dev/rotalex/lutter/generated/compile/thirdparty/StatusBadge.kt`
and `ThirdPartyEngine.kt`. Read those two files alongside this page and you will see the
recipe compiled.

## The four registration points

| Point | What it holds | Where it lives |
|---|---|---|
| The spec | Component definition: properties, rules, slots, codegen binding | Your schema contribution |
| The renderer | How the component draws, live | Your `ComponentRenderer` object |
| The schema contribution | The `SchemaContribution` implementation | Your library's API |
| The runtime registration | The function that adds your renderer/applier to a registry | An extension on the registry builder |

The engine never mentions any of them. Nothing in `engine:*` grows a line when the plugin
arrives — that is the design constraint the recipe enforces.

## Step 1: define the spec

A spec declares the component's identity, its properties, its structural rules, and how
codegen should call it. All of it is a single DSL expression:

```kotlin
const val TYPE: String = "forge.status.Badge"

val label: PropertySpec<String> = prop("label", TypeRef.Str, required = true)
val accent: PropertySpec<TokenName?> = prop("accent", TypeRef.Nullable(TypeRef.Token(TokenKind.Color)))
val unread: PropertySpec<Int> = prop("unread", TypeRef.Int32)
val content: SlotName = SlotName("content")

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
    rule(PropertyRule.Range(unread.key, min = 0.0, max = 99.0))
    slot("content", Cardinality.ExactlyOne, ChildFilter.Only(setOf(contentType)))
    composeCall(KotlinSymbol("com.forge.status", "StatusBadge")) {
        param("label", from = label, positional = Positional.WhenSole)
        param("accent", from = accent)
        param("unread", from = unread)
        slot("content", LambdaTarget.Trailing)
    }
}
```

 Things worth noticing, because they are rules, not style:

- **Namespaces are mandatory.** `ComponentType` requires a namespaced string
  (`forge.status.Badge`), and the `doc.` prefix is reserved for document-level names — a
  plugin must not squat on it.
- **Declarative rules need no engine code.** `rule(PropertyRule.Range(...))` is enforced by
  the schema's own pass reading `spec.rules` off the registry. Validation you can express as
  a rule is validation the engine runs for you.
- **Typed tokens beat untyped ones.** `prop("accent", TypeRef.Token(TokenKind.Color))`
  declares a *colour-role* token; a document writing a token of the wrong kind is refused at
  validation with a `type_mismatch` diagnostic, which is exactly the failure mode untyped
  strings let through.
- **The codegen binding is part of the spec.** `composeCall { ... }` spells how generated
  Kotlin calls your component: which package/class, which properties become arguments, how
  slots map (a lone property shortens to a positional argument with `Positional.WhenSole`).
  Binding variants and their generator dispatch live in
  `engine/schema/src/commonMain/kotlin/dev/rotalex/lutter/schema/component/CodegenBinding.kt`.
- **Child filters may name engine components.** `ChildFilter.Only(setOf(ComponentType("m3.Text")))`
  is compared against the shared component registry, so your filter and the engine agree
  about what matches — you never re-declare an engine type.

## Step 2: define the renderer

The live half is one object implementing a two-parameter Composable:

```kotlin
object StatusBadgeRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope) {
        val props = scope.props(node)
        Column(modifier = scope.modifierFor(node)) {
            Text(
                text = props[StatusBadgeSpec.label].orEmpty(),
                style = props[StatusBadgeSpec.accent]?.let { scope.theme.textStyle(it) }
                    ?: LocalTextStyle.current,
            )
            Text(text = (props[StatusBadgeSpec.unread] ?: 0).toString())
            scope.RenderSlot(node, StatusBadgeSpec.content)
        }
    }
}
```

The contract is sharp:

- A renderer receives **only** the `ResolvedNode` and a `RenderScope` — never a document,
  never a schema, never a registry. All values arrive through the scope: `props` by
  property handle (so the *spec*, not a string literal, is the source of truth), `theme`
  for token resolution, `modifierFor` for the modifier chain, `RenderSlot` for children.
- Drawing primitives are reached directly — the Compose `Text` call above is exactly what
  the engine's own `TextRenderer` does. What the contract withholds is the engine's
  knowledge, not Compose's.
- Registration is one call on the registry builder, `register(spec.type, this)`, and the
  registry refuses duplicate registrations — a plugin can never silently shadow a built-in.
- The engine is fail-fast about coverage: constructing a `UiRuntime` over a schema whose
  components include one without a renderer refuses to construct rather than rendering a
  blank box at layout time. If your runtime assembly forgets your renderer, you find out at
  startup, not in production pixels.

## Step 3: package the contribution

The schema half is an implementation of `SchemaContribution` — the same interface the
built-in pack implements. Type parameters are the five registry slots; a plugin
instantiates them the same way its host does, so `contribute` can add a `ComponentSpec`
with no casts:

```kotlin
object StatusBadgeContribution :
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
```

The runtime half is a plain extension function on `RendererRegistryBuilder` (and, if your
component ships custom modifiers, one on `ModifierApplierRegistryBuilder`):

```kotlin
fun RendererRegistryBuilder.registerStatusBadge(): Unit =
    register(StatusBadgeSpec.spec.type, StatusBadgeRenderer)
```

## Step 4: compose the host

The host assembles registries from the built-in pack plus your line:

```kotlin
val schema = Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
    registerBuiltinSpecs()
    registerBuiltinModifiers()
    registerBuiltinEnums()
    registerBuiltinFunctions()
    registerBuiltinActions()
    StatusBadgeContribution.contribute(this)   // the line
}

val renderers = RendererRegistryBuilder().apply {
    registerBuiltinRenderers()
    registerStatusBadge()                      // the line
}.build()
val modifiers = ModifierApplierRegistryBuilder()
    .apply { registerBuiltinModifierAppliers() }
    .build()
val runtime = UiRuntime(renderers, modifiers, Implementations(builtinFunctionImpls()), schema)
```

`ThirdPartyEngine.kt` builds the exact assembly above in two forms — with and without the
plugin's line — and its tests assert that the plugin's addition changes nothing else and
that coverage, codegen, and rendering all still hold. The host's total cost is two lines.

Once the schema is composed, everything downstream is plugin-agnostic: the CLI (`forge
check`, `forge generate`) and the codegen run over any schema the same way, and generated
code for your component emits calls exactly as the `composeCall` binding spelled them.

## Plugins for the other four kinds

Components are the worked example, but the five registry slots are peers. The built-in pack
(`engine:builtins`, `engine:builtins-compose`) registers each kind through the same four
points a plugin uses — `registerBuiltinSpecs`, `registerBuiltinModifiers`,
`registerBuiltinEnums`, `registerBuiltinFunctions`, `registerBuiltinActions` on the schema
side, and `registerBuiltinRenderers` / `registerBuiltinModifierAppliers` on the runtime
side. Contributing a modifier, enum, function or action follows its built-in sibling with
your namespace; the builder call (`builder.modifier(...)`, `builder.action(...)`, and so
on) is the only thing that changes.

## Today's edges, stated plainly

- **Distribution is an ordinary library dependency.** A plugin ships as a Kotlin module on
  the host's classpath; there is no plugin discovery and no dynamic loading today.
- **The descriptor is declared, not yet enforced.** `PluginDescriptor` (id, version, API
  version, dependencies) is part of the surface, but nothing currently reads it — a host
  composes explicitly, which is why the recipe is four functions instead of an installer.
- **Configurable registration stays with your library.** The extension function is what a
  host calls; if your plugin needs parameters, your extension function takes them.
