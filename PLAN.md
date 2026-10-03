# PLAN.md

> **Working name:** Forge Engine (`dev.rotalex.lutter.*`). The name is a placeholder; renaming is a mechanical change.
> **Nature of this document:** an implementation plan. It is meant to be executed phase by phase by a senior developer or a coding agent without redesigning the architecture.
> **Version policy:** no dependency versions are pinned here. All versions live in `gradle/libs.versions.toml` (see §22).

---

## 1. Project Definition

Forge Engine is a **UI document engine** for Kotlin Multiplatform and Compose Multiplatform. It is the foundation of a FlutterFlow-like product, but it is **not** the visual editor and **not** an IDE.

The engine takes one authoritative, serializable, versioned **UI document** (records: pages, nodes, components, state, themes, resources) and provides:

1. **Schema and registries** describing which components, modifiers, actions and functions exist.
2. **Validation and resolution**, which turn a raw document into a typed, defaults-applied `ResolvedDocument`.
3. **A Compose runtime renderer** that renders the resolved document live.
4. **A Kotlin/Compose code generator** that emits deterministic, idiomatic source code from the same resolved document.
5. **Serialization and schema migrations** for a stable on-disk format.
6. **An editor-agnostic editing API** (commands, patches, history) that a future visual editor, CLI, test, or server consumes.

```
                  ┌──────────────────────┐
                  │     Visual Editor    │   (future client, NOT in this plan's MVP)
                  └──────────┬───────────┘
                             │ uses
┌────────────────────────────▼────────────────────────────┐
│                      FORGE ENGINE                       │
│  Document model · Schema/Registries · Type system       │
│  Expressions · Actions · Validation/Resolution          │
│  Runtime (Compose) · Code generation · Serialization    │
└────────────────────────────┬────────────────────────────┘
                             ▼
              Kotlin Multiplatform / Compose Multiplatform
```

Deliverables of the MVP: engine libraries (Gradle modules), a JVM CLI (`forge validate|generate|render-check`), and a minimal desktop *preview sample* that renders a JSON document. The sample is a demonstration of the runtime, **not** an editor.

---

## 2. Goals

| # | Goal | How it is verified |
|---|------|--------------------|
| G1 | One authoritative document model shared by runtime and codegen | Both consume only `ResolvedDocument`; conformance suite (§28.4) |
| G2 | Registry-driven extensibility: new components need no change to engine modules | `ThirdPartyComponentTest` adds a component from a test module only |
| G3 | Strongly typed property/value/type system; no `Map<String, Any>` domain model | Konsist rule + code review |
| G4 | Deterministic serialization, validation, resolution, migration and code generation | Byte-equality tests, permutation tests, cross-target golden runs |
| G5 | Clean, idiomatic generated Compose code that compiles | Golden tests + compile harness (§28.5) |
| G6 | Versioned format with a real migration chain | Frozen historical fixtures (§19) |
| G7 | Core domain has zero Compose dependency | Module graph check + Konsist |
| G8 | Editor-agnostic API with patches, undo/redo, structured diagnostics | `:engine:editing` tests; CLI uses the same APIs |
| G9 | KMP-ready: `commonMain` first; Android + Desktop now; iOS/Wasm/JS later without domain rewrites | Compile canaries for iOS/Wasm on pure modules from Phase 0 |
| G10 | Small coherent architecture; every module and abstraction has a concrete reason | §22 justification table, §35 anti-patterns |

---

## 3. Non-Goals

- Not an IDE: no code editor, debugger, terminal, LSP, project explorer.
- No visual editor in this plan (only the API it will consume, §25).
- No arbitrary Kotlin execution and no general-purpose scripting language. Expressions are a small, typed, total-ish language (§10). Custom logic goes through declared **host functions** (§11.6).
- No round-trip: the engine never parses Kotlin back into a document. Generated code is a **one-way** artifact (ADR-011).
- No plugin marketplace, no dynamic plugin loading in MVP (interfaces only, §27).
- No full FlutterFlow parity: no backend integrations, auth, database designers, or asset pipelines.
- No complete Compose coverage. MVP ships a deliberately small component set that proves every mechanism.
- No build-system/project scaffolding in MVP (designed, postponed to post-MVP, §33/§32).
- No collaborative editing/OT in MVP (patch design keeps the door open, §26).

---

## 4. Architecture Overview

### 4.1 Layers and data flow

```
                        ┌───────────────────────┐
                        │      UiDocument       │  records: pages, nodes, components,
                        │   (model, pure data)  │  state, themes, resources
                        └───────────┬───────────┘
                                    │  + Schema (registries of specs)
                                    ▼
                        ┌───────────────────────┐
                        │       Analyzer        │  structural → schema → refs →
                        │ validate + resolve    │  expressions → actions → codegen-feasibility
                        └───────────┬───────────┘
                     Diagnostics ◄──┤
                                    ▼
                        ┌───────────────────────┐
                        │   ResolvedDocument    │  derived, ephemeral, NEVER serialized:
                        │ (typed, defaults set) │  typed props, typed exprs, scopes, tokens
                        └───────┬───────┬───────┘
                                │       │
               ┌────────────────▼─┐   ┌─▼─────────────────┐
               │ Runtime (Compose)│   │ Codegen (pure KMP)│
               │ renderers +      │   │ Kotlin IR +       │
               │ interpreter      │   │ deterministic     │
               │                  │   │ printer           │
               └────────┬─────────┘   └─────────┬─────────┘
                        ▼                       ▼
                  Composable UI          Kotlin source files
```

Side pipelines:

```
JSON bytes → Envelope → Migration chain (on JsonElement) → decode UiDocument   (serialization)
Editor/CLI → Command → Patch(+inverse) → UiDocument' → (re)analyze              (editing)
```

### 4.2 Key structural idea: split the "component definition" by capability

The prompt sketches a single `ComponentDefinition` that has `createRuntime()` and `generateCode()`. That is **rejected** because it forces the registry (and therefore the schema) to depend on both Compose and the code generator, violating dependency direction and portability. Instead:

| Concern | Artifact | Lives in | Depends on Compose? |
|---|---|---|---|
| What a component *is* (props, slots, events, metadata, **declarative Kotlin/Compose mapping**) | `ComponentSpec` | `:engine:schema` (data) / `:engine:builtins` (instances) | No |
| How to render it live | `ComponentRenderer` | `:engine:runtime` (interface) / `:engine:builtins-compose` (impls) | Yes |
| How to emit Kotlin | `CodegenBinding` **inside** the spec (data), interpreted by the generic emitter | `:engine:schema` / `:engine:codegen` | No |

Consistency between the renderer and the emitted code is guaranteed by a **single resolution pipeline**, **shared value-kind tables**, **coverage checks** and a **conformance suite** (§4.5).

### 4.3 Contradictions and unnecessary abstractions found in the requirements (resolved)

| # | Observation | Resolution |
|---|---|---|
| C1 | `ComponentDefinition.createRuntime()` + `generateCode()` couples spec, Compose and codegen | Split by capability (§4.2). |
| C2 | Requirement "runtime and codegen consume the same model" vs. "each component generates its own code" implies duplicate semantics | Introduce `ResolvedDocument` as the only input of both; codegen is mostly **data-driven** from `CodegenBinding`. |
| C3 | 14 candidate registries | Only **5 schema registries + 2 runtime registries** are justified (§8). PropertyRegistry, EventRegistry, LayoutRegistry, ThemeRegistry, AssetRegistry, PlatformRegistry, PluginRegistry, SerializerRegistry, CodeGeneratorRegistry are **rejected** with reasons. |
| C4 | Proposed modules `:registry`, `:theme`, `:actions`, `:navigation` | Rejected. Registries live with the schema; themes/actions/navigation are *document records* plus specs/handlers in existing modules. |
| C5 | "Serialization separate from model" vs. "model must not depend on a format" | Model carries plain `kotlinx.serialization` **annotations** (format-agnostic); `:engine:serialization` owns formats, envelope, canonical JSON and migrations (ADR-005). |
| C6 | KotlinPoet is the obvious choice, but codegen must run in `commonMain` (CLI, future web editor) | KotlinPoet is JVM-only (verify at kickoff); use a small custom Kotlin IR + printer (ADR-004). |
| C7 | "Document is serializable" vs. "expressions/actions/handlers" | Handlers are **data** (`ActionSequence`), never lambdas. Behaviour lives in registries keyed by ids. |
| C8 | "Support CBOR later without touching the model" vs. "migrations" | Migrations always operate on a JSON tree. Binary formats are an encoding of the *current* schema only (ADR-006). |
| C9 | Expression language ambition vs. "no full language" | Minimal typed AST, closed operator set, open function registry (§10). |
| C10 | `float.toString()` differs across Kotlin targets (JS prints `1.0` as `1`) → breaks determinism | Canonical numeric formatting owned by the model (§5.4, difficult decision D1). |
| C11 | "Persistent/external state" vs. MVP | Modeled as hooks (`Persistence`, `StateStrategy`), implemented post-MVP. |
| C12 | Two-way bindings are convenient but create hidden semantics | Not in MVP; state read + `state.set` action is explicit (ADR-009). |

### 4.4 `commonMain` vs platform source sets

| Belongs in `commonMain` | Belongs in platform source sets |
|---|---|
| Entire domain: model, schema, serialization (JSON), interpreter, analysis, editing, codegen, builtins specs | Nothing domain-related |
| Compose runtime renderer (`runtime`, `builtins-compose`) — Compose Multiplatform is common | Platform *behaviour* only: back-handler wiring, file pickers, resource loading impls, filesystem-backed `DocumentStorage`, JVM CLI |
| Generated code (targets `commonMain` of the user project) | `androidMain`/`desktopMain` of the *engine* only for: `FileSink`/`DocumentStorage` implementations, system fonts, `expect/actual` `currentTimeMillis`-like utilities (avoid if possible) |

### 4.5 How runtime/codegen semantic consistency is guaranteed

1. **One lowering pipeline.** Defaults, unknown-property handling, slot normalization, modifier order, theme-token resolution, scope computation and expression typing happen once in the `Analyzer`. Neither backend re-implements them.
2. **Typed value kinds defined once.** Each `TypeRef` has a `ValueKind` in `:engine:schema` describing (a) literal → Kotlin source (`Value.Dp(16)` → `16.dp`), and (b) the runtime marshalling contract (`Value.Dp(16)` → `16.dp` Compose object). A *kind conformance test* iterates every kind × sample values and checks both sides against the same table.
3. **Declarative mapping tables.** Component/modifier/enum→Compose mappings are data (`CodegenBinding`, `EnumEntrySpec.kotlin`). Renderers use the same enum entry table (`EnumRuntimeMap` must cover every entry, verified by a coverage test).
4. **Coverage checks (fail fast).** `RuntimeCoverage.check(schema, runtime)` and `CodegenCoverage.check(schema)` fail if any spec lacks a renderer / valid binding / referenced property.
5. **Conformance suite.** Every fixture document is rendered by the runtime and by the *compiled generated code* in one Compose UI test; the semantics trees (and on Desktop, pixels) are compared (§28.4).
6. **Expression corpus.** One JSON corpus of `(expr, scope, expected value)` runs through the interpreter and through generated-then-compiled Kotlin (§10.7).
7. **Numerics/string hazards are designed away** (table in §10.6).

### 4.6 Register of difficult decisions

| ID | Difficulty | Decision | Where |
|---|---|---|---|
| D1 | Cross-platform float formatting and JSON number drift | Canonical decimal formatting with ≤4 fractional digits, integer arithmetic formatter, never `Float.toString()` | §5.4, §18 |
| D2 | Runtime vs codegen semantic drift | Single resolver + shared kinds + coverage + conformance | §4.5 |
| D3 | Open (plugin) property types vs closed serializable values | Closed `Value` union; extensibility via `EnumTypeSpec`/`ObjectTypeSpec` (data-defined types) | §9 |
| D4 | Decoding without schema (to keep unknown components/props lossless) | Self-describing tagged values; decoding never consults the schema | §5, §19 |
| D5 | Instance-level overrides of component internals | **Not supported**: instances override only declared params and slots | §7.5 |
| D6 | Scope-dependent modifiers (`weight`, `align`) | `ScopeId`s provided by slots, required by modifiers, validated | §7.3 |
| D7 | Multi-property → single Compose parameter (Column arrangement/spacing) | Emit *cases* + declared mutually-exclusive property rules | §7.4 |
| D8 | State layout in generated code | Per-page state holder class + `remember…State()`; app state via `CompositionLocal` | §12 |
| D9 | Component contract evolution across plugin versions | Document records per-component `version`; component-level migrations | §19.3 |
| D10 | Integer division / overflow semantic parity | `/` and `%` are **not** in MVP operators | §10.6 |
| D11 | Node identity across edits for Compose | `key(node.id)` + structural sharing + stability config | §15.6, §29 |
| D12 | Enforcing architecture rules | Gradle module graph check + Konsist tests | §23.4 |
| D13 | `TypedExpr` was filed in `:engine:analysis` while the `Evaluator` consuming it lives in `:engine:interpreter`, which §23.3 forbids reaching analysis from | `TypedExpr` lives in `:engine:model`; both modules already depend on it, so the layering rule stands untouched | §10.4, §33.3, §33.5 |
| D14 | §12.1 needs a property inside a function body, one inside a class body, one with a getter, and an assignment; §16.2 named `KtProperty` only as a *top-level* declaration and never specified `KtStmt` at all | **Mirror Kotlin's own grammar rather than collapsing the four into one node.** `KtDeclaration.Property` carries top-level and class-member properties — the only place a getter is legal — `KtStmt.LocalProperty` carries function-locals, `KtStmt.Assign` carries assignments, and `KtDeclaration.Class` carries members. Two printers beat one node reused in positions Kotlin itself keeps distinct, and the getter stops being an awkward fit. A property's type is a `KtExpr` name reference, not a new node: a Kotlin type *is* a symbol, so §16.2's reference rule already covers it | §12.1, §16.2 |
| D15 | §10.5 requires "precedence-aware parenthesization" but `FunctionPrecedence` had only `Atom` and `Call`, so `core.isNull`'s `{0} == null` bound looser than its own level claimed — a violation the source documented rather than modelled | **Add the level the requirement implies** rather than have the emitter key parenthesisation off pattern text. A `Comparison` level makes `FunctionSpec`'s stated invariant true for every spec instead of leaving it a documented lie, and a convention keyed on template text would keep drifting the moment a spec is written by hand | §10.5, §16.2 |

---

## 5. Core Domain Model

### 5.1 Vocabulary

| Term | Meaning |
|---|---|
| **Document** (`UiDocument`) | Root record; everything persisted. |
| **Node** | One instance of a component in the UI tree. Identified by `NodeId`. |
| **Page** | A navigable screen: metadata, params, state, and a `root: NodeId`. |
| **ComponentDecl** | A *document-defined reusable component* (params, slots, root node). |
| **Slot** | Named child area of a node (`children`, `content`, `topBar`). |
| **Modifier entry** | Ordered `(ModifierType, args)` applied to a node. |
| **Event handler** | `EventKey → ActionSequence` on a node. |
| **Value** | Closed union of typed literals (§9). |
| **Expr** | Typed-by-analysis expression AST (§10). |
| **Schema** | Immutable set of registries describing what the engine understands. |
| **ResolvedDocument** | Derived typed form used by runtime and codegen. Never persisted. |

### 5.2 Identifiers (all `@JvmInline value class`, `@Serializable`)

```kotlin
package dev.rotalex.lutter.model

@JvmInline @Serializable
public value class NodeId(public val value: String) {
    init { require(IdSyntax.isValid(value)) { "Invalid NodeId: $value" } }   // [A-Za-z0-9_]{1,64}
    override fun toString(): String = value
}
// Same pattern: PageId, ComponentDeclId, StateId, ThemeId, ResourceId, DataModelId, TypeId,
// PropertyKey, SlotName, EventKey, ParamName.
// Namespaced, dotted ids: ComponentType("core.Column"), ModifierType("layout.padding"),
// ActionId("nav.navigate"), FunctionId("list.isNotEmpty"), PluginId("forge.builtins").
```

`IdGenerator` (interface in model) produces `NodeId`s. Production: `n` + 10 Crockford-base32 chars from a secure random source (platform-neutral, injected). Tests: `SequentialIdGenerator("n_")` → `n_1`, `n_2`… so fixtures are deterministic.

### 5.3 The node record

```kotlin
@Serializable
public data class Node(
    val id: NodeId,
    val type: ComponentType,                                   // "core.Column", "m3.Text", "doc.c_ab12"
    val name: String? = null,                                  // user-facing layer name; a hint only
    val props: Map<PropertyKey, PropertyValue> = emptyMap(),   // sorted by key when written
    val modifiers: List<ModifierEntry> = emptyList(),          // order is semantically significant
    val slots: Map<SlotName, List<NodeId>> = emptyMap(),       // ordered child ids per slot
    val events: Map<EventKey, ActionSequence> = emptyMap(),
)

@Serializable
public data class ModifierEntry(
    val type: ModifierType,
    val args: Map<PropertyKey, PropertyValue> = emptyMap(),
)
```

`Node` is **not** a recursive tree: children are referenced by id (normalized). `Map<PropertyKey, PropertyValue>` is *not* `Map<String, Any>`: both key and value are closed, typed, serializable domain types (`PropertyValue` is a sealed union, §9), and every entry is validated against `PropertySpec`s.

### 5.4 Values and canonical numbers

```kotlin
@Serializable
public sealed interface Value {
    @Serializable @SerialName("null")   public data object Null : Value
    @Serializable @SerialName("bool")   public data class Bool(val v: Boolean) : Value
    @Serializable @SerialName("i32")    public data class Int32(val v: Int) : Value
    @Serializable @SerialName("i64")    public data class Int64(val v: Long) : Value
    @Serializable @SerialName("f32")    public data class Float32(@Serializable(CanonicalFloat::class) val v: Float) : Value
    @Serializable @SerialName("f64")    public data class Float64(@Serializable(CanonicalDouble::class) val v: Double) : Value
    @Serializable @SerialName("str")    public data class Str(val v: String) : Value
    @Serializable @SerialName("color")  public data class Color(val argb: ColorArgb) : Value
    @Serializable @SerialName("dp")     public data class Dp(@Serializable(CanonicalFloat::class) val v: Float) : Value
    @Serializable @SerialName("sp")     public data class Sp(@Serializable(CanonicalFloat::class) val v: Float) : Value
    @Serializable @SerialName("enum")   public data class Enum(val entry: String) : Value      // type from the PropertySpec
    @Serializable @SerialName("url")    public data class Url(val v: String) : Value
    @Serializable @SerialName("ref")    public data class Ref(val kind: RefKind, val id: String) : Value   // page, component, resource, dataModel
    @Serializable @SerialName("icon")   public data class Icon(val set: String, val name: String) : Value
    @Serializable @SerialName("token")  public data class Token(val kind: TokenKind, val name: String) : Value
    @Serializable @SerialName("list")   public data class ListOf(val items: List<Value>) : Value
    @Serializable @SerialName("obj")    public data class Obj(val typeId: TypeId, val fields: Map<PropertyKey, Value>) : Value
}

@Serializable
public sealed interface PropertyValue {
    @Serializable @SerialName("const") public data class Const(val value: Value) : PropertyValue
    @Serializable @SerialName("expr")  public data class Computed(val expr: Expr) : PropertyValue
}
```

Design rules for `Value`:

- **Closed** union: adding a variant is a schema-version event. Extensibility for plugins comes from *data-defined types* (`EnumTypeSpec`, `ObjectTypeSpec`, `IconSet`) registered in the schema, not from new `Value` subclasses (D3).
- **Every persisted subclass has a mandatory `@SerialName`** (Konsist rule). Never rely on class names.
- **Canonical numerics (D1).** Float-ish literals in documents are canonicalized at construction (`Value.dp(16.5f)` rounds to ≤4 fractional digits, rejects NaN/±Inf). The serializers `CanonicalFloat/CanonicalDouble` format via integer arithmetic (`165000/10000` → `16.5`), never `toString()`. Evaluation results (runtime `Value.Float64` from arithmetic) are not serialized and may hold any finite double.
- Decoding is **schema-free**: a `Value` decodes without knowing which component it belongs to. This is what allows unknown components/properties from newer plugins to round-trip losslessly (D4).

### 5.5 Document, pages, component declarations

```kotlin
@Serializable
public data class UiDocument(
    val meta: DocumentMeta,
    val app: AppSpec,
    val pages: Map<PageId, Page>,
    val components: Map<ComponentDeclId, ComponentDecl>,
    val nodes: NodeTable,                                 // ONE global normalized table (ADR-001)
    val appState: List<StateDecl> = emptyList(),
    val dataModels: Map<DataModelId, DataModelDecl> = emptyMap(),
    val enums: Map<TypeId, EnumTypeDecl> = emptyMap(), // one value space with dataModels; the two maps are disjoint
    val hostFunctions: List<HostFunctionDecl> = emptyList(),
    val themes: Map<ThemeId, ThemeDecl> = emptyMap(),
    val theme: ThemeId? = null,                       // the selected theme (§14.2); null selects by the rule there
    val resources: Map<ResourceId, ResourceDecl> = emptyMap(),
)

@Serializable
public data class DocumentMeta(
    val name: String,
    val plugins: List<PluginRequirement> = emptyList(),                 // {id, version} — checked at load (§27.2)
    val componentVersions: Map<ComponentType, Int> = emptyMap(),        // contract versions the doc was authored with (D9)
)

@Serializable
public data class Page(
    val id: PageId,
    val name: String,                 // Kotlin identifier (validated) → HomeScreen composable
    val route: String,
    val params: List<ParamDecl> = emptyList(),
    val state: List<StateDecl> = emptyList(),
    val root: NodeId,
)

@Serializable
public data class ComponentDecl(
    val id: ComponentDeclId,
    val name: String,                 // Kotlin identifier → composable name
    val params: List<ParamDecl>,      // typed inputs
    val slots: List<SlotDecl>,        // named content areas exposed to instances
    val state: List<StateDecl> = emptyList(),   // component-local state
    val root: NodeId,
)
```

The declarations this section *uses* but does not define live with their own concern: `ParamDecl` (§13.1:1029 fixes its three fields), `StateDecl` and `Persistence` (§12.1), `AppSpec` and `NavigationSpec` (§13.1), `HostFunctionDecl` (§11.6), `ThemeDecl` and the role vocabulary (§14.1), and `ResourceDecl`/`ResourceVariant`/`ResourceSource`/`ResourceKind`/`Qualifier` (§20). Five types had no home anywhere, and they are declared here because a document is the only place that can name them — six records, because a document-declared enum needs its entries:

```kotlin
@Serializable
public data class SlotDecl(
    val name: SlotName,
)

@Serializable
public data class EnumTypeDecl(
    val id: TypeId,                              // the TypeRef.Enum(id) a property type uses (§9.1)
    val name: String,                            // Kotlin identifier → generated enum type
    val entries: List<EnumEntryDecl> = emptyList(),
)
@Serializable
public data class EnumEntryDecl(
    val name: String,                            // the name IS the whole value a document can write
)

@Serializable
public data class DataModelDecl(
    val id: DataModelId,
    val name: String,                            // Kotlin identifier → data class name (§16.6)
    val fields: List<FieldDecl> = emptyList(),
)
@Serializable
public data class FieldDecl(
    val name: PropertyKey,                       // the key Value.Obj writes, validated as an identifier
    val type: TypeRef,                           // Nullable → ?, ListOf → List<T> (§16.6)
)

@Serializable
public data class PluginRequirement(
    val id: PluginId,
    val version: String,                         // the version the document was authored against
)
```

Four of the five are over-determined rather than chosen, and one is a decision:

- **`SlotDecl` carries exactly one field.** §7.1:551-557 declares `SlotSpec` with `name`, `cardinality`, `accepts`, `provides` and `iteration`, and §33.2:2419 puts `Cardinality` and `IterationSpec` in `:engine:schema` — which §23.3:1727 makes unreachable from `:engine:model`. Those five properties are therefore not `SlotDecl` fields, and §5.5:465 is what turns that into a design rather than a loss: the engine synthesizes a `ComponentSpec` for each `ComponentDecl`, and the synthesis is where a document slot acquires `Cardinality.Many` and no `ScopeId`. A later session that adds those fields to `SlotDecl` breaks §23.3, not just this section.
- **`DataModelDecl` and `FieldDecl` are fixed by their emission target.** §16.6:1307 emits `data class User(val name: String, val age: Int)`; `Nullable` → `?` and `ListOf` → `List<T>` are the same row. The generator's output *is* the specification of the record, so its field list is a consequence, not a fork. `FieldDecl.name` is a `PropertyKey` and not a bare `String` because a data model's fields are written as the keys of `Value.Obj(typeId, fields: Map<PropertyKey, Value>)` (§5.4:257) — the same rule §5.3:234 states for `Node.props`. `DataModelDecl` has no optionality flag: `TypeRef.Nullable` already is that, and a second one would be a second source of truth for the same fact.
- **`EnumEntryDecl` carries no value and no Kotlin symbol.** §5.4:251's `Value.Enum(entry: String)` is the entire value a document can write, and §16.6:1301's `EnumEntrySpec.kotlin` symbol belongs to the schema's `EnumTypeSpec` (§33.2:2426), not to the document. A plugin enum that needs per-entry values registers an `EnumTypeSpec` in `:engine:schema`; a document enum emits a generated enum whose entry name is its own symbol.
- **`PluginRequirement.version` is a `String`, and the section contradicted itself until it did not.** The `plugins` field's comment said `versionRange`; §30.2:2043 and §27.1:1912 both say `version`. Two sites against one comment, so `version` wins, and the comment is corrected in place above. *What the string means* is not settled here and is not derivable: no section specifies a grammar, and §27.2:1919 names the outcome (`plugin.version_mismatch`) without the comparison. The loader owns that rule.

`DataModelId` and `TypeId` are **one value space**. §5.2:205-206 declares both as the same value class over the same id syntax, and §9.1:750-751 references an enum type and an object type through the same `TypeId`; `enums` and `dataModels` are therefore two halves of one namespace, and the structural pass treats a key present in both as the duplicate it is. No alias field is added for that, and `dataModels` keeps its `DataModelId` key rather than being retyped: `DataModelId` and `TypeId` are interchangeable on the wire, so changing the declared key type would break every stored document (§19.2) to fix a distinction nothing can observe.

`NodeTable` wraps a persistent map (`kotlinx-collections-immutable`, internal detail, not exposed in signatures):

```kotlin
@Serializable(with = NodeTableSerializer::class)   // encodes Map<NodeId, Node>, entries sorted by id (§18.2)
public class NodeTable internal constructor(private val map: PersistentMap<NodeId, Node>) {
    public companion object {
        public val EMPTY: NodeTable                   // nodes: NodeTable has no default
    }
    public operator fun get(id: NodeId): Node?
    public fun require(id: NodeId): Node
    public operator fun contains(id: NodeId): Boolean
    public val size: Int
    public fun ids(): Sequence<NodeId>               // ascending by id; not the map's iteration order
    public fun with(node: Node): NodeTable           // replaces on an existing id; structural sharing
    public fun without(id: NodeId): NodeTable
    override fun equals(other: Any?): Boolean        // order-independent (§6.2)
    override fun hashCode(): Int
}
```

`NodeTableSerializer` lives in `:engine:model`, beside the class, and is `public`. The annotation above names it by unqualified symbol, so it has to be resolvable from the module that declares the annotated type; `:engine:serialization` is the wrong home for that (and §23.3:1727 forbids the model from depending on it), which is why §33.3's row for it is corrected. It is public rather than `internal` because an internal custom serializer on a public type throws `SerializationException` on Wasm — the generated lookup cannot reach it there, while JVM and Android can. Public is not a leak: the persistent map stays an internal detail (this section's own note, and §24.1:1754), and the serializer's only public fact is that a table writes as a map.

Four members the table needs and does not have, each with the text that requires it:

- **`EMPTY`.** The `nodes` field above carries no default, so without a zero-length instance there is no way to build a `UiDocument` at all — and the builder DSL (§33.1:2400) needs a starting point before it has a first node. `PersistentHashMap` is the backing type §29.1:2006 already names, so `EMPTY` holds that library's empty hash map.
- **`equals`/`hashCode`.** A `class` gives identity, and §6.2:509 requires `NodeTable.equals` to be order-independent. Delegating to the wrapped map satisfies that for free: `PersistentMap` *is* a `Map`, and two maps holding the same pairs are equal however they were built. `NodeTable` is not a `data class` precisely because the property it would generate is the wrong one — it would compare the map field, which happens to be right, but would also put a library type in the generated `toString`.
- **`ids()` order.** Ascending by `NodeId`, which is the order §18.2:1416 writes and the order §6.2:509's equality assumes. It is a contract of this method and not a property of the backing map: `persistentHashMapOf` — the type §29.1:2006 names — documents its iteration order as *unspecified*, so an implementation that returned `map.keys` would compile and violate the format. The comparison is on the id's string form, UTF-16 code-unit lexicographic (the rule §18.2:1414 already states for keys), because `NodeId` is a value class over `String` with no `Comparable` and therefore no `sortedBy` to delegate to. Materialising that order costs `O(n log n)` per call, which is worth saying plainly: a table used for traversal should keep a sorted key sequence beside the map. That cache is an implementation detail and deliberately not a member.
- **`with(node)` on an id that already exists: replace.** §26.2:1875's `PatchOp.SetProp` carries a whole replacement `Node`, and a property edit has no other way to reach the table; making `with` refuse an existing id would force every edit path to test membership first and would leave the editing layer holding a second mutation primitive. Replacement also keeps `ids()` unchanged — the key set is the same — so structural sharing and sorted order both survive the update.

**`NodeBody` does not exist and the format does not need it.** §5.5's `NodeTable` comment said the encoding is `Map<NodeId, NodeBody>`; no section declares `NodeBody`, §5.3:218 requires `Node` to carry `id`, and §30.2:2050-2071 writes bodies *without* one because the key already is the id. The comment above is corrected to `Map<NodeId, Node>`, with the id in the body, and the disagreement is resolved here rather than deferred: a second record that is `Node` minus `id` would have to be kept in step with `Node` forever for a field the consumers do not want dropped — `without(id)` returns a `NodeTable` and not a bare value, `DocumentIndex` (§5.6) is keyed by id, and `DiagnosticLocation` (§17.2:1366) carries `nodeId` separately. This is a `FORMAT_VERSION` event, as a discriminator or key that changes name is, and it costs nothing to decide now: §19.2:1497 refuses documents produced by `0.x` snapshots and freezes `schemaVersion = 1` at MVP release, so there is nothing to migrate.

#### 5.5.1 Resolved ambiguities

Each row was an open question in this section's closure — a type this section names and did not declare, or two sections that disagreed. The citations are the evidence, so the next reader can retrace a decision instead of re-deriving it.

| Decision | Forced by | Against |
|---|---|---|
| Theme role names are open strings: `ColorRole`, `TextRole`, `ShapeRole`, `TokenName` are value classes over `String`; `TextStyleSpec`/`ShapeSpec` are `Map<PropertyKey, Value>` | §14.2:1110 already mandates the validation this gives up — a wrong role is `token.unknown` at analysis time, not a constructor failure | Freezing Material 3's vocabulary into the wire before anyone has built a theme would make a rename a `FORMAT_VERSION` event for no gain |
| `Persistence` is a `@Serializable sealed interface`, not an `enum class` | §12.1:979's `= Persistence.None` needs the value, §4.3:137 calls it a *hook* | Adding a variant to an enum is additive; adding a **payload** to an existing enum entry is not, and only a sealed interface grows without one. Nothing can test the variant set — §31.3:2240 defers persistent state out of MVP |
| Theme selection is `UiDocument.theme: ThemeId? = null` | §14.2:1110 validates "the selected theme" and nothing named it; §5.5:288 declares only the map | An additive nullable with a default; the alternative — a non-null field — changes an existing declaration's type and so every stored document |
| `UiDocument.enums: Map<TypeId, EnumTypeDecl>` | §5.4:269 and §9.1:750 both provide for document-declared enums; §5.5:285 had a `dataModels` map and no `enums` | The same additive empty-map-with-a-default shape as `theme` |
| `DataModelId` and `TypeId` are one value space, with no alias field | §5.2:205-206 declares both over the same id syntax; §9.1:750-751 uses one `TypeId` for both an enum and an object | An alias field would be a second name for one thing, and retyping `dataModels`' key would break stored documents for a distinction nothing observes |
| `SlotDecl` carries only `name: SlotName` | `Cardinality`, `accepts`, `provides`, `iteration` are `SlotSpec` fields (§7.1:551-557) in `:engine:schema` (§33.2:2419), unreachable under §23.3:1727 | §5.5:465's synthesized `ComponentSpec` is where a document slot acquires them — which is what makes that sentence load-bearing |
| `NodeOwner` is a `@Serializable sealed interface` with `Page(PageId)` / `Component(ComponentDeclId)` | §5.6:437 states the two cases; §10.1:817-823 is the shape this document already persists for a hierarchy of ids | §5.4:270: every persisted hierarchy carries explicit tags. A tagless hierarchy is expensive to discover late |
| The `UiDocument` work and the `NodeTable` work are **one** unit, and the arrow runs from the former to the latter | `nodes: NodeTable` carries no default (this section), so the root record does not compile without the table it holds | The feature task list's "T8 needs T7" is the wrong way round: there is no cut point at which `UiDocument` compiles and `NodeTable` does not. The order is set by the type graph, not by preference |
| `PluginRequirement.version: String` | §30.2:2043 and §27.1:1912 | The `plugins` field's `versionRange` comment — one site against two, and corrected in place above |
| `DataModelDecl`/`FieldDecl` shapes | §16.6:1307's emission target (`data class User(val name: String, val age: Int)`) | Not a design space: the generated source *is* the specification |
| `NodeTableSerializer` is in `:engine:model` and `public` | §5.5:371's annotation names it; §33.1:2385 files it with `NodeTable` | §33.3:2438, which cannot hold it: §23.3:1727 forbids the model from depending on `:engine:serialization` |
| `NodeBody` is retired; the encoding is `Map<NodeId, Node>` | §5.3:218 requires `id` | §30.2:2050-2071 writes bodies without one. A `FORMAT_VERSION` event, and §19.2:1497 means it is free |
| `ResourceKind` has no `Color` entry | §20:1559 — "Colors/typography are tokens (§14), not resources" — and §9.2:768 gives colour a colour-picker, not a resource picker | §20:1522's trailing comment listed it; corrected in place in §20 |
| `ResourceVariant.qualifiers` stays a `Set` | §18.2:1414 orders object keys and arrays and says nothing about sets | Fixed by a canonical-writer rule in §18.2, not by a type change: qualifiers are a predicate, so their order carries no meaning to preserve |
| `NavigationSpec` has no fields | §13.1:1018 — `Page.route` + `Page.params` *are* the destination | A `kind` field would duplicate `CodegenOptions.navigation` (§16.8:1321) and put a second source of truth on a semantic decision |
| `DocumentIndex.ancestorsOf` is declared, and §6.2:514 is left alone | §6.2:514 wants a dirty **set** from a patch touching N nodes; §26.2:1881's `touchedNodes` is already a `Set` | Folding `pathTo` at the call site returns a `List` with duplicates where the consumer wants membership |

### 5.6 Tree vs normalized vs composition — trade-off analysis

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Nested immutable tree (`UiNode.children: List<UiNode>`) | Trivial to read/print; simple codegen | Every edit copies the path to the root; moving nodes between parents is a delete+insert; no O(1) lookup by id; cross-tree references are awkward; large docs slow | Rejected as the *stored* form |
| **Normalized table** (`Map<NodeId, Node>` + child id lists) | O(1) lookup, O(depth) structural sharing, cheap moves/patches, stable identity, references and selections are ids, diff-friendly | Needs invariants (single parent, no cycles, no orphans) and an index for parents | **Chosen** |
| Per-page node tables | Smaller tables | Moving nodes between pages/components needs cross-table ops; ids not globally unique | Rejected |
| Component *inheritance* | Familiar | Override diffing, fragile base class, hard codegen | Rejected (composition only) |
| **Composition via `ComponentDecl` + params + slots** | Maps 1:1 to Compose (`@Composable fun Foo(param, content: @Composable () -> Unit)`) | No structural overrides (D5) | **Chosen** |

Derived structures (never persisted, lazily built and cached by document identity in the editing layer):

```kotlin
public class DocumentIndex(document: UiDocument) {
    public fun parentOf(id: NodeId): ParentRef?            // (parentId, slot, index)
    public fun ownerOf(id: NodeId): NodeOwner?             // Page(id) | Component(id)
    public fun pathTo(id: NodeId): List<NodeId>
    public fun ancestorsOf(ids: Collection<NodeId>): Set<NodeId>
    public fun descendants(id: NodeId): Sequence<NodeId>
}
public class ReferenceIndex(document: UiDocument)          // who references state/page/resource/component (safe delete & rename)

public data class ParentRef(
    val parentId: NodeId,
    val slot: SlotName,
    val index: Int,
)

@Serializable
public sealed interface NodeOwner {
    @SerialName("page")      data class Page(val id: PageId) : NodeOwner
    @SerialName("component") data class Component(val id: ComponentDeclId) : NodeOwner
}
```

`ParentRef` is the three-tuple `parentOf` returns, written out because the comment above was the whole of its specification. `NodeOwner` is the two-case hierarchy that comment names, declared in the shape §10.1:817-823 already persists for a closed set of ids: explicit `@SerialName` on every case, per §5.4:270. Neither appears in the document payload — this section's own preamble says the structures here are never persisted — but a hierarchy that is `@Serializable` without tags falls back to class names the first time something does serialize it, and `RefTarget` shows the cost of stating them is one line each. `ParentRef` is plain data with no hierarchy and so no tag to state.

`ancestorsOf` exists because §6.2:514 calls it by name and nothing here declared it; the alternative was to amend §6.2:514 to `pathTo`, which is the wrong shape. `pathTo` answers for one node and returns the root-to-node path as a `List`, while the dirty set is the union of the paths of *all* nodes a patch touched, and §26.2:1881 already types the patch's own `touchedNodes` as a `Set`. A caller folding `pathTo` per touched node gets duplicates, a `List` where it wants membership, and the deduplication written once per call site. Taking a batch and returning a `Set` puts it in one place, which is where §6.2:514 says the dirty set is formed. It is post-MVP with the rest of incremental analysis (§31.3:2240).

### 5.7 Reusable components, slots, instances, templates

- **Instance:** a `Node` whose type is `doc.<ComponentDeclId>`. Its `props` are the values for `ComponentDecl.params`; its `slots` fill `ComponentDecl.slots`. That is the complete override surface (D5).
- **Inside the declaration body**, a built-in `core.SlotOutlet` node (`slot = "content"`) marks where instance slot content renders; `Expr.Ref(RefTarget.Param("title"))` reads params.
- **The engine synthesizes a `ComponentSpec`** from each `ComponentDecl` (an *overlay* over the static schema, §8.4), so the analyzer, runtime and codegen treat document components exactly like built-in ones, only with `origin = Document`.
- **Templates** (palette entries such as "Login form") are *subtree snapshots*. Instantiation is an editing command (`InsertSubtree`) that copies nodes with fresh ids. They are not a core-model concept.

---

## 6. Record-Based Document Model

### 6.1 Shape

```
UiDocument
 ├── meta { name, plugins[], componentVersions{} }
 ├── app { packageName, startPage, navigation }
 ├── pages        { PageId → Page{ name, route, params, state[], root } }
 ├── components   { ComponentDeclId → ComponentDecl{ params, slots, state, root } }
 ├── nodes        { NodeId → Node{ type, props{}, modifiers[], slots{}, events{} } }   ← ONE table
 ├── appState[]
 ├── dataModels   { DataModelId → DataModelDecl }
 ├── enums        { TypeId → EnumTypeDecl }              // one value space with dataModels
 ├── hostFunctions[]
 ├── themes       { ThemeId → ThemeDecl }
 ├── theme        ThemeId?                               // the selected one (§14.2)
 └── resources    { ResourceId → ResourceDecl }

Envelope (serialization, not part of UiDocument):
 { format, formatVersion, schemaVersion, payload: UiDocument }
```

```
Page ──root──► Node(core.Column)
                 ├─ slots.children ─► Node(m3.Text)
                 └─ slots.children ─► Node(m3.Button)
                                        └─ slots.content ─► Node(m3.Text)
                 (all Nodes physically live in UiDocument.nodes)
```

### 6.2 Properties of the record model

| Topic | Design |
|---|---|
| **Stable IDs** | Ids are assigned once, never reused, never derived from position or name. Renaming a page/component/state changes `name`, not `id`. Moves keep ids. Duplicate/paste generates new ids (`IdRemapper`). Ids are globally unique in the document (across pages/components). |
| **Schema versions** | `schemaVersion` (payload contract) and `formatVersion` (envelope layout) live in the envelope; component contract versions in `meta.componentVersions` (§19). |
| **References** | Only by id: `NodeId` (slots), `Value.Ref(kind,id)` (pages/resources/components), `RefTarget.State(StateId)` (expressions), `TypeRef.Object(TypeId)`. `ReferenceIndex` answers "who uses X". Dangling references are validation errors, never silent. |
| **Deterministic serialization** | Canonical JSON (§18): sorted map keys, nodes sorted by id, fixed formatting, canonical numbers, no defaults encoded, `\n` newlines, trailing newline. |
| **Structural equality** | `data class` equality; `NodeTable.equals` is order-independent. Fast path `===`. The editing layer also exposes a monotonically increasing `revision`. |
| **Patch operations** | Closed set of invertible `PatchOp`s (§26). `Patch = List<PatchOp>` is serializable and applies deterministically. |
| **Undo/redo compatibility** | Every applied patch yields an inverse; history stores patches, not snapshots (ADR-013). |
| **Diffing** | `DocumentDiff.compute(old, new): Patch` — node-table keyed diff (added/removed/changed nodes, slot moves) + record-level diff for pages/components/etc. Needed for tests, `forge diff`, and later collaboration/sync. |
| **Validation** | `Analyzer` (§17), independent of editing. |
| **Partial updates** | `Patch.touchedNodes` + `DocumentIndex.ancestorsOf` define the *dirty set* for incremental re-analysis and preview updates. |
| **Persistence** | `DocumentCodec` (bytes ↔ document) + `DocumentStorage` (bytes ↔ location). Neither touches the domain model. |
| **Invariants** (checked by the structural pass) | Each node reachable from exactly one root (page or component root) and has exactly one parent; no cycles; all `NodeId`s in slots exist; roots exist; ids syntactically valid. |

---

## 7. Component System

### 7.1 `ComponentSpec` — pure data

```kotlin
package dev.rotalex.lutter.schema

public class ComponentSpec(
    public val type: ComponentType,
    public val version: Int,                        // contract version (migration boundary, D9)
    public val metadata: ComponentMetadata,         // display name, category, description, keywords, icon, since
    public val availability: Set<PlatformTag> = PlatformTag.ALL,
    public val properties: List<PropertySpec<*>>,
    public val slots: List<SlotSpec>,
    public val events: List<EventSpec>,
    public val modifiers: ModifierPolicy = ModifierPolicy.All,
    public val rules: List<PropertyRule> = emptyList(),      // mutually exclusive, requires-one-of, ranges
    public val codegen: CodegenBinding,
    public val origin: SpecOrigin = SpecOrigin.Static,       // Static | Document(decl id)
)

public class PropertySpec<T>(                         // T is a phantom type for typed renderer access
    public val key: PropertyKey,
    public val type: TypeRef,
    public val default: Value? = null,
    public val required: Boolean = false,
    public val bindable: Boolean = true,              // may hold a Computed(Expr)
    public val editor: EditorHints = EditorHints.None,
    public val doc: String = "",
)

public class SlotSpec(
    public val name: SlotName,
    public val cardinality: Cardinality,               // ExactlyOne | ZeroOrOne | Many
    public val accepts: ChildFilter = ChildFilter.Any,  // Any | Only(types) | Except(types)
    public val provides: Set<ScopeId> = emptySet(),     // e.g. RowScope, ColumnScope, BoxScope
    public val iteration: IterationSpec? = null,        // LazyColumn item template (post-MVP wave)
)

public class EventSpec(
    public val key: EventKey,                           // "onClick", "onValueChange"
    public val args: List<EventArgSpec> = emptyList(),  // typed event arguments (name, TypeRef)
)
```

### 7.2 Declarative Compose mapping

```kotlin
public sealed interface CodegenBinding {
    public data class ComposeCall(
        val function: KotlinSymbol,                     // androidx.compose.material3.Text
        val modifierParam: String? = "modifier",
        val params: List<ParamBinding> = emptyList(),
        val events: List<EventBinding> = emptyList(),
        val slots: List<SlotBinding> = emptyList(),
    ) : CodegenBinding
    public data class Custom(val emitterId: EmitterId) : CodegenBinding   // escape hatch (registered in CodegenExtensions)
    public data object Intrinsic : CodegenBinding                         // SlotOutlet, doc.* instances (handled by the generator)
}

public data class ParamBinding(
    val param: String,
    val from: List<PropertyKey>,
    val emit: ValueEmit = ValueEmit.Direct,
    val positional: Positional = Positional.Never,       // Never | WhenSole (Text("Continue"))
    val omitWhenDefault: Boolean = true,
)
public sealed interface ValueEmit {
    public data object Direct : ValueEmit                                // uses the ValueKind's literal emission
    public data class Cases(val cases: List<EmitCase>) : ValueEmit        // pick by which properties are present (D7)
}
public data class EmitCase(val whenPresent: Set<PropertyKey>, val pattern: String, val imports: List<KotlinSymbol> = emptyList())
// pattern uses {propKey} placeholders: "Arrangement.spacedBy({spacing})"
public data class SlotBinding(val slot: SlotName, val target: LambdaTarget, val receiver: KotlinSymbol? = null)  // Trailing | NamedParam("content")
public data class EventBinding(val event: EventKey, val param: String, val lambdaParams: List<String> = emptyList())
```

Column, for example (D7): properties `spacing: Dp?` and `verticalArrangement: enum`, mutually exclusive by a declared `PropertyRule.MutuallyExclusive(spacing, verticalArrangement)`; the `verticalArrangement` param uses `Cases([spacing → "Arrangement.spacedBy({spacing})", verticalArrangement → "{verticalArrangement}"])`.

### 7.3 Layout is components + modifiers + scopes (no LayoutRegistry)

- Layout components (`Row`, `Column`, `Box`, later `FlowRow`, `LazyColumn`, `BoxWithConstraints`) are ordinary `ComponentSpec`s.
- Layout *modifiers* (`padding`, `size`, `fillMaxSize`, `weight`, `align`, `offset`, `background`, `clip`, `clickable`…) are `ModifierSpec`s.
- **Scope-dependent modifiers (D6):** `SlotSpec.provides = {compose.RowScope}` on `Row.children`; `ModifierSpec.requiresScope = {compose.RowScope}` on `layout.weight`. The analyzer computes the scope set for each node from its ancestry and reports `modifier.scope_missing`. Codegen emits `Modifier.weight(1f)` inside the receiver lambda, so generated code is valid by construction; the runtime obtains the same receiver via `ScopeBag`.

```kotlin
public class ModifierSpec(
    public val type: ModifierType,
    public val metadata: ModifierMetadata,
    public val params: List<PropertySpec<*>>,
    public val requiresScope: Set<ScopeId> = emptySet(),
    public val emit: ModifierEmit,                       // function symbol + Cases, e.g. padding(all) / padding(horizontal, vertical)
)
```

### 7.4 Adding a component: the extensibility contract (goal G2)

```kotlin
// (1) SPEC  — in :engine:builtins (or any plugin module). Pure Kotlin.
public object TextSpec {
    public val text = prop<String>("text", TypeRef.Str, required = true)
    public val color = prop<ColorArgb?>("color", TypeRef.Nullable(TypeRef.Color))
    public val style = prop<TokenName?>("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography)))

    public val spec: ComponentSpec = componentSpec(CoreTypes.M3Text, version = 1) {
        metadata(displayName = "Text", category = Category.Basic)
        property(text); property(color); property(style)
        composeCall(KotlinSymbol("androidx.compose.material3", "Text")) {
            param("text", from = text, positional = Positional.WhenSole)
            param("color", from = color); param("style", from = style)
        }
    }
}

// (2) RENDERER — in :engine:builtins-compose (or the plugin's Compose module)
internal object TextRenderer : ComponentRenderer {
    @Composable override fun Render(node: ResolvedNode, scope: RenderScope) {
        val p = scope.props(node)
        Text(
            text = p[TextSpec.text],
            color = p[TextSpec.color]?.toCompose() ?: Color.Unspecified,
            style = p[TextSpec.style]?.let { scope.theme.textStyle(it) } ?: LocalTextStyle.current,
            modifier = scope.modifierFor(node),
        )
    }
}

// (3) REGISTER — in a plugin pair (schema contribution + runtime contribution)
schemaBuilder.component(TextSpec.spec)
rendererRegistryBuilder.register(TextSpec.spec.type, TextRenderer)

// (4) TESTS — spec coverage test, golden codegen fixture, conformance fixture (auto-discovered)
```

No `when` over component types exists anywhere in the engine. `ThirdPartyComponentTest` proves that a component defined in a test-only module works through validation, runtime, codegen and serialization without editing any engine module.

### 7.5 Component families (MVP vs later)

| Wave | Components |
|---|---|
| **MVP (wave 1)** | `core.Column`, `core.Row`, `core.Box`, `core.Spacer`, `core.SlotOutlet`, `m3.Text`, `m3.Button`, `m3.TextField`, `m3.Card`, document-defined components (`doc.*`) |
| Wave 2 | `m3.Checkbox`, `m3.Switch`, `m3.RadioButton`, `m3.Slider`, `m3.ProgressIndicator`, `m3.Divider`, `m3.Icon`, `m3.Image`, `core.LazyColumn/LazyRow` (needs iteration scopes), `core.FlowRow/FlowColumn` |
| Wave 3 | `m3.Scaffold`, `m3.Surface`, `m3.Dialog`, `m3.BottomSheet`, navigation components (bar/rail/drawer), `core.BoxWithConstraints` |

Wave 2/3 are *mechanical* once wave 1 exists; iteration scopes (`LazyColumn`) are the one new mechanism (typed item variable from `IterationSpec`).

---

## 8. Registry Architecture

### 8.1 Which registries exist, and why

| Candidate | Verdict | Reason |
|---|---|---|
| **ComponentRegistry** (`ComponentType → ComponentSpec`) | ✅ Schema | Central extension point |
| **ModifierRegistry** (`ModifierType → ModifierSpec`) | ✅ Schema | Modifiers have their own params, scope rules and emission; used by validation, runtime and codegen |
| **ActionRegistry** (`ActionId → ActionSpec`) | ✅ Schema | Actions are data steps; behaviour is looked up by id |
| **FunctionRegistry** (`FunctionId → FunctionSpec`) — the "ExpressionRegistry" | ✅ Schema | Typed signatures and Kotlin emission for expression functions |
| **TypeRegistry** (enum types, object types, icon sets) | ✅ Schema | Data-defined types are how plugins add "property types" (D3) |
| **RendererRegistry** (`ComponentType → ComponentRenderer`) | ✅ Runtime | Compose implementations, kept out of schema |
| **ModifierApplierRegistry** (`ModifierType → ModifierApplier`) | ✅ Runtime | Same reason |
| Implementations (`FunctionImpls`, `ActionHandlers`) | ✅ Interpreter (two small maps) | Pure Kotlin behaviour keyed by id |
| PropertyRegistry | ❌ | Properties belong to a component spec; shared groups are plain Kotlin values (`val commonTextProps = listOf(...)`) |
| EventRegistry | ❌ | Events belong to component specs |
| LayoutRegistry | ❌ | Layout = components + modifiers + scopes (§7.3) |
| SerializerRegistry | ❌ | `Value` is closed; custom object types are data; format choice is a plain `DocumentCodec` interface |
| CodeGeneratorRegistry | ❌ | Bindings are in specs; target strategies are `CodegenOptions` values; overrides in `CodegenExtensions` |
| PlatformRegistry | ❌ | `PlatformTag` on specs + `expect/actual` where needed |
| ThemeRegistry | ❌ | Themes are document data; presets are factory functions |
| AssetRegistry | ❌ | Resources are document data; loading is `ResourceProvider` |
| PluginRegistry | ❌ | Plugins are *applied to builders* at composition time; no runtime registry |

### 8.2 Registry mechanics

```kotlin
public interface Registry<K : Any, V : Any> {
    public operator fun get(key: K): V?
    public fun require(key: K): V
    public operator fun contains(key: K): Boolean
    public fun all(): List<V>                  // deterministic: sorted by key
}

public class Schema private constructor(
    public val components: Registry<ComponentType, ComponentSpec>,
    public val modifiers: Registry<ModifierType, ModifierSpec>,
    public val actions: Registry<ActionId, ActionSpec>,
    public val functions: Registry<FunctionId, FunctionSpec>,
    public val types: TypeRegistry,
    public val contributors: List<PluginDescriptor>,
) {
    public companion object { public fun build(block: SchemaBuilder.() -> Unit): Schema }
}

public interface SchemaContribution {                 // implemented by builtins and by plugins
    public val descriptor: PluginDescriptor
    public fun contribute(builder: SchemaBuilder)
}
```

Rules: registries are **built once, then immutable** (no global mutable state, no service locator); duplicate keys are a `SchemaBuildException` (no silent override); iteration order is deterministic; every registry is passed explicitly (constructor injection).

### 8.3 Runtime-side registries

```kotlin
public class RendererRegistry internal constructor(...) { public operator fun get(type: ComponentType): ComponentRenderer? }
public interface ModifierApplier { public fun apply(m: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier }
public class UiRuntime(schema, renderers, modifierAppliers, implementations)   // coverage-checked in init
```

### 8.4 Document overlay

`SchemaOverlay(schema, document)` produces a `SchemaView` in which `doc.*` component types resolve to synthesized specs. `Analyzer`, `Runtime`, `Codegen` all take a `SchemaView`, so document components are first-class without polluting the static schema. Overlay cannot shadow static keys (namespace `doc.` is reserved).

---

## 9. Property and Type System

### 9.1 `TypeRef` (in `:engine:model` because documents declare types for state, params and data models)

```kotlin
@Serializable
public sealed interface TypeRef {
    @SerialName("bool") data object Bool; @SerialName("i32") data object Int32; @SerialName("i64") data object Int64
    @SerialName("f32") data object Float32; @SerialName("f64") data object Float64; @SerialName("str") data object Str
    @SerialName("color") data object Color; @SerialName("dp") data object Dp; @SerialName("sp") data object Sp
    @SerialName("url") data object Url; @SerialName("icon") data class Icon(val set: String?)
    @SerialName("dimension") data object Dimension                     // post-MVP: Dp | Fill(fraction) | Wrap
    @SerialName("nullable") data class Nullable(val inner: TypeRef)
    @SerialName("list") data class ListOf(val element: TypeRef)
    @SerialName("map") data class MapOf(val value: TypeRef)            // String keys
    @SerialName("enum") data class Enum(val id: TypeId)
    @SerialName("object") data class Object(val id: TypeId)            // data models & structured props
    @SerialName("ref") data class Ref(val kind: RefKind)
    @SerialName("token") data class Token(val kind: TokenKind)
    // (all subclasses @Serializable; shown compressed)
}
```

Events/lambdas are *not* `TypeRef`s; they are `EventSpec`/`ActionSequence` (§11). Expressions and bindings are `PropertyValue.Computed`. Theme tokens are `Value.Token`.

### 9.2 Type → 5 dimensions

| Type | Serialization (JSON tag) | Runtime evaluation | Editor metadata | Validation | Codegen |
|---|---|---|---|---|---|
| String | `{"k":"str","v":"…"}` | `String` | text field, multiline hint | length limits (spec) | `"…"` with escaping (`\"`, `\\`, `\n`, `$` → `\$`) |
| Boolean | `bool` | `Boolean` | switch | — | `true/false` |
| Integer / Long | `i32` / `i64` | `Int` / `Long` | number stepper, range | min/max | `42` / `42L` |
| Float / Double | `f32` / `f64` canonical | `Float` / `Double` | number field | finite; range | `1.5f` / `1.5` |
| Color | `color` `#AARRGGBB` | Compose `Color` (marshalled) | color picker | valid hex | `Color(0xFF6200EE)` |
| Dp / Sp | `dp` / `sp` | `Dp` / `TextUnit` | dimension field | ≥0 unless spec allows | `16.dp` / `14.sp` |
| Dimension (post-MVP) | union | `Dp`/fill/wrap | dimension picker | — | `16.dp` / `Modifier.fillMaxWidth()` route |
| Enum | `enum` entry name | closed runtime map per enum type | dropdown from `EnumTypeSpec` | entry exists | `EnumEntrySpec.kotlin` symbol (`Alignment.Start`) |
| Resource / Image | `ref(kind=resource)` | `ResourceProvider` | resource picker | exists, kind matches | `Res.drawable.foo` / `stringResource(Res.string.foo)` |
| Icon | `icon{set,name}` | `IconSet` lookup | icon picker | in registered set | `Icons.Filled.Home` |
| URL | `url` | `String` (+ scheme check) | text | RFC-3986 syntactic | string literal |
| Reference | `ref` | resolved by analysis | node/page picker | target exists/kind ok | identifier/route |
| Expression / Binding | `{"k":"expr",…}` | `Evaluator` | expression editor | typechecked | Kotlin expression |
| List | `list` | `List<T>` | list editor | element types | `listOf(…)` |
| Map | (`obj` with typed fields or `list` of pairs) | `Map` | key/value editor | value type | `mapOf(…)` |
| Object | `obj{typeId,fields}` | `ObjectValue` | nested form from `ObjectTypeSpec` | fields | constructor call |
| Theme token | `token{kind,name}` | resolved by theme | token picker | token exists | `MaterialTheme.colorScheme.primary`, `AppTokens.brand.accent` |
| Lambda/Event | — (not a value) | `ActionSequence` executor | action editor | action specs | lambda with statements |

### 9.3 `ValueKind`

```kotlin
public interface ValueKind<T> {
    public val type: TypeRef
    public fun accepts(value: Value): Boolean
    public fun toKotlin(value: Value, ctx: KotlinLiteralContext): KtExpr      // used by codegen
    public fun decode(value: Value): T                                        // typed access for renderers (model types, no Compose)
}
```

`ValueKind`s are defined once, next to the type system, and used by both backends (§4.5). Renderers convert model types to Compose (`ColorArgb.toCompose()`) in `:engine:runtime` helpers; a per-kind conformance test compares outputs.

---

## 10. Expression System

### 10.1 AST (in `:engine:model`, serializable)

```kotlin
@Serializable
public sealed interface Expr {
    @SerialName("const")    data class Const(val value: Value) : Expr
    @SerialName("ref")      data class Ref(val target: RefTarget) : Expr
    @SerialName("member")   data class Member(val receiver: Expr, val name: String, val safe: Boolean = false) : Expr
    @SerialName("call")     data class Call(val function: FunctionId, val args: List<Expr>) : Expr
    @SerialName("unary")    data class Unary(val op: UnaryOp, val operand: Expr) : Expr
    @SerialName("binary")   data class Binary(val op: BinaryOp, val left: Expr, val right: Expr) : Expr
    @SerialName("if")       data class If(val cond: Expr, val then: Expr, val otherwise: Expr) : Expr
    @SerialName("list")     data class ListLiteral(val items: List<Expr>) : Expr
    @SerialName("template") data class Template(val parts: List<Expr>) : Expr      // string interpolation
}

@Serializable
public sealed interface RefTarget {
    @SerialName("state")  data class State(val id: StateId) : RefTarget           // includes derived state
    @SerialName("param")  data class Param(val name: ParamName) : RefTarget        // page/component params
    @SerialName("event")  data class EventArg(val name: String) : RefTarget        // inside handlers
    @SerialName("item")   data class Item(val name: String) : RefTarget            // iteration variable (wave 2)
}
public enum class UnaryOp { Not, Neg }
public enum class BinaryOp { Add, Sub, Mul, Eq, Neq, Lt, Le, Gt, Ge, And, Or }   // no Div/Mod in MVP (D10)
```

### 10.2 Minimum for the first production-capable version

Included: constants; refs to state/params/event args; member access on data models (`user.name`, safe `?.`); function calls from the `FunctionRegistry`; `+ - *`, comparison, equality, `&& || !`, unary minus; `if` expressions; list literals; string templates; derived state.
Excluded (postponed): lambdas/higher-order (`map`, `filter`), user-defined functions, assignments in expressions, loops, `/` and `%`, implicit conversions, regexes, dates.

Seed function set (`:engine:builtins`): `list.isEmpty`, `list.isNotEmpty`, `list.size`, `list.contains`, `list.get`, `str.isBlank`, `str.length`, `str.uppercase`, `str.lowercase`, `str.trim`, `str.contains`, `num.format(value, decimals)`, `num.toDouble`, `core.coalesce`, `core.isNull`.

### 10.3 Function specs

```kotlin
public class FunctionSpec(
    public val id: FunctionId,
    public val params: List<ParamSig>,          // typed; generics limited to element-type variables
    public val returns: TypeSig,
    public val kotlin: FunctionEmit,            // "{0}.isNotEmpty()" + imports + precedence class
    public val pure: Boolean = true,            // MVP requires true
)
// interpreter (pure Kotlin), registered under the same id:
public fun interface FunctionImpl { public fun invoke(args: List<Value>): Value }
```

A coverage test asserts every `FunctionSpec` has a `FunctionImpl`, and the expression corpus (§10.7) exercises each.

### 10.4 Type checking (in `:engine:analysis`)

- Bidirectional check with the *expected type* from the `PropertySpec`/param; result is a `TypedExpr(expr, type, refs)`.
- No implicit numeric conversion (`Int32 + Float64` is an error; use `num.toDouble`).
- Nullability is tracked; `Member` on a nullable receiver requires `safe = true`.
- Template parts must be `Str`, `Int32`, `Int64`, `Bool`; floating types require `num.format` (hazard table, §10.6).
- Unknown functions/refs → structured diagnostics with `nodeId` and `property`.

### 10.5 Evaluation and generation

| | Interpreter (runtime) | Codegen |
|---|---|---|
| Const | value | `ValueKind.toKotlin` |
| State ref | `EvalScope.read(State(id))` (reads Compose snapshot state ⇒ recomposition tracking) | `state.count` (page holder) / `LocalAppState.current.count` |
| Param ref | scope lookup | parameter name |
| Member | field of `ObjectValue` | `user.name` / `user?.name` |
| Call | `FunctionImpl.invoke` | `FunctionEmit` template |
| Binary | typed operator table | Kotlin operator with **precedence-aware parenthesization** |
| If | lazy branch | `if (c) a else b` |
| Template | concatenation using canonical `toDisplayString` | `"Hello ${user.name}"` |

### 10.6 Semantic hazard table (why some things are restricted)

| Hazard | Policy |
|---|---|
| `Double.toString()` differs (JS vs JVM) | Template parts cannot be Float/Double; use `num.format(v, n)` implemented with integer math on both sides |
| Int overflow | Same as Kotlin on every target (both sides run Kotlin) — no divergence |
| Int division/modulo by zero | `/`, `%` not offered in MVP (D10) |
| `Double` equality/NaN | `==` on floats allowed; NaN cannot be a literal; runtime NaN follows Kotlin |
| String comparison | Only `==`/`!=` and `str.*` functions; no locale-dependent ordering |
| Evaluation order/laziness | `&&`, `||`, `if` are short-circuit in both |
| Exceptions | Functions are total; index errors return `Null` (`list.get` returns nullable) |

### 10.7 Expression corpus

`src/commonTest/resources/expr/*.json` contains `{expr, scope, expected}` triples. One test evaluates each in the interpreter. The generator emits a Kotlin test file from the same corpus that is compiled in `:integration:generated-compile` and asserted against the same expected values.

### 10.8 Textual syntax (post-MVP but designed)

`ExprParser`/`ExprPrinter` (`user.name`, `items.isNotEmpty()`) is added in Phase 6b **only** for the editor's expression field and CLI diagnostics. The AST is the source of truth; text is a view.

---

## 11. Event and Action System

### 11.1 Separation

```
UI event (EventSpec, declared by a component)  ──►  ActionSequence (data, in the document)  ──►  executed by interpreter / emitted as Kotlin
```

### 11.2 Model

```kotlin
@Serializable
public data class ActionSequence(val steps: List<ActionStep>)

@Serializable
public data class ActionStep(
    val action: ActionId,                                     // "nav.navigate", "state.set", "flow.if", "host.call", "ui.showDialog"
    val args: Map<PropertyKey, PropertyValue> = emptyMap(),   // typed, may contain Computed exprs
    val branches: Map<BranchName, ActionSequence> = emptyMap(), // "then"/"else" for flow.if
)
```

Handlers live in `Node.events: Map<EventKey, ActionSequence>`. Actions are **data**, never lambdas.

### 11.3 Spec and behaviour

```kotlin
public class ActionSpec(
    public val id: ActionId,
    public val metadata: ActionMetadata,
    public val params: List<PropertySpec<*>>,
    public val branches: List<BranchSpec> = emptyList(),
    public val emit: ActionEmit,               // Intrinsic (engine-known) | Template(pattern, imports) for plugin actions
)

// :engine:interpreter (pure Kotlin)
public interface ActionHandler { public suspend fun execute(step: ResolvedActionStep, env: ActionEnv): ActionOutcome }
public interface ActionEnv {
    public val scope: EvalScope; public val state: StateWriter
    public val navigator: Navigator; public val dialogs: DialogHost; public val host: HostFunctions
}
```

### 11.4 Intrinsic vs plugin actions

Engine-owned semantics (state, navigation, control flow, dialogs, host calls) are **intrinsic**: interpreter handlers and codegen emitters are in the engine and stay in sync by design. Plugin actions (`analytics.log`) provide a `Template` emission plus an `ActionHandler`.

MVP actions: `nav.navigate`, `nav.back`, `state.set`, `flow.if`, `host.call`, `ui.showSnackbar` (small). Wave 2: `ui.showDialog`, `ui.showBottomSheet`, `state.toggle`, `list.append/remove`.

### 11.5 Execution

- Runtime: `ActionExecutor.run(sequence, env)` in a coroutine scope owned by the composition (`rememberCoroutineScope`). Steps run sequentially; `host.call` may suspend; failures produce a `RuntimeDiagnostic` (routed to `RuntimeEnvironment.diagnostics`) and stop the sequence.
- Codegen: an `ActionSequence` becomes a lambda body of statements: `navigator.navigate(Route.Profile)`, `state.count = state.count + 1`, `if (...) { … } else { … }`.
- Suspend semantics in generated code: `host.call` targets are declared `suspend` only if the host function decl says `suspend = true`; the generator wraps such handlers in `scope.launch { … }` obtained from `rememberCoroutineScope()`.

### 11.6 Host functions (the escape hatch instead of arbitrary Kotlin)

```kotlin
@Serializable public data class HostFunctionDecl(val name: String, val params: List<ParamDecl>, val returns: TypeRef?, val suspend: Boolean = false)
```

Runtime: `RuntimeEnvironment.host: HostFunctions` (a map from name to lambda supplied by the embedding app). Generated code: an interface `AppHost { fun submitOrder(id: String) }` passed to `AppRoot(host = …)`. Users implement it in hand-written code, keeping generated files pure.

---

## 12. State Model

### 12.1 Scopes

| Scope | Declared in | Lifetime | Runtime representation | Generated representation |
|---|---|---|---|---|
| **Component-local** | `ComponentDecl.state` | Composition | `remember { mutableStateOf }` inside `DeclInstanceRenderer` | `var x by remember { mutableStateOf(init) }` inside the composable |
| **Page** | `Page.state` | Page composition + `rememberSaveable` where type is saveable | `PageStateStore` (snapshot state map) | `@Stable class HomeScreenState { var count by mutableStateOf(0) }` + `@Composable fun rememberHomeScreenState()`; screen takes `state: HomeScreenState = rememberHomeScreenState()` |
| **App** | `UiDocument.appState` | Process/app root | `AppStateStore` provided by `RuntimeEnvironment` | `class AppState` + `val LocalAppState = staticCompositionLocalOf<AppState>` provided in `AppRoot` |
| **Derived** | `StateDecl.derived: Expr` | Recomputed | Evaluated on read (Compose tracks dependencies) | `val doubled: Int get() = count * 2` |
| **Persistent** | `StateDecl.persistence` | Survives restarts | Hook `PersistentStore` (interface only in MVP) | Post-MVP: `StateStrategy` generates store-backed properties |
| **External** | (post-MVP) `ExternalStateDecl` | Host-owned | `HostFunctions`/`Flow` adapters | Constructor parameter of the state holder |

```kotlin
@Serializable
public data class StateDecl(
    val id: StateId,
    val name: String,                       // Kotlin identifier, preserved verbatim in generated code
    val type: TypeRef,
    val initial: Value? = null,
    val derived: Expr? = null,              // exactly one of initial/derived
    val persistence: Persistence = Persistence.None,
)

@Serializable
public sealed interface Persistence {
    @SerialName("none")     data object None : Persistence
    @SerialName("saveable") data object Saveable : Persistence      // rememberSaveable where the type allows
}
```

**A sealed interface, not an `enum class`, and the reason is falsifiability rather than blast radius.** Nothing in this project can ever test whether `Persistence`'s *variant set* is right: §31.3:2240 defers persistent state out of MVP, §12.1:968 calls `PersistentStore` an interface only in MVP, and no engine code reads a non-`None` value. A decision that cannot be tested should therefore be the one that is cheapest to be wrong about later — and for those two shapes the costs are not symmetric. Adding a variant to an `enum class` is additive, since the existing entries and their tags are untouched. Adding a *payload* to an existing enum entry is a wire break: the new field appears on a tag every already-stored document carries. A sealed interface grows a variant with a payload without touching the ones that are already written, which is the only way this type can absorb a `store` reference when `PersistentStore` becomes real. A `sealed interface` also states the closure that an enum states, so a `when` over it is exhaustive in both.

Two variants is what the text supports and no more. `None` is forced by `StateDecl.persistence`'s default, immediately above. `Saveable` is forced by §12.1:965 and §15.3:1183, which both emit `rememberSaveable` *where the type is saveable* — a distinction the field has to be able to express. A third variant naming a host store is **deliberately not declared**: §12.1:968 describes the hook as `PersistentStore`, a `:engine:interpreter` interface (§33.4:2454), and a document field that referenced one would have to name it by id, and no section specifies what that id is or where the registry of store names would live. Declaring it would put an unverifiable string on the wire for a feature that does not exist. When it is needed it is one additive case, and by then there will be a section to derive its shape from.

`Saveable` carries no type list and no key. Which types are saveable is a code-generation concern (§12.2's `StateStrategy` is "the only place that knows *how* state is emitted"), and a key would be a store concern — the same reason §12.1:968 calls it a hook.

### 12.2 Framework independence

`StateStrategy` (codegen option, default `ComposeSnapshotState`) is the only place that knows *how* state is emitted. Later strategies (ViewModel-based, Molecule, Circuit) implement it without touching the model. The interpreter depends only on `StateStore`/`StateWriter` interfaces; the Compose-backed implementations live in `:engine:runtime`.

### 12.3 Rules

- State ids are stable; names are validated identifiers and unique per scope.
- `state.set` targets must be writable (not derived) and type-compatible (checked by analysis).
- Two-way binding sugar is intentionally absent (ADR-009). Editors compose `value = state ref` and `onValueChange = state.set(event.value)`.

---

## 13. Navigation

### 13.1 Model (document level)

```kotlin
@Serializable
public data class AppSpec(
    val packageName: String,
    val startPage: PageId,
    val navigation: NavigationSpec = NavigationSpec(),   // kind hints only; no library types
)
// Page.route + Page.params define the typed destination.
// Action: ActionStep(action = "nav.navigate", args = { page: Ref(page,"p_profile"), <paramName>: expr })

@Serializable
public data class NavigationSpec()   // no fields: the comment above is the whole of its content
```

`NavigationSpec` is declared with **no fields**, which is the narrowest thing the text supports and also the only shape that does not create a second source of truth. The comment says the field carries "kind hints only; no library types", and the destination it describes is already `Page.route` plus `Page.params` (§13.1:1018). The one hint a reader might expect — a `kind` naming a navigation library — is the decision `CodegenOptions.navigation` already holds as `NavigationStrategy` (§16.8:1321), and §4.5's single-resolver rule exists to stop exactly that decision being taken twice. §31.3:2240 defers the Navigation Compose / Navigation 3 strategies, so there is no second strategy for a document-level hint to disagree with yet either.

The record exists rather than being deleted because `AppSpec.navigation` has a default (§13.1:1016) and is public API: an empty record is a field that can gain a field later, whereas removing it is a break. A `data class` with no parameters is also how Kotlin spells "nothing here yet" without a lie in the declaration.

Analysis validates: start page exists; routes unique; `nav.navigate` targets exist; provided args match the target's `ParamDecl`s (name, type, required).

### 13.2 Abstractions

```kotlin
// interpreter — runtime side
public interface Navigator {
    public fun navigate(page: PageId, args: Map<ParamName, Value>)
    public fun back(): Boolean
}
// codegen — output side
public interface NavigationStrategy {
    public fun emitRoutes(pages: List<ResolvedPage>, ctx: FileContext): List<KtDeclaration>     // sealed Route
    public fun emitNavigateCall(target: ResolvedPage, args: List<KtExpr>): KtStmt
    public fun emitAppRoot(document: ResolvedDocument, ctx: FileContext): KtFile
}
```

Strategies: `SimpleBackStack` (MVP; zero dependencies; `mutableStateListOf<Route>` back stack, an `AppNavigator` class, `AppRoot`), `NavigationCompose` (post-MVP), `Navigation3` (post-MVP), `Custom`. Components never know about navigation; only the `nav.*` actions and generated `AppRoot` do.

---

## 14. Theme and Design Tokens

### 14.1 Model

```kotlin
@Serializable
public data class ThemeDecl(
    val id: ThemeId,
    val name: String,
    val base: ThemeBase = ThemeBase.Material3,
    val colors: Map<ColorRole, ColorSpec> = emptyMap(),            // light/dark pair per role
    val typography: Map<TextRole, TextStyleSpec> = emptyMap(),
    val shapes: Map<ShapeRole, ShapeSpec> = emptyMap(),
    val dimensions: Map<TokenName, Value.Dp> = emptyMap(),         // spacing scale etc.
    val custom: Map<TokenName, Value> = emptyMap(),                // brand tokens
    val componentDefaults: Map<ComponentType, Map<PropertyKey, PropertyValue>> = emptyMap(),   // post-MVP
)
@Serializable public data class ColorSpec(val light: ColorArgb, val dark: ColorArgb? = null)

@Serializable
public enum class ThemeBase { @SerialName("material3") Material3 }

@JvmInline @Serializable
public value class ColorRole(public val value: String) {
    init { require(value.isNotBlank()) { "Blank ColorRole" } }          // e.g. "primary", "surfaceVariant"
    override fun toString(): String = value
}
@JvmInline @Serializable
public value class TextRole(public val value: String) {
    init { require(value.isNotBlank()) { "Blank TextRole" } }           // e.g. "headlineMedium"
    override fun toString(): String = value
}
@JvmInline @Serializable
public value class ShapeRole(public val value: String) {
    init { require(value.isNotBlank()) { "Blank ShapeRole" } }          // e.g. "small", "extraLarge"
    override fun toString(): String = value
}
@JvmInline @Serializable
public value class TokenName(public val value: String) {
    init { require(value.isNotBlank()) { "Blank TokenName" } }          // e.g. "md.color.primary", "brand.accent"
    override fun toString(): String = value
}

public typealias TextStyleSpec = Map<PropertyKey, Value>   // size / weight / lineHeight / letterSpacing
public typealias ShapeSpec    = Map<PropertyKey, Value>   // corner radii
```

**The role vocabulary is open strings, and that is the decision this section was missing.** `ColorRole`, `TextRole`, `ShapeRole` and `TokenName` are value classes over `String` with no enumerated entries, and `TextStyleSpec`/`ShapeSpec` are open maps of `PropertyKey` to `Value`. The alternative was to write Material 3's role names down as the variants — `primary`, `onPrimary`, `surfaceVariant`, `headlineMedium`, … — and the reason not to is that §14.2:1110 *already mandates* the validation this gives up: *"validation checks the token exists in the selected theme"*, reported as `token.unknown` (§17.3:1384). A wrong role name is therefore a diagnostic, not a constructor failure, which means nothing needs the vocabulary to be closed for the document to be rejected. Freezing a third party's vocabulary into the wire before anyone has built a theme would instead make every rename a `FORMAT_VERSION` event — and the vocabulary's real shape is only knowable after a theme exists. The cost is stated rather than hidden: a typo in a role name is caught by analysis, not by `init`, and the value classes refuse only what is structurally impossible, a blank name.

The four value classes are one pattern, not four decisions, and they are value classes for the reason §5.2:195 gives for the ids: a role on the wire is a string, and a bare `String` in a signature is a string nothing can validate. Their names are namespaced and dotted in the shape §5.2:207-208 gives for `ComponentType("core.Column")` — `md.color.primary`, `brand.accent` (§14.1:1106 writes all three) — which is why they do **not** use `IdSyntax` (§5.2:202): that grammar is `[A-Za-z0-9_]{1,64}` and has no room for a dot. The constructor check is deliberately `isNotBlank()` and not a full grammar; the remaining rules are `token.unknown`'s to report (§14.2:1110).

`TextStyleSpec` and `ShapeSpec` are typealiases over `Map<PropertyKey, Value>` for the same reason `Node.props` is (§5.3:234): a bare `Map<String, Any>` is the shape §23.4's Konsist rule exists to forbid, and a typed key with a typed value is the requirement. A typealias rather than a `data class` because there is nothing to add — `PropertyKey` and `Value` are already `@Serializable` (§5.2, §5.4), so the map is, and a wrapper would be a record whose only field is its own payload. Their *keys* are not enumerated here, and that is a real gap rather than an oversight: §14.1:1062 declares the map and §14.2:1125 names "typography roles" as its content, but no section lists the individual properties, and the codegen bullet that emits `Typography(...)` takes whatever the map holds. The key set belongs to the schema next to the rest of the property specs, and a key outside it is `prop.unknown` (§17.3:1382), the existing code for exactly this.

`ThemeBase` is an `enum class` with one entry, and it is the one closed type in this section, because it answers a different question from the others. A *name* can be validated by lookup — §14.2:1110 already does that — but a *base* has to be dispatched on: `:engine:runtime` has to know how to build a Compose `MaterialTheme` and `:engine:codegen` has to know how to emit `lightColorScheme(...)` (§14.2:1124-1125). An open hierarchy would be worse than useless, because nothing in the model could resolve it: a document can only register new *types* through the data-defined mechanism D3 provides (§5.4:269, `EnumTypeSpec`/`ObjectTypeSpec`), and there is no such mechanism for a design system — a `when` over an unknown base would have no else branch that is honest. `Material3` is the only entry the text names (§14.1:1060's default, §14.2:1125's MVP line); a second base arrives with the post-MVP work §14.2:1125 lists, and it is a `FORMAT_VERSION` event because an unknown enum tag fails to decode.

Tokens are referenced as `Value.Token(kind, name)`, e.g. `md.color.primary`, `md.typography.headlineMedium`, `brand.accent`. Raw values (`Value.Color`) remain allowed and are *literal* overrides.

### 14.2 Resolution

The analyzer resolves each token to a `ResolvedToken(kind, name, source = Material | Custom)`; validation checks the token exists in the selected theme.

**Which theme is "the selected theme",** since `UiDocument` declared a map of them and nothing that named one. The answer is `UiDocument.theme: ThemeId?` (§5.5), a nullable field with a default, and the resolution is a total function of three cases:

| `theme` | `themes` | Resolved against |
|---|---|---|
| an id present in `themes` | — | that `ThemeDecl` |
| an id absent from `themes` | — | nothing; every `Value.Token` in the document is `token.unknown` |
| `null` | empty | nothing; base defaults only (§14.2:1125) |
| `null` | exactly one entry | that entry — a single-theme document does not have to restate itself |
| `null` | two or more | nothing; every `Value.Token` in the document is `token.unknown` |

The last two rows are the ones worth arguing for. Defaulting to "the only theme" is what makes `themes = { "t": … }` — the shape §30.2's excerpt implies and what any first document will look like — work without a second field, and the cost is a two-line rule rather than a nullable every author has to fill in. The fifth row is a document that has themes and selects none, which is a mistake; it is answered with `token.unknown` on every token it uses rather than with a new diagnostic, for two reasons. A diagnostic code is a published contract (§17.2:1358, a value class over a catalog), and inventing one to say "you forgot a field" would add an entry to keep in step for a failure `token.unknown` already reports — loudly, once per token. And the mistake is not silent: a document in that state produces a diagnostic per token reference, so it cannot reach a user who never notices.
- **Runtime:** `ThemeHost` builds a Compose `MaterialTheme` from the `ThemeDecl` (light/dark chosen by `isSystemInDarkTheme()` or environment override); `md.*` tokens map to `MaterialTheme.colorScheme/typography/shapes`; custom tokens go through a `CompositionLocal<ForgeTokens>`.
- **Codegen:** emits `theme/AppTheme.kt` (`lightColorScheme(...)`/`darkColorScheme(...)`, `Typography(...)`, `Shapes(...)`) and `AppTokens` (a `@Immutable` class + `LocalAppTokens`). `md.color.primary` → `MaterialTheme.colorScheme.primary`; `brand.accent` → `AppTokens.current.accent` (helper generated).
- MVP: Material 3 base, light/dark colors, typography roles, shape roles, custom color/dp tokens. Post-MVP: `componentDefaults` (applied in the resolver so both backends see the same effective props).

---

## 15. Runtime Renderer

### 15.1 Flow

```
UiDocument ─► Analyzer(SchemaView) ─► ResolvedDocument
                                          │
                        UiRuntime(RendererRegistry, ModifierAppliers, Implementations)
                                          │
              @Composable UiScreen(page) ─► RenderNode(node) ─► ComponentRenderer.Render
```

### 15.2 Public surface

```kotlin
public class UiRuntime(
    public val renderers: RendererRegistry,
    public val modifiers: ModifierApplierRegistry,
    public val implementations: Implementations,      // FunctionImpls + ActionHandlers
    schema: Schema,
) { init { RuntimeCoverage.check(schema, this) } }

public class RuntimeEnvironment(
    public val navigator: Navigator,
    public val host: HostFunctions = HostFunctions.None,
    public val resources: ResourceProvider = ResourceProvider.Empty,
    public val appState: StateStore = SnapshotStateStore(),
    public val diagnostics: (RuntimeDiagnostic) -> Unit = {},
    public val hooks: RenderHooks = RenderHooks.None,        // editor integration point (§25)
)

@Composable
public fun UiScreen(
    runtime: UiRuntime,
    document: ResolvedDocument,
    page: PageId,
    environment: RuntimeEnvironment,
    args: Map<ParamName, Value> = emptyMap(),
    modifier: Modifier = Modifier,
)

public interface ComponentRenderer { @Composable public fun Render(node: ResolvedNode, scope: RenderScope) }
```

### 15.3 How each concern is handled

| Concern | Mechanism |
|---|---|
| **Component resolution** | The analyzer already validated the type. `RenderNode` does `runtime.renderers[node.spec.type]`; a missing renderer was rejected at `UiRuntime` construction. `doc.*` types go to `DeclInstanceRenderer`. |
| **Property evaluation** | `scope.props(node)` returns a `PropertyReader`. `Constant` props are pre-decoded at resolve time; `Dynamic` props run `Evaluator.eval(typedExpr, EvalScope)` **inside composition**, so reads of Compose snapshot state subscribe automatically. `p[Spec.key]` returns the typed value with defaults applied. |
| **Expression evaluation** | Pure `Evaluator` in `:engine:interpreter` over `TypedExpr`; `FunctionImpl`s from `Implementations`. |
| **Modifiers** | `scope.modifierFor(node)` folds `node.modifiers` in order through `ModifierApplier`s, threading a `ScopeBag` holding the active `RowScope/ColumnScope/BoxScope` receivers. |
| **Children & slots** | Renderers call `scope.RenderSlot(node, SlotName.Children)` inside the matching Compose lambda after `scope.withScope(...)`, so scoped modifiers resolve. Each child is wrapped in `key(child.id)`. |
| **Events** | `scope.handler(node, EventKey("onClick"))` returns a stable lambda `(args) -> Unit` that launches the `ActionExecutor` in the composition's coroutine scope; event args become `RefTarget.EventArg` values in the `EvalScope`. |
| **State** | `StateStore`/`StateWriter` interfaces; Compose implementation `SnapshotStateStore` uses `mutableStateOf` per slot. Page state is created with `remember(pageId)`/`rememberSaveable` for saveable types. |
| **Platform-specific behaviour** | `expect/actual` only inside `:engine:runtime` for concerns like back handling (`PlatformBackHandler`) and default font families; components with `availability` restricted to platforms are rejected at analysis if the target platform set excludes them. |
| **Errors** | Renderer exceptions are caught by a per-node error boundary (`RenderErrorBoundary`) that shows a placeholder and reports a `RuntimeDiagnostic` (crucial for editors). |
| **Theme** | `ThemeHost` wraps the page; renderers read tokens via `scope.theme`. |

### 15.4 Renderer example (Column, D6/D7 in action)

```kotlin
internal object ColumnRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope) {
        val p = scope.props(node)
        val spacing = p[ColumnSpec.spacing]
        Column(
            modifier = scope.modifierFor(node),
            verticalArrangement =
                if (spacing != null) Arrangement.spacedBy(spacing.dp)
                else EnumMaps.verticalArrangement(p[ColumnSpec.verticalArrangement]),
            horizontalAlignment = EnumMaps.horizontalAlignment(p[ColumnSpec.horizontalAlignment]),
        ) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, SlotName.Children) }
        }
    }
}
```

### 15.5 Independence from the editor

`:engine:runtime` has no reference to editing or editor concepts except the neutral `RenderHooks`:

```kotlin
public interface RenderHooks {
    @Composable public fun Decorate(node: ResolvedNode, content: @Composable () -> Unit)   // selection overlays, bounds capture
    public fun onNodeMeasured(id: NodeId, bounds: Rect) {}
    public companion object { public val None: RenderHooks }
}
```

### 15.6 Compose specifics

- **Stability:** model/resolved classes cannot carry Compose annotations (no Compose dependency). `:engine:runtime` and consumers use a **Compose stability configuration file** listing `dev.rotalex.lutter.**` as stable (`stability_config.conf`), shipped by the convention plugin.
- **Identity:** `key(node.id)` per node.
- **Incremental recomposition:** unchanged subtrees of `ResolvedNode` keep reference equality between edits (analyzer memoization, §29), so Compose skips them.

---

## 16. Code Generation

### 16.1 Pipeline

```
ResolvedDocument ─► GenPlan (files, names, imports policy) ─► Kotlin IR (KtFile…) ─► Printer ─► GeneratedFiles
                                                                        ▲
                                                 CodegenBindings (from specs) + CodegenExtensions (overrides only)
```

### 16.2 Approach decision (ADR-004)

Custom **Kotlin IR + printer** in `commonMain`, not KotlinPoet, not templates. Reasons: KotlinPoet is JVM-only; string templates cannot guarantee valid Kotlin or deterministic import handling; the required Kotlin subset is small (calls with named args, trailing lambdas, member chains, literals, string templates, `if`, assignments, class/function/property declarations).

```kotlin
public sealed interface KtExpr {
    public data class Literal(val text: String) : KtExpr                       // produced only by LiteralPrinter (escaped)
    public data class Name(val name: String) : KtExpr
    public data class Member(val receiver: KtExpr, val name: String, val safe: Boolean = false) : KtExpr
    public data class Call(val callee: KtCallee, val args: List<KtArg>, val trailing: KtLambda? = null) : KtExpr
    public data class Chain(val receiver: KtExpr, val calls: List<KtCall>) : KtExpr            // Modifier.a().b()
    public data class Binary(val op: KtOp, val l: KtExpr, val r: KtExpr) : KtExpr              // precedence-aware
    public data class Unary(val op: KtOp, val e: KtExpr) : KtExpr
    public data class IfElse(val c: KtExpr, val a: KtExpr, val b: KtExpr) : KtExpr
    public data class StringTemplate(val parts: List<KtTemplatePart>) : KtExpr
    public data class Lambda(val params: List<String>, val body: List<KtStmt>) : KtExpr
}
public data class KtSymbol(val pkg: String, val name: String, val member: String? = null)     // "androidx.compose.material3", "Text"
public sealed interface KtDeclaration {
    public data class Function(val name: String, val annotations: List<KtSymbol>, val params: List<KtParam>, val body: List<KtStmt>)
    public data class Property(val name: String, val annotations: List<KtSymbol>, val type: KtExpr?, val mutable: Boolean,
                               val initializer: KtExpr?, val delegate: KtExpr?, val getter: KtExpr?)  // top-level or class member; only here is `get()` legal
    public data class Class(val name: String, val annotations: List<KtSymbol>, val members: List<KtDeclaration>)
}
public sealed interface KtStmt {
    public data class Expr(val expr: KtExpr)
    public data class LocalProperty(val name: String, val type: KtExpr?, val mutable: Boolean,
                                    val initializer: KtExpr?, val delegate: KtExpr?)   // function-local; no getter
    public data class Assign(val target: KtExpr, val value: KtExpr)
}
public class KtFile(val pkg: String, val header: String?, val declarations: List<KtDeclaration>)   // imports are computed
```

Symbols are *references*, never text: the printer collects them into an `ImportSet`.

### 16.3 Printer: deterministic formatting rules

- 4-space indent, LF, one blank line between top-level declarations, trailing newline.
- Call with 0 args: `Foo()`; with exactly one arg that is short (≤ 60 chars) and positional-eligible: inline; otherwise **one argument per line with trailing comma** (matches Compose community style).
- Trailing lambda syntax when the callee's last parameter is a slot lambda.
- Modifier chains: first line `modifier = modifier` (root) or `Modifier`, then one `.call(...)` per line.
- Line width 100 for expression wrapping decisions; no reflow of string literals.
- No wildcard imports; imports sorted lexicographically by full name; aliased imports after plain ones; default imports (`kotlin.*`, `kotlin.collections.*`, etc.) omitted.
- Header comment `// Generated by Forge. Do not edit.`; **no timestamps or versions** by default (`HeaderPolicy.Minimal`), so output only changes when the document changes.

### 16.4 Imports, aliases, identifiers

- Every `KtSymbol` used is recorded. Conflicts of simple names in one file (e.g. a user component named `Text` vs `androidx.compose.material3.Text`): **declared names win**; among library symbols the lexicographically first FQN keeps the simple name, others are aliased deterministically (`import androidx.compose.material.Text as MaterialText`).
- `NamingPolicy` maps document names to identifiers: page/component names must already be valid Kotlin identifiers (validation error `name.invalid_identifier` with a suggested fix), keywords are rejected rather than silently backticked. Generated helper names (`HomeScreenState`, `Route.Home`) are derived deterministically; collisions get numeric suffixes in sorted order. **User-defined identifiers are preserved verbatim.**

### 16.5 What is generated (file layout, `GeneratedLayout.Standard`)

```
generated/
  App.kt                          AppRoot(), theme wrapper, navigation host
  Navigation.kt                   sealed interface Route, AppNavigator (strategy-dependent)
  AppHost.kt                      interface AppHost (host functions)
  state/AppState.kt               AppState, LocalAppState
  model/Models.kt                 data classes from DataModelDecl
  theme/AppTheme.kt               AppTheme, AppTokens
  screens/HomeScreen.kt           @Composable fun HomeScreen(...) (+ HomeScreenState)
  components/ProfileCard.kt       @Composable fun ProfileCard(...) (from ComponentDecl)
```

`GeneratedFile(path: String, content: String)`; `GeneratedFiles` is a sorted list; codegen **never touches the filesystem** (`FileSink` is an optional adapter in the CLI).

### 16.6 Emission rules

| Element | Rule |
|---|---|
| Node → call | From `CodegenBinding.ComposeCall`: named params from `ParamBinding`s (omitting defaults when `omitWhenDefault`), event lambdas from `EventBinding`, slots as trailing/named lambdas. |
| Nested nodes | Inlined in slot lambdas; sub-trees are **not** extracted into helpers automatically (developers may promote them to `ComponentDecl`s). |
| Modifiers | `Modifier`-chain per node; root of a page/component uses the `modifier` parameter (`modifier = modifier.fillMaxSize()...`). |
| Enums | `EnumEntrySpec.kotlin` symbol. |
| Tokens | Token → `KtSymbol`/member expression. |
| Expressions | Typed AST → `KtExpr` via `FunctionEmit` and precedence-aware operators. |
| Actions | `ActionSequence` → lambda body statements. |
| State | Per `StateStrategy` (§12). |
| Custom component instance | `ProfileCard(title = "…", content = { … })` |
| Data models | `data class User(val name: String, val age: Int)`; `Nullable` → `?`; `ListOf` → `List<T>`. |

### 16.7 Validation and failure handling

- `Analyzer` includes a **codegen-feasibility pass** (missing binding, unsupported strategy, identifier collisions). Codegen refuses to run on a `ResolvedDocument` that carries errors.
- The generator itself never throws for domain errors; it returns `CodegenResult(files, diagnostics)`. Internal invariant breaches (bug) throw `CodegenBug`.
- "Avoid generating invalid Kotlin" is enforced by construction (IR only), by escaping in `LiteralPrinter`, by identifier validation, and by the compile harness (§28.5) with fuzzed documents.

### 16.8 Options

```kotlin
public data class CodegenOptions(
    val basePackage: String,
    val layout: GeneratedLayout = GeneratedLayout.Standard,
    val navigation: NavigationStrategy = SimpleBackStack,
    val state: StateStrategy = ComposeSnapshotState,
    val header: HeaderPolicy = HeaderPolicy.Minimal,
    val formatting: FormattingOptions = FormattingOptions.Default,
    val targetPlatforms: Set<PlatformTag> = PlatformTag.ALL,
)
public class KotlinGenerator(schema: SchemaView, options: CodegenOptions, extensions: CodegenExtensions = CodegenExtensions.None) {
    public fun generate(document: ResolvedDocument): CodegenResult
}
```

---

## 17. Validation

### 17.1 Pipeline (`:engine:analysis`)

```
UiDocument
  1. StructuralPass      ids valid/unique, roots exist, single parent, no cycles, no orphans, slot ids exist       [BLOCKING]
  2. NamingPass          identifiers valid/unique (pages, components, state, params)                              [BLOCKING for codegen]
  3. SchemaPass          component/modifier/action known, props known, required present, value/type match,
                         enum entries, slot cardinality/accepts, scope requirements, property rules, platform availability
  4. ReferencePass       pages/components/resources/data models/tokens/state exist; kinds match; nav args match params
  5. ExpressionPass      typecheck every Computed, scope resolution (params, state, event args, items)
  6. ActionPass          action params types, branch presence, state.set targets writable and type-compatible
  7. ResolutionPass      build ResolvedDocument (only if no BLOCKING/ERROR), apply defaults, compute scopes, resolve tokens
  8. CodegenFeasibility  bindings valid, identifier collisions, strategy support
```

Passes run in order; a *blocking* pass with errors stops later passes (they would be meaningless). Non-blocking passes always run and accumulate. `Analyzer` never throws on invalid documents.

### 17.2 Structured diagnostics

```kotlin
public data class Diagnostic(
    val severity: Severity,                       // Error | Warning | Info
    val code: DiagnosticCode,                     // value class over a catalog of constants
    val location: DiagnosticLocation,
    val message: String,                          // rendered from code + args (English)
    val args: Map<String, String> = emptyMap(),   // machine-readable parameters
    val suggestions: List<Suggestion> = emptyList(),   // e.g. "rename to HomeScreen", or a Patch
)
public data class DiagnosticLocation(
    val pageId: PageId? = null, val componentDeclId: ComponentDeclId? = null,
    val nodeId: NodeId? = null, val property: PropertyKey? = null,
    val modifierIndex: Int? = null, val event: EventKey? = null, val path: List<String> = emptyList(),
)
public class AnalysisResult(public val diagnostics: List<Diagnostic>, public val resolved: ResolvedDocument?) {
    public val hasErrors: Boolean
}
```

Diagnostics are sorted deterministically: `(severity desc, pageId, nodeId, code, property)`.

### 17.3 Code catalog (excerpt; each has a test)

| Code | Meaning |
|---|---|
| `struct.duplicate_id`, `struct.missing_node`, `struct.cycle`, `struct.orphan`, `struct.multiple_parents`, `struct.missing_root` | Structural invariants |
| `component.unknown`, `component.slot_unknown`, `component.slot_cardinality`, `component.child_not_allowed`, `component.conflicting_properties` | Schema |
| `prop.unknown`, `prop.required_missing`, `prop.type_mismatch`, `prop.enum_entry_invalid`, `prop.range`, `prop.not_bindable`, `value.non_finite` | Properties |
| `modifier.unknown`, `modifier.scope_missing`, `modifier.arg_invalid` | Modifiers |
| `ref.dangling`, `ref.kind_mismatch`, `token.unknown`, `resource.unknown` | References |
| `expr.unknown_function`, `expr.type_mismatch`, `expr.nullable_access`, `expr.unresolved_ref` | Expressions |
| `action.unknown`, `action.arg_invalid`, `action.state_not_writable`, `nav.args_mismatch` | Actions/navigation |
| `name.invalid_identifier`, `name.duplicate`, `name.keyword` | Naming |
| `codegen.no_binding`, `codegen.strategy_unsupported`, `codegen.name_collision` | Feasibility |
| `plugin.missing`, `plugin.version_mismatch`, `version.component_newer` | Loading |

### 17.4 Component-defined validation

`ComponentSpec.rules` (declarative: `MutuallyExclusive`, `RequiresOneOf`, `Range`, `SlotRequires`) cover ~all cases without code. A `ComponentValidator` code hook exists as a last resort and receives only read-only, resolved data.

---

## 18. Serialization

### 18.1 Envelope

```json
{
  "format": "forge.ui-document",
  "formatVersion": 1,
  "schemaVersion": 1,
  "payload": { }
}
```

`formatVersion` changes only if the envelope layout changes. `schemaVersion` versions the payload.

### 18.2 Canonical JSON (kotlinx-serialization-json)

- `Json { encodeDefaults = false; ignoreUnknownKeys = false; classDiscriminator = "k"; explicitNulls = false }` plus a **canonical writer**: object keys emitted in sorted order (UTF-16 code unit lexicographic, locale-independent), arrays keep model order, 2-space pretty print, LF, trailing newline.
- **Sets** have no model order, so the writer gives them one: a set is emitted as an array of its elements ordered by each element's own canonical encoding, compared as a string. Iteration order reaches the encoder from a `Set` and differs per target, so a rule stated here is the only thing that keeps the bytes equal across them. `ResourceVariant.qualifiers` (§20) is the only `Set` in the document payload, and `Patch.touchedNodes` (§26.2) the only other serializable one, so the rule applies to both unchanged. A `Set` of *value classes* is written as a bare JSON array with no surrounding structure, which makes this ordering rule the whole of its canonical form.
- `nodes` table sorted by `NodeId`; each node keyed by id, so unrelated edits do not touch each other's lines and Git merges cleanly.
- Numbers use the canonical decimal formatter (D1); floats never rely on `toString()`.
- Because defaults are not encoded, **defaults of model data classes are frozen**: changing one requires a migration (documented in §19).
- The Json configuration is created in exactly one place (`ForgeJson`).

### 18.3 Codec and storage

```kotlin
public interface DocumentCodec {
    public val formatId: String
    public fun encode(document: UiDocument): ByteArray
    public fun decode(bytes: ByteArray, options: DecodeOptions = DecodeOptions()): DecodeResult   // includes migration report
}
public interface DocumentStorage { public suspend fun read(location: String): ByteArray; public suspend fun write(location: String, bytes: ByteArray) }
public object JsonDocumentCodec : DocumentCodec
```

`DecodeResult` carries `document`, `migratedFrom`, and `warnings` (`plugin.missing`, unknown component types retained, etc.).

### 18.4 JSON vs CBOR vs binary

| | JSON | CBOR | Custom binary |
|---|---|---|---|
| Human-readable/Git-diffable | ✅ | ❌ | ❌ |
| Debuggable | ✅ | ⚠ | ❌ |
| Size/speed | OK | Better | Best |
| Migrations | Operate on `JsonElement` | Cannot share JSON migrations | Same problem |
| **Verdict** | **Primary and only MVP format** | Optional later transport/cache of the *current* schema | Not planned |

Architecture allows another codec (`DocumentCodec`) without touching the model; migrations always go through JSON (ADR-006).

### 18.5 Example fragment

```json
"n_title": {
  "type": "m3.Text",
  "props": {
    "style": { "k": "token", "kind": "typography", "name": "md.typography.headlineMedium" },
    "text":  { "k": "const", "value": { "k": "str", "v": "Welcome" } }
  }
}
```

(`PropertyValue.Const` is a tagged wrapper; a serializer optimization collapses `{"k":"const","value":X}` to `X` in the encoding while keeping the model unchanged — implemented in one `PropertyValueSerializer`, covered by round-trip tests.)

---

## 19. Schema Evolution

### 19.1 Two levels of versioning

| Level | Version field | Owner | What changes |
|---|---|---|---|
| **Document schema** | `schemaVersion` (envelope) | `:engine:serialization` | Model records, `Value` union, `Expr`, `ActionStep`, envelope-adjacent structure |
| **Component/plugin contract** | `ComponentSpec.version` recorded in `meta.componentVersions` | Component author (builtins/plugins) | Property renames, type changes, slot renames of a specific component |

### 19.2 Document migrations

```
file schemaVersion = 1
        │  Migration 1→2   (JsonObject → JsonObject)
        ▼
schemaVersion = 2
        │  Migration 2→3
        ▼
CURRENT (decode into UiDocument)
```

```kotlin
public interface Migration { public val from: Int; public val to: Int; public fun apply(payload: JsonObject): JsonObject }
public class MigrationChain(migrations: List<Migration>) {
    init { require(contiguous && sortedByFrom && noGaps) }
    public fun migrate(envelope: JsonObject): MigrationResult      // errors: UnsupportedFutureVersion(found, max)
}
```

**Boundaries and responsibilities:**
- Migrations are **pure functions on JSON**, deterministic, and never depend on the current model classes (they must keep working when those classes change).
- They must not depend on the schema/registries (`:engine:serialization` does not depend on `:engine:schema`).
- Released migrations are **immutable**; a fix is a new migration.
- Newer-than-supported `schemaVersion` → `UnsupportedFutureVersion` (a read-only "inspect" mode may be offered by tools).
- Pre-1.0 policy: documents produced by `0.x` snapshots are **not** supported; `schemaVersion = 1` is frozen at MVP release. From then on, any change to serialized shape requires a version bump and a migration.

### 19.3 Component contract migrations

```kotlin
public class SpecMigration(val type: ComponentType, val from: Int, val to: Int, val apply: (NodePatchScope) -> Unit)
```

Executed by a `SpecMigrationPass` **after decoding** (they may use the schema), against the document's recorded `componentVersions`. Example: `m3.Text` v1 → v2 renames property `label` → `text`. Each pass produces a patch, so the result is reviewable in diff form and can be undone by the editor as a single "upgrade" transaction.

### 19.4 Unknown data policy

Unknown component types, properties, modifiers and actions are **preserved** (round-trip) and reported as `component.unknown` etc. This is possible because decoding is schema-free (D4). Unknown *envelope* or *model* fields are errors unless `DecodeOptions.lenient` (tools only).

### 19.5 Test policy

`src/commonTest/resources/migrations/v1.json`, `v2.json`, … are kept forever. Each is migrated to current and compared with a golden `vN.migrated.json`. A property test asserts idempotence of `decode(encode(x)) == x` at current version.

---

## 20. Resource System

```kotlin
@Serializable
public data class ResourceDecl(
    val id: ResourceId, val name: String, val kind: ResourceKind,       // String | Image | Font | Icon | File  (no Color — see below)
    val variants: List<ResourceVariant>,                                // qualifiers: locale, density, theme
)
@Serializable public data class ResourceVariant(val qualifiers: Set<Qualifier>, val source: ResourceSource)
@Serializable public sealed interface ResourceSource {
    @SerialName("text")     data class Text(val value: String)
    @SerialName("bundled")  data class Bundled(val path: String)        // project-relative
    @SerialName("remote")   data class Remote(val url: String)
    @SerialName("embedded") data class Embedded(val hash: String)       // content-addressed blob store (post-MVP)
}

@Serializable
public enum class ResourceKind {
    @SerialName("string") String
    @SerialName("image")  Image
    @SerialName("font")   Font
    @SerialName("icon")   Icon
    @SerialName("file")   File
}

@Serializable
public sealed interface Qualifier {
    @SerialName("locale")  data class Locale(val tag: String) : Qualifier     // BCP-47
    @SerialName("density") data class Density(val dpi: Int) : Qualifier
    @SerialName("theme")   data class Theme(val variant: String) : Qualifier  // light | dark | …
}
```

**`Color` is not a `ResourceKind`, and the `kind` comment used to say it was.** This section contradicted itself: that comment listed `Color` among the six, and the MVP bullet below says *"Colors/typography are tokens (§14), not resources."* The bullet wins, and it is the stronger of the two statements — it is categorical and it names the owner, while the comment is a trailing enumeration. Three things agree with it: §14.1 gives colours a home (`ThemeDecl.colors`, `ColorSpec`, `Value.Color`), §9.2:768 gives the editor a colour picker and the generator `Color(0xFF6200EE)` rather than a resource reference, and `ResourceSource` has no variant that would carry a colour — the four above are text, a path, a URL and a hash, and a colour is none of them. `Color` is therefore absent from the enum above and the comment is corrected in place. The five remaining kinds are the ones §20:1559 scopes: `String` end-to-end in MVP, `Image`/`Font`/`File` modeled and validated now and implemented in wave 2, `Icon` backed by an `IconSet` in the `TypeRegistry` (§20:1560).

`Qualifier`'s three cases are exactly the three this section's comment names. Their payloads are the minimum that distinguishes them and nothing more: a BCP-47 tag because §20:1557's `stringFor(id, locale)` needs one; an integer `dpi` because density is a number and the alternative is a second closed vocabulary; and an open `variant` string for theme, because a theme qualifier is the same open-string situation as §14.1's roles and the same answer applies. Note what a `theme` qualifier is *not*: it is not light/dark for colours, because §14.1:1068's `ColorSpec(light, dark)` already carries that pair, and duplicating it would give colour resolution two sources.

**`qualifiers` stays a `Set`, and the determinism obligation goes to the canonical writer.** §18.2:1414 specifies the order of object keys and of arrays and says nothing about sets, so a set's iteration order — which differs per target — would otherwise reach the bytes and let variant selection differ between a JVM and a Wasm build of the same document. Two options close it and one is right. Making it a `List` would fix the bytes and introduce a worse problem: qualifiers are a *predicate* over the environment, so their order carries no meaning, and a list invites every reader to read priority into a position that selection is not allowed to honour. Instead §18.2 adds the rule that a set is written as an array ordered by its elements' canonical encodings, and variant selection is defined as set membership against the environment's qualifier set — which is order-free by construction, so it is correct even where the bytes are not canonical. That is the same division of labour as §5.3's "sorted by key when written" on `Node.props`: the model states the semantic, the writer states the bytes.

- The **core never references Android resources or `Res`**. References are `Value.Ref(kind=resource, id)`.
- **Runtime:** `ResourceProvider` interface (`suspend fun load(id): LoadedResource`, plus `stringFor(id, locale)`), implemented per platform (`androidMain`, `desktopMain`, later `wasmJsMain`, `iosMain`) in `:engine:builtins-compose`/samples.
- **Codegen:** `ResourceStrategy` emits `composeResources/…` layout metadata and `Res.string.foo` / `stringResource(Res.string.foo)` references. Copying binary assets is a *scaffolding* task (§33), not a codegen task.
- **MVP:** `String` resources end-to-end (runtime + codegen); `Image`, `Font`, `File` are modeled and validated, implemented in wave 2. Colors/typography are tokens (§14), not resources.
- Icons: `IconSet` registered in `TypeRegistry` (name → Kotlin symbol, e.g. Material Icons *core* only in MVP to avoid the extended icons artifact).

---

## 21. KMP/CMP Architecture

### 21.1 Targets and source sets

| Module class | Targets now | Targets later | Source sets used |
|---|---|---|---|
| Pure (`model`, `schema`, `serialization`, `interpreter`, `analysis`, `editing`, `codegen`, `builtins`) | Android, Desktop(JVM) | iOS, wasmJs, js | `commonMain` only (+ tiny `nonWebMain` for filesystem storage) |
| Compose (`runtime`, `builtins-compose`) | Android, Desktop(JVM) | iOS, wasmJs | `commonMain`, minimal `androidMain`/`desktopMain` |
| Tools (`cli`, `architecture-tests`) | JVM | — | JVM only |

**Canary targets:** from Phase 0, pure modules also declare `iosSimulatorArm64` and `wasmJs` targets with *compile + commonTest* jobs in CI. Cost is near-zero while `commonMain` stays pure, and it prevents JVM leakage into the domain. Do not publish them until supported.

### 21.2 Boundaries

| Concern | Where |
|---|---|
| Pure domain logic | `model`, `schema`, `interpreter`, `analysis`, `editing`, `builtins` — no Compose, no JVM APIs |
| Compose UI | `runtime`, `builtins-compose`, samples |
| Platform services | Interfaces in pure modules (`DocumentStorage`, `ResourceProvider`, `Navigator`, `HostFunctions`, `FileSink`); implementations at the edges |
| Filesystem | `FileDocumentStorage`/`FileSink` in `nonWebMain` via `kotlinx-io` (verify target coverage at kickoff) or JVM-specific in `desktopMain`/`androidMain` |
| Resource loading | `ResourceProvider` implementations per platform |
| Code generation | `:engine:codegen` (`commonMain`, no filesystem) |
| Serialization | `:engine:serialization` (`commonMain`) |

### 21.3 `expect/actual` policy

Allowed only for: back-press handling, default font resolution, `nonWebMain` filesystem shim, benchmark clocks. Any other `expect/actual` in the domain layer requires an ADR.

### 21.4 Compose rules

Compose appears only in `runtime`, `builtins-compose`, samples and the future editor. No `@Composable` and no Compose type in any serialized or model type. Compose stability config applied to consumers.

---

## 22. Gradle Module Architecture

### 22.1 Modules

```
:engine:model               Records: ids, Value, TypeRef, Expr, ActionStep, Node, Page, ComponentDecl, UiDocument, DSL, indexes
:engine:schema              ComponentSpec/ModifierSpec/ActionSpec/FunctionSpec/TypeSpecs, Registry, Schema, SchemaBuilder, plugins (interfaces), ValueKinds, overlay
:engine:serialization       Envelope, canonical JSON, DocumentCodec, migrations, DocumentStorage interface
:engine:interpreter         Evaluator, FunctionImpl, ActionExecutor, ActionHandler, StateStore, Navigator (pure Kotlin)
:engine:analysis            Analyzer, passes, diagnostics, type checker, ResolvedDocument
:engine:editing             DocumentController, Command, Patch, PatchOp, History, DocumentDiff, IdRemapper
:engine:codegen             Kotlin IR, printer, imports, naming, generators, strategies, options
:engine:runtime             UiRuntime, RendererRegistry, RenderScope, ThemeHost, StateStore (Compose), env, hooks (Compose)
:engine:builtins            CorePack + Material3Pack specs/mappings/enums/modifiers/actions/functions + pure impls (no Compose)
:engine:builtins-compose    Renderers, modifier appliers, enum runtime maps for builtins (Compose)
:engine:test-support        Golden helper, fixtures, test schema, sample documents, fake env
:tools:cli                  `forge` JVM CLI: validate | generate | migrate | diff
:tools:architecture-tests   Konsist rules (JVM)
:integration:generated-compile   Compiles generated fixtures; runs conformance (runtime vs generated)
:samples:desktop-preview    CMP desktop app rendering a JSON document (not an editor)
(post-MVP) :engine:scaffold Project/build-file scaffolding from GeneratedFiles + ProjectSpec
```

### 22.2 Critical evaluation of the module list proposed in the requirements

| Proposed | Decision | Why |
|---|---|---|
| `:core` | **Renamed** to `:editing` (mutations/history) | "core" was ambiguous; the model is `:model` |
| `:model` | Kept | Foundation |
| `:schema` | Kept | Specs + registries (registries are tiny, live here) |
| `:serialization` | Kept | Independent of schema by design |
| `:registry` | **Removed** | A generic `Registry<K,V>` is ~50 lines; registries are owned by their spec domain |
| `:validation` | **Merged** into `:analysis` | Validation and resolution share passes; separate modules would duplicate the walk |
| `:expression` | **Split**: AST → `:model`; typecheck → `:analysis`; evaluation → `:interpreter` | Avoids a module that everything depends on in a cycle |
| `:actions` | **Merged**: AST → `:model`, specs → `:schema`, execution → `:interpreter` | Same |
| `:navigation`, `:theme` | **Removed** | Records in `:model`, tokens in analysis, strategies in codegen/runtime |
| `:runtime` | Kept | Only Compose consumer besides builtins-compose |
| `:codegen` | Kept | |
| *(new)* `:interpreter` | Added | Pure evaluation shared by runtime/tests; keeps `:runtime` thin and testable without Compose |
| *(new)* `:builtins`, `:builtins-compose` | Added | Keeps specs Compose-free; renderers separate |
| *(new)* `:test-support`, `:architecture-tests` | Added | Enforcement and shared fixtures |

Every module has an owner concern and a reason to exist; the total is 11 engine modules + 2 tools + 1 integration + 1 sample.

### 22.3 Build strategy

- `settings.gradle.kts`: `includeBuild("build-logic")`, `include(...)` all modules; typesafe project accessors; `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`.
- `gradle/libs.versions.toml`: single source of truth for all versions (Kotlin, Compose Multiplatform, AGP, kotlinx-serialization, kotlinx-coroutines, kotlinx-collections-immutable, kotlinx-io, Konsist, Kotest, kotlinx-benchmark, binary-compatibility validation). **Do not hard-code versions in modules.**
- **Kotlin strategy:** latest stable Kotlin 2.x (K2). Kotlin and Compose Multiplatform are upgraded *together* according to the official compatibility table; upgrade PRs are automated (Renovate/Dependabot) and gated by the full CI matrix. The Compose compiler is the Kotlin Gradle plugin (`org.jetbrains.kotlin.plugin.compose`).
- **Android target:** use AGP's KMP library plugin if stable at kickoff; otherwise `androidTarget()`.
- **Convention plugins** in `build-logic/`:
  - `forge.kmp.library` → targets (Android, `jvm("desktop")`, canaries), `explicitApi()`, `allWarningsAsErrors` in CI, opt-in annotations, test dependencies, `embedFixtures` task, API dump/validation.
  - `forge.kmp.compose` → applies compose plugin, stability config, Compose test deps.
  - `forge.jvm.tool` → JVM apps (CLI).
  - `forge.publishing` → maven publishing (later).
- **API discipline:** `explicitApi()` everywhere; `@EngineInternalApi` (`@RequiresOptIn`) marks "public across modules but unstable" declarations (Kotlin has no friend modules, so this is the substitute); ABI dumps (binary-compatibility-validator or KGP ABI validation, whichever is stable) committed from Phase 1.
- **Testing stack:** `kotlin.test`, `kotlinx-coroutines-test`, `kotest-property`, `compose.uiTest` (common), Konsist (JVM), `kotlinx-benchmark`.

```kotlin
// build-logic sketch (forge.kmp.library)
kotlin {
    explicitApi()
    androidTarget(); jvm("desktop")
    iosSimulatorArm64(); wasmJs { browser() }          // canaries: compile + commonTest only
    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
}
```

---

## 23. Dependency Graph

### 23.1 Direction (arrows = "depends on")

```
                    ┌────────────────────────────────────────────────────┐
                    │                    :engine:model                   │
                    └───────▲───────▲────────▲─────────▲─────────▲───────┘
                            │       │        │         │         │
                    :serialization  :schema ◄─┴─────────┴─────────┴────────────┐
                            ▲          ▲  ▲                                    │
                            │          │  └──────────────┐                     │
                            │    :interpreter        :editing                  │
                            │          ▲                                       │
                            │          │          :analysis (model, schema)    │
                            │          │              ▲            ▲           │
                            │          │              │            │           │
                            │          └──────────────┼──────► :runtime (Compose)
                            │                         │            ▲
                            │                    :codegen         │
                            │                         ▲     :builtins-compose ─► :builtins
                            │                         │                             (model, schema, interpreter)
                            └───────────────► :tools:cli (serialization, analysis, codegen, builtins)
```

Textual form:

```
model
  ↓
schema ─────────────┬────────────┬───────────┐
  ↓                 ↓            ↓           ↓
interpreter      analysis     editing     builtins (+interpreter)
  ↓                 ↓
  └────────► runtime (+analysis)         codegen (analysis)
                 ↓
          builtins-compose (runtime, builtins)

serialization → model            (never schema)
cli → serialization, analysis, codegen, builtins
```

### 23.2 Allowed dependency matrix (row may depend on column)

| | model | schema | serial. | interp. | analysis | editing | codegen | runtime | builtins | builtins-compose |
|---|---|---|---|---|---|---|---|---|---|---|
| **model** | — | | | | | | | | | |
| **schema** | ✓ | — | | | | | | | | |
| **serialization** | ✓ | ✗ | — | | | | | | | |
| **interpreter** | ✓ | ✓ | | — | | | | | | |
| **analysis** | ✓ | ✓ | | | — | | | | | |
| **editing** | ✓ | ✓ | | | | — | | | | |
| **codegen** | ✓ | ✓ | | | ✓ | | — | | | |
| **runtime** | ✓ | ✓ | | ✓ | ✓ | | | — | | |
| **builtins** | ✓ | ✓ | | ✓ | | | | | — | |
| **builtins-compose** | ✓ | ✓ | | ✓ | ✓ | | | ✓ | ✓ | — |

### 23.3 Explicitly forbidden

- `model → anything` (except kotlinx-serialization-core, kotlinx-collections-immutable).
- `model/schema/interpreter/analysis/editing/codegen/builtins → Compose`.
- `schema → analysis|runtime|codegen|interpreter`.
- `serialization → schema` (migrations must survive schema changes).
- `codegen ↔ runtime` (neither may reference the other).
- `analysis → interpreter|runtime|codegen`.
- `editing → runtime|codegen|analysis` (the editor composes them, the editing layer does not).
- `runtime → editing`, and no engine module → any editor module.
- `engine:* → tools:*|samples:*|integration:*`.
- Any platform API (`java.*`, `android.*`) in `commonMain`.

### 23.4 Enforcement

1. `verifyModuleGraph` Gradle task in `build-logic` compares each module's `project(...)` dependencies against an allow-list; runs on every CI build.
2. `:tools:architecture-tests` (Konsist): no `androidx.compose` imports outside allowed modules; no `java.`/`android.` in `commonMain`; every sealed subclass of persisted hierarchies has `@SerialName`; no `when` expression whose subject is `ComponentType`/`node.type` in engine code; no `Map<String, Any>`; no `object` with `var`; `Value` factories used for floats.
3. CI matrix compiles canary targets.

---

## 24. Public APIs

### 24.1 Stability tiers

| Tier | Contents | Policy |
|---|---|---|
| **Stable** (ABI validated) | `model` records and ids, `Schema`, `ComponentSpec` family, `Registry`, `DocumentCodec`, `Analyzer`, `Diagnostic`, `DocumentController`, `Command`/`Patch`, `KotlinGenerator`, `CodegenOptions`, `UiRuntime`, `RuntimeEnvironment`, `ComponentRenderer`, `Navigator`, `ResourceProvider` | Semver; breaking changes need migration notes |
| **Experimental** (`@EngineInternalApi`/`@ExperimentalForgeApi`) | `KtExpr` IR builders, `CodegenExtensions`, `RenderScope` internals, incremental analysis | May change in minors |
| **Internal** (`internal`) | Passes, printer internals, canonical writers, registry implementations | Free to change |

### 24.2 Key signatures (facade)

```kotlin
// Load → analyze → render / generate, the whole engine in five calls
val schema = Schema.build { install(CorePack); install(Material3Pack) }
val doc: UiDocument = JsonDocumentCodec.decode(bytes).document
val analysis: AnalysisResult = Analyzer(schema).analyze(doc)
val resolved = analysis.resolved ?: error(analysis.diagnostics)
val files: GeneratedFiles = KotlinGenerator(SchemaView(schema, doc), CodegenOptions("com.example.app")).generate(resolved).files
```

```kotlin
// Programmatic construction (model DSL, generic) + typed sugar (builtins)
val doc = buildDocument(name = "Demo", ids = SequentialIdGenerator()) {
    page("Home", route = "home") {
        column(modifiers = { fillMaxSize(); padding(16.dp) }, spacing = 8.dp) {      // typed sugar from :engine:builtins
            text("Welcome", style = md.typography.headlineMedium)
            button(onClick = { navigate("Profile") }) { text("Continue") }
        }
    }
    page("Profile", route = "profile") { column { text("Profile") } }
}
```

The generic layer is `node(type = ComponentType("core.Column")) { prop(key, value); slot(name) { … } }`; typed builders in `:engine:builtins` are thin extension functions over it.

---

## 25. Editor Integration API

### 25.1 Who owns what

The **engine owns the document**. An editor holds a `DocumentController` (or its own instance of it), never a copy of the model. Selection, viewport, panels, and drag state are editor-only concerns.

### 25.2 Controller

```kotlin
public class DocumentController(
    initial: UiDocument,
    private val schema: SchemaView,
    private val ids: IdGenerator,
    private val history: History = History(),
) {
    public val state: StateFlow<DocumentState>                    // document, revision, canUndo, canRedo, lastPatch
    public val changes: SharedFlow<DocumentChange>                // Patch + touched node ids
    public fun dispatch(command: Command): CommandResult          // Ok(patch) | Rejected(reasons: List<Diagnostic>)
    public fun <R> transaction(label: String, block: TransactionScope.() -> R): R   // multiple commands, one undo step
    public fun undo(): Boolean
    public fun redo(): Boolean
}
public data class DocumentState(val document: UiDocument, val revision: Long, val canUndo: Boolean, val canRedo: Boolean)
```

### 25.3 Commands (intent) vs Patches (mechanism)

```kotlin
public sealed interface Command {          // in :engine:editing; closed set, engine-owned
    data class InsertNode(val parent: NodeId, val slot: SlotName, val index: Int, val subtree: NodeTemplate) : Command
    data class RemoveNode(val id: NodeId) : Command
    data class MoveNode(val id: NodeId, val newParent: NodeId, val slot: SlotName, val index: Int) : Command
    data class DuplicateNode(val id: NodeId) : Command
    data class WrapNode(val id: NodeId, val wrapper: ComponentType, val slot: SlotName) : Command
    data class UnwrapNode(val id: NodeId) : Command
    data class SetProperty(val node: NodeId, val key: PropertyKey, val value: PropertyValue?) : Command
    data class SetModifiers(val node: NodeId, val modifiers: List<ModifierEntry>) : Command
    data class SetEventHandler(val node: NodeId, val event: EventKey, val actions: ActionSequence?) : Command
    data class RenameNode(val id: NodeId, val name: String?) : Command
    data class AddPage(val page: Page, val subtree: NodeTemplate) : Command
    data class ExtractComponent(val root: NodeId, val name: String) : Command           // subtree → ComponentDecl (wave 2)
    data class ApplyPatch(val patch: Patch) : Command
}
```

`Command → CommandPlanner.plan(document, schema): Result<Patch>`. Planners check schema policies (`SlotSpec.accepts/cardinality`) so an editor can offer only legal drop targets via `DropTargets.forNode(document, schema, nodeType)`.

### 25.4 Editor needs → engine API

| Editor capability | API |
|---|---|
| Inspect document, tree | `UiDocument`, `DocumentIndex`, `NodeTraversal` |
| Component palette | `schema.components.all()` metadata (category, icon, keywords) |
| Property panel | `ComponentSpec.properties`, `PropertySpec.editor` hints, `TypeRegistry` |
| Select nodes | `NodeId` (editor state), `DocumentIndex.pathTo` |
| Modify/insert/delete/reorder/duplicate | `Command`s |
| Undo/redo | `controller.undo()/redo()` |
| Preview | `UiScreen(runtime, resolved, page, env)` with `RenderHooks` for overlays and node bounds |
| Validation | `Analyzer.analyze` → `Diagnostic`s with `nodeId/property` for inline markers |
| Generate code | `KotlinGenerator.generate` |
| Drop-target legality | `DropTargets` |
| Clipboard | `NodeTemplate.capture(doc, ids)` / `InsertNode(...)` with `IdRemapper` |

### 25.5 Threading model

Controller mutations are synchronous and single-writer (callers serialize; the controller is not thread-safe by contract). `Analyzer`, `KotlinGenerator` are pure functions safe to run on background dispatchers with immutable inputs. Preview always renders a `ResolvedDocument` snapshot.

### 25.6 Other clients

CLI: decode → analyze → generate → sink. Tests: `DocumentController` with `SequentialIdGenerator`. Server: same pure APIs. Future web editor: pure modules with wasm target; `:runtime` when Compose for Web is enabled.

---

## 26. Undo/Redo

### 26.1 Options evaluated

| Option | Verdict |
|---|---|
| Full snapshot per step | Simple; with persistent maps cheap in memory, but gives no diff/patch semantics; leaves collaboration and sync without a foundation. Rejected as the *primary* mechanism. |
| **Command → forward/inverse Patch** | ✅ Chosen: small memory, exact semantics, reusable for diffing, sync, tests. |
| Event sourcing | Powerful but heavier; the patch log is effectively a subset of it. Deferred. |
| OT/CRDT | Only needed for concurrent collaboration. Deferred; `PatchOp`s are shaped so a transformation layer could be added. |

### 26.2 Model

```kotlin
public sealed interface PatchOp {                       // closed, serializable, deterministic
    data class InsertNodes(val parent: NodeId, val slot: SlotName, val index: Int, val nodes: List<Node>) : PatchOp   // whole subtree, root first
    data class RemoveSubtree(val root: NodeId, val captured: RemovedSubtree) : PatchOp        // captured for inversion
    data class MoveNode(val id: NodeId, val from: Placement, val to: Placement) : PatchOp
    data class SetProp(val node: NodeId, val key: PropertyKey, val old: PropertyValue?, val new: PropertyValue?) : PatchOp
    data class SetModifiers(val node: NodeId, val old: List<ModifierEntry>, val new: List<ModifierEntry>) : PatchOp
    data class SetHandler(val node: NodeId, val event: EventKey, val old: ActionSequence?, val new: ActionSequence?) : PatchOp
    data class SetNodeName(val node: NodeId, val old: String?, val new: String?) : PatchOp
    data class PutRecord(val ref: RecordRef, val old: Record?, val new: Record?) : PatchOp   // pages, decls, themes, resources, state, models
}
public data class Patch(val ops: List<PatchOp>) { public fun inverse(): Patch; public val touchedNodes: Set<NodeId> }
public class History(private val limit: Int = 200) {
    // entries: Transaction(label, patch, mergeKey?)
    // typing in a text field merges by mergeKey within a time window supplied by the editor (no clock inside the engine)
}
```

Because ops carry `old` values, the inverse needs no document lookup. Property test: for any valid doc `d` and patch `p` produced by a command, `apply(p.inverse(), apply(p, d)) == d`, and `apply(p, d)` is deterministic.

### 26.3 Rules

- Redo stack cleared on new command.
- Failed commands (rejected) never enter history.
- `transaction {}` groups multiple commands into one history entry (e.g. "Wrap in Card" or bulk paste).
- History is editor session state; it is not persisted in the document.

---

## 27. Plugin Architecture

### 27.1 What a plugin may provide (facets)

| Facet | Interface | Module type |
|---|---|---|
| Schema (components, modifiers, actions, functions, enum/object types, icon sets) | `SchemaContribution` | pure |
| Interpreter impls (function impls, pure action handlers) | `InterpreterContribution` | pure |
| Compose runtime (renderers, modifier appliers) | `RuntimeContribution` | Compose |
| Codegen (emitter overrides, strategies) | `CodegenContribution` | pure |
| Spec migrations | `SpecMigration` | pure |

```kotlin
public class PluginDescriptor(public val id: PluginId, public val version: String, public val apiVersion: Int, public val dependsOn: List<PluginRequirement> = emptyList())
```

### 27.2 MVP posture

- Plugins are **statically composed in code**: `Schema.build { install(MyPluginSchema) }`, `UiRuntime.builder().install(MyPluginRuntime)`. The built-in packs (`CorePack`, `Material3Pack`) are implemented as plugins using exactly this interface, which is what the third-party test validates.
- **No dynamic loading** (no `ServiceLoader`, no class scanning; those are not portable across KMP targets).
- The document records required plugins in `meta.plugins`; load-time check yields `plugin.missing`/`plugin.version_mismatch` diagnostics.
- Post-MVP options: manifest-based discovery for JVM tooling; sandboxed/remote component definitions for wasm; signed plugins. None of these require changes to the interfaces above.

---

## 28. Testing Strategy

### 28.1 Layers

| Layer | Modules | Examples |
|---|---|---|
| Unit | model, schema, serialization, interpreter, analysis, editing, codegen | ids validity; `Value` canonical formatting; registry duplicate detection; each migration; each diagnostic code has a positive and a negative test; expression type rules; action handlers with a fake `ActionEnv`; IR printer formatting; import alias resolution |
| Golden/snapshot | codegen, serialization, analysis (diagnostics) | JSON → expected Kotlin; migrated JSON; diagnostics text |
| Runtime (Compose UI test) | runtime, builtins-compose | Node → semantics (text, click, enabled); state interaction (`state.set` updates Text); error boundary |
| Conformance | integration | Runtime vs compiled generated code |
| Property-based (Kotest) | model, serialization, editing, analysis, codegen | encode/decode round-trip; patch/inverse; canonicalization order-independence; random valid docs → analysis has no errors → codegen compiles |
| Integration | integration, cli | deserialize → validate → render → generate → compile |
| Architecture | tools | Konsist rules, module graph |
| Benchmarks | model, serialization, analysis, codegen | §29 |

### 28.2 Golden tests (codegen)

```
engine/codegen/src/commonTest/resources/codegen/
    basic_column.json            basic_column.expected.kt
    button.json                  button.expected.kt
    nested_layout.json           nested_layout.expected.kt
    text_field_state.json        text_field_state.expected.kt
    expressions.json             expressions.expected.kt
    navigation_two_pages.json    navigation_two_pages.expected.kt   (multi-file: *.expected/ directory)
    custom_component.json        custom_component.expected.kt
    import_alias_conflict.json   import_alias_conflict.expected.kt
    …
```

`Golden.assertEquals(actual, "codegen/basic_column.expected.kt")`; update mode with `-Pgolden.update=true` (writes the file; CI forbids the flag). Fixtures are embedded as Kotlin constants by the `embedFixtures` Gradle task, so **identical goldens run on all targets** (Android, Desktop, and the wasm/iOS canaries), verifying cross-platform determinism.

### 28.3 Determinism tests

- `encode(x)` twice → identical bytes; `encode(decode(encode(x))) == encode(x)`.
- **Permutation test:** build the same document via different command orders / map insertion orders → identical bytes, identical diagnostics, identical generated files.
- Analyzer and generator run on shuffled internal orders (test-only seam) and must yield identical outputs.

### 28.4 Conformance (runtime ⇔ generated)

`:integration:generated-compile`:
1. Gradle task `generateFixtures` runs `:tools:cli` over all conformance fixtures and writes `build/generated/forge/kotlin/**` (a normal source dir of the module).
2. The task also emits `GeneratedFixtures.kt`: `val all: Map<String, @Composable (Modifier) -> Unit>`.
3. `ConformanceTest` (`runComposeUiTest`) for each fixture renders (a) `UiScreen(...)` and (b) `GeneratedFixtures.all[id]`, then compares **semantics dumps** (role, text, enabled, click actions, bounds, hierarchy) and on Desktop additionally **pixel equality** via `captureToImage()`. Interaction scripts (`click("Continue")`, `type("hello")`) run on both and re-compare.
4. The expression corpus (§10.7) is executed the same way.

### 28.5 Compile tests

Compilation of generated code is not simulated: it is a **real Gradle compile** of a CMP module that contains the generated sources. Additionally a nightly job generates N random valid documents (Kotest `Arb` driven by the schema), compiles them in one batch module and fails on any compiler error.

### 28.6 Full pipeline test

`Document(JSON) → decode → migrate → analyze → render (semantics) → generate → compile → (same semantics)` executed for every fixture in `integration/fixtures/`.

### 28.7 CI

| Job | Content |
|---|---|
| `check` | Build, unit tests (Desktop JVM + Android unit), Konsist, module graph, ABI check, `allWarningsAsErrors` |
| `canary` | Compile + `commonTest` for iOS simulator and wasmJs on pure modules |
| `conformance` | `:integration:generated-compile:desktopTest` |
| `nightly` | Fuzzed compile batch, benchmarks vs baseline, property tests with high iteration count |

---

## 29. Performance

Targets are initial engineering goals (measure first, then enforce with benchmark baselines); JVM Desktop on a typical developer laptop:

| Scenario (10,000 nodes) | Target |
|---|---|
| Decode JSON → `UiDocument` (incl. migration check) | < 250 ms |
| Encode canonical JSON | < 200 ms |
| Full analysis | < 200 ms |
| Full codegen (analysis reused) | < 300 ms |
| Single command (patch + new doc) | < 1 ms |
| Incremental re-analysis after a single-property edit | < 10 ms (Phase post-MVP) |

### 29.1 Where indexing/caching is justified

| Structure | Why | Where |
|---|---|---|
| `NodeTable` = persistent hash map | O(1) lookup; O(log n) structural-sharing updates | model |
| `DocumentIndex` (parent/owner/path) | Needed by nearly every editing and validation operation; O(n) once per document revision | model, cached in editing by `revision` |
| `ReferenceIndex` | Safe delete/rename, unused detection | model |
| Registry `Map` lookups | Hash lookups; `all()` pre-sorted at build time | schema |
| Analyzer memoization | Post-MVP: cache `ResolvedNode` per `(Node identity, inherited scope, schema revision)`; unchanged subtrees keep reference identity, giving Compose skip-ability | analysis |
| Expression compilation | Post-MVP: precompile `TypedExpr` into closures at resolve time | interpreter |

### 29.2 Not optimized prematurely

No custom hashing, no object pools, no parallel analysis in MVP. Codegen uses `StringBuilder` and precomputed import sets; string concatenation in hot paths is confined to the printer. Runtime: `key(node.id)`; big lists must use `LazyColumn` (wave 2) — the plain `Column` renderer does not virtualize, and validation warns (`perf.large_column`) beyond a configurable child count.

### 29.3 Benchmarks

`kotlinx-benchmark` suites with synthetic generators (`DocumentGenerator.balanced(nodes = 10_000, depth = 12)`); baselines stored in the repo; nightly CI alerts on >20% regression.

---

## 30. Example End-to-End Flow

### 30.1 Scenario

```
Screen "Home"
 └── Column (fillMaxSize, padding 16, spacing 8)
      ├── Text("Welcome")                       style = headlineMedium
      └── Button("Continue")  onClick → navigate("Profile")
Screen "Profile"
 └── Text("Profile")
```

### 30.2 Document (JSON payload excerpt)

```json
{
  "format": "forge.ui-document", "formatVersion": 1, "schemaVersion": 1,
  "payload": {
    "meta": { "name": "Demo",
              "plugins": [ { "id": "forge.core", "version": "1" }, { "id": "forge.material3", "version": "1" } ],
              "componentVersions": { "core.Column": 1, "m3.Button": 1, "m3.Text": 1 } },
    "app":  { "packageName": "com.example.demo", "startPage": "p_home" },
    "pages": {
      "p_home":    { "name": "Home",    "route": "home",    "root": "n_root" },
      "p_profile": { "name": "Profile", "route": "profile", "root": "n_profile_text" }
    },
    "nodes": {
      "n_btn": {
        "type": "m3.Button",
        "slots": { "content": ["n_btn_label"] },
        "events": { "onClick": { "steps": [
          { "action": "nav.navigate", "args": { "page": { "k": "ref", "kind": "page", "id": "p_profile" } } } ] } }
      },
      "n_btn_label":   { "type": "m3.Text", "props": { "text": { "k": "str", "v": "Continue" } } },
      "n_profile_text":{ "type": "m3.Text", "props": { "text": { "k": "str", "v": "Profile" } } },
      "n_root": {
        "type": "core.Column",
        "modifiers": [ { "type": "layout.fillMaxSize" },
                       { "type": "layout.padding", "args": { "all": { "k": "dp", "v": 16 } } } ],
        "props": { "spacing": { "k": "dp", "v": 8 } },
        "slots": { "children": ["n_title", "n_btn"] }
      },
      "n_title": {
        "type": "m3.Text",
        "props": { "style": { "k": "token", "kind": "typography", "name": "md.typography.headlineMedium" },
                   "text":  { "k": "str", "v": "Welcome" } }
      }
    }
  }
}
```

### 30.3 Programmatic creation (same document)

```kotlin
val doc = buildDocument("Demo", SequentialIdGenerator("n_")) {
    page("Home", route = "home") {
        column(modifiers = { fillMaxSize(); padding(16.dp) }, spacing = 8.dp) {
            text("Welcome", style = md.typography.headlineMedium)
            button(onClick = { navigate(page = "Profile") }) { text("Continue") }
        }
    }
    page("Profile", route = "profile") { text("Profile") }
}
```

### 30.4 Registry resolution and validation

```
Analyzer(schema).analyze(doc)
  1 StructuralPass     ✓ 6 nodes, 2 roots, every node has one parent
  3 SchemaPass         n_root  → components["core.Column"]      ✓ slot "children" (Many), provides {ColumnScope}
                       n_root  → modifiers["layout.fillMaxSize"], ["layout.padding"]  ✓
                       n_title → components["m3.Text"]  prop "style": Token(Typography) ✓ known token
                       n_btn   → components["m3.Button"] slot "content" (ExactlyOne, provides {RowScope}) ✓
                       n_btn.onClick → actions["nav.navigate"]  param page: Ref(Page) ✓
  4 ReferencePass      p_profile exists ✓ ; nav args match Profile params (none) ✓
  7 ResolutionPass     ResolvedDocument { Home: ResolvedNode(core.Column …), Profile: … }
Diagnostics: []   (hasErrors = false)
```

Introducing an error (`text = Int32(3)`) yields:

```
Diagnostic(Error, prop.type_mismatch, nodeId = n_title, property = "text", message = "Property 'text' expects Str but found Int32", args = {expected: str, found: i32})
```

and `AnalysisResult.resolved == null`.

### 30.5 Path A — runtime rendering

```
ResolvedNode(n_root, spec=core.Column,
    props   = { spacing = Constant(Dp 8), verticalArrangement = Default(Top), horizontalAlignment = Default(Start) },
    modifiers = [ fillMaxSize(), padding(all = Dp 16) ],
    slots   = { children = [ n_title, n_btn ] })
```

```kotlin
@Composable fun App(runtime: UiRuntime, resolved: ResolvedDocument) {
    val navigator = remember { BackStackNavigator(start = PageId("p_home")) }
    val env = remember { RuntimeEnvironment(navigator = navigator) }
    val current = navigator.current            // page + args (snapshot state)
    UiScreen(runtime, resolved, current.page, env, current.args)
}
```

Render steps: `UiScreen` → `ThemeHost` → `RenderNode(n_root)` → `ColumnRenderer` (§15.4) → `Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(8.dp), Alignment.Start) { RenderSlot(children) }` → `TextRenderer` → `Text("Welcome", style = MaterialTheme.typography.headlineMedium)` → `ButtonRenderer` with `onClick = handler(n_btn, onClick)`. A click runs `ActionExecutor`, whose `nav.navigate` handler calls `navigator.navigate(p_profile, {})`; the `current` snapshot state changes and `Profile` renders.

### 30.6 Path B — code generation

```
ResolvedDocument ─► GenPlan[App.kt, Navigation.kt, screens/HomeScreen.kt, screens/ProfileScreen.kt, theme/AppTheme.kt]
                 ─► KtFile IR ─► Printer ─► GeneratedFiles
```

`screens/HomeScreen.kt`:

```kotlin
// Generated by Forge. Do not edit.
package com.example.demo.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.demo.AppNavigator
import com.example.demo.Route

@Composable
fun HomeScreen(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Welcome",
            style = MaterialTheme.typography.headlineMedium,
        )
        Button(
            onClick = {
                navigator.navigate(Route.Profile)
            },
        ) {
            Text("Continue")
        }
    }
}
```

`Navigation.kt` (SimpleBackStack strategy):

```kotlin
// Generated by Forge. Do not edit.
package com.example.demo

import androidx.compose.runtime.mutableStateListOf

sealed interface Route {
    data object Home : Route
    data object Profile : Route
}

class AppNavigator(private val backStack: MutableList<Route> = mutableStateListOf(Route.Home)) {
    val current: Route get() = backStack.last()
    fun navigate(route: Route) { backStack.add(route) }
    fun back(): Boolean = if (backStack.size > 1) { backStack.removeAt(backStack.lastIndex); true } else false
}
```

Both paths consumed the *same* `ResolvedNode`; the conformance test (§28.4) renders `UiScreen(Home)` and the compiled `HomeScreen` and compares semantics/pixels, and clicks "Continue" in both to verify navigation.

---

## 31. MVP

### 31.1 Proves

`Document → Registry → Validation → Runtime → Code Generation → Serialization`, plus migrations, state, actions and reusable components.

### 31.2 In scope

| Area | MVP content |
|---|---|
| Model | Full record model (§5–6), DSL, indexes, canonical numerics |
| Schema | Registries (5), `SchemaBuilder`, overlay, plugin interfaces (static composition) |
| Components | wave 1 (Column, Row, Box, Spacer, SlotOutlet, Text, Button, TextField, Card) + `doc.*` components with params & slots |
| Modifiers | fillMaxSize, fillMaxWidth, fillMaxHeight, padding, size/width/height, weight, align, background, clip(shape), border, clickable, alpha |
| Types | bool, i32, i64, f32, f64, str, color, dp, sp, enum, token, ref, list, object, nullable |
| Expressions | §10.2 subset, textual syntax excluded |
| Actions | nav.navigate, nav.back, state.set, flow.if, host.call, ui.showSnackbar |
| State | Component-local, page, app, derived |
| Navigation | Model + `SimpleBackStack` strategy |
| Themes | Material 3, light/dark, colors, typography, shapes, custom color/dp tokens |
| Resources | String resources end-to-end; other kinds modeled/validated |
| Serialization | Canonical JSON, envelope, migrations framework + at least one real historical fixture chain |
| Validation | All passes and the diagnostics catalog in §17.3 |
| Runtime | Full renderer for MVP components, error boundary, `RenderHooks` |
| Codegen | IR/printer, imports/aliases, naming, all MVP features, `Standard` layout, deterministic headers |
| Editing | `DocumentController`, commands (Insert/Remove/Move/Duplicate/Wrap/Unwrap/SetProperty/SetModifiers/SetEventHandler/Rename/AddPage/ApplyPatch), patches, history, `DocumentDiff` |
| Tooling | CLI (`validate`, `generate`, `migrate`, `diff`), desktop preview sample |
| Quality | Everything in §28 |

### 31.3 Out of scope (explicitly postponed)

Visual editor; project scaffolding/Gradle generation; wave 2/3 components; `LazyColumn` iteration scopes; persistent/external state; two-way binding sugar; component-level events/overrides; `componentDefaults` in themes; Navigation Compose/Navigation 3 strategies; dynamic plugin loading; CBOR; incremental analysis; expression text parser; image/font resources; localization beyond simple string resources; iOS/Wasm *runtime* support (canary compile only); collaboration.

---

## 32. Implementation Phases

Dependency order intentionally differs from the naive sequence: analysis before codegen/runtime, a **walking skeleton** (Phase 4) validating the whole architecture on `Column + Text` before broadening, expressions/actions after the core mechanism is proven, editing API late (it depends only on model+schema).

Size legend: S ≤ 1 week, M 1–2 weeks, L 2–4 weeks (single senior developer).

### Phase 0 — Build foundation (S)
- **Objective:** repository, build logic, guardrails.
- **Modules:** root, `build-logic`, all module shells, `:engine:test-support` (skeleton), `:tools:architecture-tests`.
- **APIs:** `@EngineInternalApi`, convention plugins.
- **Tasks:** version catalog; `forge.kmp.library`/`forge.kmp.compose`/`forge.jvm.tool`; `explicitApi`; canary targets; `verifyModuleGraph`; Konsist baseline rules; CI workflows; `embedFixtures` task; `Golden` helper; ABI validation wiring.
- **Tests:** each module builds on all declared targets; graph check fails on a deliberately bad dependency (negative test).
- **Acceptance:** `./gradlew check` green on empty modules; canary jobs green.
- **Depends on:** none.

### Phase 1 — Domain model (M)
- **Objective:** immutable serializable record model.
- **Modules:** `:engine:model`.
- **APIs:** ids, `Value`, `TypeRef`, `PropertyValue`, `Expr`, `ActionSequence`, `Node`, `UiDocument`, `NodeTable`, `DocumentIndex`, `ReferenceIndex`, `IdGenerator`, DSL, canonical number helpers.
- **Tasks:** implement §5; validating constructors; `Decimal` canonical formatter (integer arithmetic); traversal utilities; ABI dump.
- **Tests:** id validation; canonical numbers table-driven on all targets; index correctness; property tests for index invariants; Konsist `@SerialName` rule.
- **Acceptance:** model compiles on Android, Desktop, iOS, wasm; zero non-allowed dependencies.
- **Depends on:** P0.

### Phase 2 — Schema (M) ‖ Phase 3 — Serialization (M) (parallelizable)
**Phase 2**
- **Objective:** spec types and registries.
- **Modules:** `:engine:schema`.
- **APIs:** `Registry`, `Schema`, `SchemaBuilder`, `ComponentSpec` family, `ModifierSpec`, `ActionSpec`, `FunctionSpec`, `TypeRegistry`, `ValueKind`s, `SchemaOverlay`, `SchemaContribution`, spec DSL.
- **Tasks:** §7–9 types; duplicate detection; deterministic ordering; typed property accessors; `PropertyRule`.
- **Tests:** builder/duplicates/order; `ValueKind` table tests; overlay cannot shadow static keys.
- **Acceptance:** a toy pack registers and is looked up; no dependency on analysis/runtime.
- **Depends on:** P1.

**Phase 3**
- **Objective:** stable persistence with migration framework.
- **Modules:** `:engine:serialization`.
- **APIs:** `DocumentCodec`, `JsonDocumentCodec`, `Envelope`, `Migration`, `MigrationChain`, `DocumentStorage`, `ForgeJson`.
- **Tasks:** canonical JSON writer; `NodeTableSerializer`; `PropertyValueSerializer`; envelope decode → migrate → decode; a synthetic `1→2` migration for testing; `nonWebMain` file storage.
- **Tests:** round-trip (property-based), byte-determinism, key sorting, unknown-preservation, future-version error, migration fixtures.
- **Acceptance:** golden JSON files identical on every target.
- **Depends on:** P1.

### Phase 4 — Analysis + Walking skeleton (L)
- **Objective:** structural/schema/reference validation and resolver; then the thinnest vertical slice through runtime and codegen.
- **Modules:** `:engine:analysis`, `:engine:builtins` (Column, Text only), `:engine:interpreter` (skeleton: `StateStore`, `EvalScope`), `:engine:runtime`, `:engine:builtins-compose`, `:engine:codegen`, `:integration:generated-compile`, `:tools:cli` (`generate`).
- **APIs:** `Analyzer`, `Diagnostic`, `ResolvedDocument/Node`, `UiRuntime`, `UiScreen`, `RendererRegistry`, `KotlinGenerator`, `Kt*` IR, `GeneratedFiles`.
- **Tasks:** passes 1, 3, 4, 7 (no expressions yet); `ResolvedNode` with default handling; Kotlin IR; printer (formatting rules, imports, aliasing); generic `ComposeCall` emitter; `RenderNode`, `RenderScope`, `PropertyReader`; `RuntimeCoverage`; conformance harness with 2 fixtures.
- **Tests:** diagnostic catalog tests for implemented codes; printer unit/golden tests; import-conflict tests; runtime semantics tests; **first conformance test** (Column+Text) including compile of generated code.
- **Acceptance:** Column/Text document validated, rendered and generated; generated code compiles and matches semantics/pixels; no `when(componentType)` anywhere (Konsist).
- **Depends on:** P1, P2, P3 (P3 only for CLI/fixtures).
- **Decision gate:** if the skeleton exposes a gap in `ResolvedNode`, fix here — this is the last cheap moment.

### Phase 5 — Component wave 1 and modifiers (M)
- **Objective:** enough components/modifiers to build real screens; scope machinery.
- **Modules:** `:engine:builtins`, `:engine:builtins-compose`, `:engine:analysis` (scope pass), `:engine:codegen` (Cases emit, positional rules).
- **APIs:** `ModifierSpec`/`ModifierApplier`, `ScopeId`, `PropertyRule`, enum specs, typed DSL sugar.
- **Tasks:** Row, Box, Spacer, Button, TextField (value/onValueChange present but read-only until P6/P7), Card; the modifier set from §31.2; enum runtime maps; `Cases` emit for Column/padding; scope validation (`modifier.scope_missing`).
- **Tests:** spec coverage; enum coverage; golden per component; conformance per component; scope negative tests.
- **Acceptance:** all wave-1 static UIs pass conformance.
- **Depends on:** P4.

### Phase 6 — Expressions and state (M–L)
- **Objective:** dynamic values.
- **Modules:** `:engine:model` (already contains AST), `:engine:analysis` (type checker), `:engine:interpreter` (evaluator), `:engine:builtins` (function specs/impls), `:engine:runtime` (`SnapshotStateStore`), `:engine:codegen` (expression/state emit, `StateStrategy`).
- **APIs:** `TypedExpr`, `Evaluator`, `FunctionSpec/Impl`, `StateDecl`, `StateStore`, `StateStrategy`.
- **Tasks:** typecheck rules (§10.4); precedence-aware emitter; page/app/component/derived state emit; `num.format` integer-math implementation on both sides; expression corpus harness.
- **Tests:** type-rule unit tests; corpus (interpreter and compiled generated); state golden tests; recomposition test (changing state updates text).
- **Acceptance:** `Text(text = expr)` and `TextField(value = state)` work in runtime and generated code with equal semantics.
- **Depends on:** P5.

### Phase 7 — Events, actions, navigation (M)
- **Objective:** interactive apps.
- **Modules:** `:engine:interpreter` (`ActionExecutor`, handlers), `:engine:schema` (`ActionSpec`), `:engine:builtins`, `:engine:runtime` (`BackStackNavigator`, environment), `:engine:codegen` (action emit, `SimpleBackStack`, `AppRoot`), `:engine:analysis` (action/nav passes).
- **APIs:** `ActionHandler`, `ActionEnv`, `Navigator`, `NavigationStrategy`, `HostFunctions`.
- **Tasks:** MVP actions; host functions/`AppHost`; `flow.if`; args → route params; suspend handling.
- **Tests:** handler tests with fake env; nav conformance with clicks; host function test; negative diagnostics.
- **Acceptance:** the §30 example works end to end, including click navigation in both runtime and generated code.
- **Depends on:** P6.

### Phase 8 — Reusable components, themes, resources (M–L)
- **Objective:** composition and design system.
- **Modules:** `:engine:model` (records exist), `:engine:schema` (overlay), `:engine:analysis` (overlay analysis, token resolution), `:engine:runtime` (`DeclInstanceRenderer`, `ThemeHost`, `ResourceProvider`), `:engine:codegen` (component files, theme files, resource emit), `:engine:builtins`.
- **APIs:** `ComponentDecl` instancing, `SlotOutlet`, `ThemeDecl`, `ResourceDecl`.
- **Tasks:** overlay synthesis; params/slots; theme codegen (`AppTheme.kt`, `AppTokens`); string resources; cycle detection between component decls (`struct.component_cycle`).
- **Tests:** component golden and conformance; light/dark conformance; cycle negative test.
- **Acceptance:** reusable component with param + slot renders and generates equivalently; theme tokens resolved identically.
- **Depends on:** P7.

### Phase 9 — Editing API (M)
- **Objective:** editor-agnostic mutation layer.
- **Modules:** `:engine:editing`.
- **APIs:** `DocumentController`, `Command`, `Patch/PatchOp`, `History`, `DocumentDiff`, `IdRemapper`, `NodeTemplate`, `DropTargets`.
- **Tasks:** command planners with schema policy checks; patch apply/inverse; history & transactions; diff; clipboard remap; `SpecMigrationPass` (component-level migrations).
- **Tests:** patch/inverse property tests; command legality tests; history merge tests; diff round-trip (`apply(diff(a,b), a) == b`).
- **Acceptance:** scripted "editing sessions" run headless and yield deterministic documents and analyses.
- **Depends on:** P1, P2 (independent of P4–P8; may run in parallel after P2).

### Phase 10 — Tooling, hardening, MVP release (M)
- **Objective:** prove editor independence; performance and determinism; docs.
- **Modules:** `:tools:cli`, `:samples:desktop-preview`, benchmarks.
- **Tasks:** CLI commands; desktop preview that loads JSON and hot-reloads on file change (via `DocumentStorage`); benchmark suites; permutation/determinism suite; fuzz compile job; ThirdPartyComponentTest; docs (`docs/`: getting-started, plugin guide, format spec).
- **Tests:** everything in §28; benchmark baselines recorded.
- **Acceptance:** Definition of Done (§36) fully satisfied.
- **Depends on:** P0–P9.

### Post-MVP roadmap (dependency order, not scheduled)

| Phase | Content |
|---|---|
| 11 | Component wave 2 (incl. iteration scopes, `LazyColumn/Row`, image/font resources) |
| 12 | Component wave 3 (Scaffold, Dialog, BottomSheet, navigation components), `componentDefaults` |
| 13 | Incremental analysis + expression compilation; expression text parser/printer |
| 14 | `:engine:scaffold` — project scaffolding (`ProjectSpec` → `settings.gradle.kts`, `composeApp/`, `shared/`, `libs.versions.toml`), separate from UI codegen |
| 15 | Alternative strategies: Navigation Compose, Navigation 3, ViewModel state; persistent/external state |
| 16 | Plugin manifest/discovery, third-party publishing guide |
| 17 | iOS/wasm *runtime* support |
| 18 | Visual editor (separate repository/modules consuming `:editing`, `:runtime`, `:analysis`, `:codegen`) |

---

## 33. File-Level Implementation Plan

Base package `dev.rotalex.lutter.<module>`. "Pub" = public API (ABI-tracked), "Int" = `internal`, "Exp" = public but `@EngineInternalApi`.

### 33.1 `:engine:model` — `engine/model/src/commonMain/kotlin/dev/forge/engine/model/`

| File | Responsibility | Key types | Deps | Vis |
|---|---|---|---|---|
| `ids/Ids.kt` | Typed identifiers, syntax validation | `NodeId`, `PageId`, `ComponentDeclId`, `StateId`, `ThemeId`, `ResourceId`, `DataModelId`, `TypeId`, `ParamName`, `PropertyKey`, `SlotName`, `EventKey`, `BranchName`, `ComponentType`, `ModifierType`, `ActionId`, `FunctionId`, `PluginId` | — | Pub |
| `ids/IdSyntax.kt` | Shared regexes/checks | `IdSyntax` | Ids | Int |
| `ids/IdGenerator.kt` | Id generation interface + sequential/random impls | `IdGenerator`, `SequentialIdGenerator`, `RandomIdGenerator(random)` | Ids | Pub |
| `value/Decimal.kt` | Canonical decimal formatting/parsing (integer arithmetic) | `Decimal`, `CanonicalFloat`, `CanonicalDouble` serializers | — | Int/Pub serializers |
| `value/ColorArgb.kt` | Color literal | `ColorArgb` (value class, parse/format `#AARRGGBB`) | — | Pub |
| `value/Value.kt` | Closed literal union + factories (canonicalizing) | `Value`, `Value.dp()` etc. | Decimal | Pub |
| `value/TypeRef.kt` | Type references | `TypeRef`, `RefKind`, `TokenKind`, `ResourceKind` | Ids | Pub |
| `value/PropertyValue.kt` | Const/Computed wrapper | `PropertyValue` | Value, Expr | Pub |
| `value/PropertyValueSerializer.kt` | Compact encoding (collapse `const`) | serializer | PropertyValue | Int |
| `expr/Expr.kt` | Expression AST | `Expr`, `RefTarget`, `UnaryOp`, `BinaryOp` | Value | Pub |
| `expr/TypedExpr.kt` | Typed expression + checker types | `TypedExpr`, `ExprType` | Expr, TypeRef | Pub |
| `action/ActionSequence.kt` | Action data | `ActionSequence`, `ActionStep` | PropertyValue | Pub |
| `doc/Node.kt` | Node + modifier record | `Node`, `ModifierEntry` | Ids, PropertyValue, ActionSequence | Pub |
| `doc/NodeTable.kt` | Normalized persistent table | `NodeTable`, `NodeTableSerializer` (public) | Node | Pub |
| `doc/Page.kt` | Page & params | `Page`, `ParamDecl` | Ids, TypeRef, StateDecl | Pub |
| `doc/ComponentDecl.kt` | Reusable components | `ComponentDecl`, `SlotDecl` | Ids | Pub |
| `doc/StateDecl.kt` | State | `StateDecl`, `Persistence` | Expr, TypeRef | Pub |
| `doc/DataModelDecl.kt` | Object types in document | `DataModelDecl`, `FieldDecl` | TypeRef | Pub |
| `doc/HostFunctionDecl.kt` | Host function declaration | `HostFunctionDecl` | | Pub |
| `doc/ThemeDecl.kt` | Theme records | `ThemeDecl`, `ThemeBase`, `ColorSpec`, `TextStyleSpec`, `ShapeSpec`, `ColorRole`, `TextRole`, `ShapeRole`, `TokenName` | Ids, Value | Pub |
| `doc/ResourceDecl.kt` | Resource records | `ResourceDecl`, `ResourceVariant`, `ResourceSource`, `Qualifier` | Ids, TypeRef (`ResourceKind`) | Pub |
| `doc/AppSpec.kt` | App-level info | `AppSpec`, `NavigationSpec` | Ids | Pub |
| `doc/DocumentMeta.kt` | Meta | `DocumentMeta`, `PluginRequirement` | Ids | Pub |
| `doc/UiDocument.kt` | Root record | `UiDocument` | all doc | Pub |
| `doc/SchemaVersion.kt` | Current version constants | `CURRENT_SCHEMA_VERSION`, `FORMAT_VERSION` | | Pub |
| `index/DocumentIndex.kt` | Parent/owner/path/descendants | `DocumentIndex`, `ParentRef`, `NodeOwner` | UiDocument | Pub |
| `index/ReferenceIndex.kt` | Reverse references | `ReferenceIndex` | UiDocument | Pub |
| `traversal/NodeTraversal.kt` | DFS/BFS helpers, subtree extraction | functions | UiDocument | Pub |
| `dsl/DocumentBuilder.kt` | Generic builder DSL | `buildDocument`, `NodeScope`, `PageScope` | ids | Pub |
| `annotations/EngineInternalApi.kt` | Opt-in marker | `@EngineInternalApi` | | Pub |

Tests (`commonTest`): `IdsTest`, `DecimalTest`, `ValueFactoryTest`, `NodeTableTest`, `DocumentIndexTest` (+ property-based), `TraversalTest`, `DslTest`.

**Dependencies.** `:engine:model` has one non-project dependency beyond `kotlinx-serialization`: `org.jetbrains.kotlinx:kotlinx-collections-immutable`, **0.5.2**, which `NodeTable`'s backing map needs (§5.5, ADR-007). §23.3:1727 already permits it, and nothing else does. It is declared `implementation`, not `api`, because the persistent map is an internal detail of `NodeTable` and appears in no public signature (§5.5's own note). The version comes from `gradle/libs.versions.toml` — the shared `rootLibs` catalog (1.2.7) carries no such entry, so that file's own "coordinates that catalog does not have yet" rule is what places it there.

**The 0.5.x API note, for whoever writes `NodeTable` next.** 0.5 renamed every copy-returning method on `PersistentCollection`/`PersistentList`/`PersistentMap` to the participial form KEEP-0459 requires, and kept the old spellings as `@Deprecated(WARNING)` with `ReplaceWith`: `put` → `putting`, `remove` → `removing`, `putAll` → `puttingAll`, `add` → `adding`, `set` → `replacingAt`, `removeAt` → `removingAt`, `clear` → `cleared`. The imperative names become a compile error in 0.6.0 and are removed in 0.7.0, so **a missed rename is a warning today, not a failure** — which is exactly the case where it is worth writing the new names the first time. `NodeTable.with`/`without` are written directly on this API (§5.5), so nothing else in the module will notice the difference. The zero-length map comes from `persistentHashMapOf()`/`persistentMapOf()`: the `immutableMapOf` spelling is the deprecated alias of the latter, and the two differ in what their iteration order is.

### 33.2 `:engine:schema` — `dev/forge/engine/schema/`

| File | Responsibility | Key types | Vis |
|---|---|---|---|
| `registry/Registry.kt` | Read-only registry + builder | `Registry`, `RegistryBuilder`, `DuplicateKeyException` | Pub |
| `Schema.kt` | Immutable schema | `Schema`, `SchemaView` | Pub |
| `SchemaBuilder.kt` | Build DSL; duplicate detection | `SchemaBuilder`, `SchemaBuildException` | Pub |
| `plugin/SchemaContribution.kt` | Plugin facet | `SchemaContribution`, `PluginDescriptor` | Pub |
| `component/ComponentSpec.kt` | Component spec | `ComponentSpec`, `ComponentMetadata`, `Category`, `PlatformTag`, `SpecOrigin`, `ModifierPolicy` | Pub |
| `component/PropertySpec.kt` | Typed property spec | `PropertySpec<T>`, `EditorHints`, `PropertyRule` | Pub |
| `component/SlotSpec.kt` | Slots and scopes | `SlotSpec`, `Cardinality`, `ChildFilter`, `ScopeId`, `IterationSpec` | Pub |
| `component/EventSpec.kt` | Events | `EventSpec`, `EventArgSpec` | Pub |
| `component/CodegenBinding.kt` | Declarative Kotlin mapping | `CodegenBinding`, `ParamBinding`, `ValueEmit`, `EmitCase`, `SlotBinding`, `EventBinding`, `KotlinSymbol` | Pub |
| `component/ComponentSpecDsl.kt` | Authoring DSL | `componentSpec {}` | Pub |
| `modifier/ModifierSpec.kt` | Modifier spec | `ModifierSpec`, `ModifierEmit` | Pub |
| `action/ActionSpec.kt` | Action spec | `ActionSpec`, `BranchSpec`, `ActionEmit` | Pub |
| `function/FunctionSpec.kt` | Function signature/emit | `FunctionSpec`, `ParamSig`, `TypeSig`, `FunctionEmit` | Pub |
| `types/TypeRegistry.kt` | Data-defined types | `TypeRegistry`, `EnumTypeSpec`, `EnumEntrySpec`, `ObjectTypeSpec`, `IconSet` | Pub |
| `kind/ValueKind.kt` | Kind definitions, literal emission contract | `ValueKind<T>`, `ValueKinds` | Pub |
| `overlay/SchemaOverlay.kt` | Document components → specs | `SchemaOverlay` | Pub |
| `migration/SpecMigration.kt` | Component-level migrations | `SpecMigration`, `SpecMigrationRegistry` | Pub |

### 33.3 `:engine:serialization` — `dev/forge/engine/serialization/`

| File | Responsibility | Vis |
|---|---|---|
| `ForgeJson.kt` | Single `Json` configuration | Int |
| `Envelope.kt` | Envelope record + read/write | Pub |
| `CanonicalJsonWriter.kt` | Sorted keys, formatting, numbers | Int |
| ~~`NodeTableSerializer.kt`~~ | *moved to `:engine:model` `doc/NodeTable.kt`, public: §5.5's `@Serializable(with = …)` names it, §23.3:1727 forbids the model from depending on this module, and an internal serializer on a public type throws on Wasm* | — |
| `DocumentCodec.kt` | `DocumentCodec`, `DecodeOptions`, `DecodeResult` | Pub |
| `JsonDocumentCodec.kt` | JSON implementation | Pub |
| `migration/Migration.kt` | `Migration`, `MigrationChain`, `MigrationResult` | Pub |
| `migration/Migrations.kt` | Registered chain (initially empty; test-only synthetic in tests) | Pub |
| `storage/DocumentStorage.kt` | Interface | Pub |
| `nonWebMain/.../FileDocumentStorage.kt` | kotlinx-io implementation | Pub |

### 33.4 `:engine:interpreter` — `dev/forge/engine/interpreter/`

| File | Responsibility | Vis |
|---|---|---|
| `eval/Evaluator.kt` | `TypedExpr` interpreter | Pub |
| `eval/EvalScope.kt` | `EvalScope`, `MapEvalScope` | Pub |
| `eval/FunctionImpl.kt` | `FunctionImpl`, `FunctionImpls` | Pub |
| `eval/Display.kt` | Canonical `toDisplayString` | Pub |
| `state/StateStore.kt` | `StateStore`, `StateWriter`, `PersistentStore` | Pub |
| `action/ActionExecutor.kt` | Sequence runner | Pub |
| `action/ActionHandler.kt` | `ActionHandler`, `ActionEnv`, `ActionOutcome` | Pub |
| `action/IntrinsicHandlers.kt` | state.set, flow.if, nav.*, host.call | Pub |
| `env/Navigator.kt`, `env/HostFunctions.kt`, `env/DialogHost.kt` | Environment interfaces | Pub |
| `Implementations.kt` | Registries of impls | Pub |
| `contribution/InterpreterContribution.kt` | Plugin facet | Pub |

### 33.5 `:engine:analysis` — `dev/forge/engine/analysis/`

| File | Responsibility | Vis |
|---|---|---|
| `Analyzer.kt` | Orchestration, options | Pub |
| `AnalysisResult.kt` | Result | Pub |
| `diagnostic/Diagnostic.kt`, `DiagnosticCode.kt`, `DiagnosticCodes.kt`, `Severity.kt` | Diagnostics | Pub |
| `diagnostic/DiagnosticSorter.kt` | Deterministic ordering | Int |
| `pass/AnalysisPass.kt` | Pass interface, context | Int |
| `pass/StructuralPass.kt` | Invariants | Int |
| `pass/NamingPass.kt` | Identifiers | Int |
| `pass/SchemaPass.kt` | Components/props/slots/modifiers | Int |
| `pass/ReferencePass.kt` | Reference checks | Int |
| `pass/ExpressionPass.kt` | Type-check driver | Int |
| `pass/ActionPass.kt` | Action/nav checks | Int |
| `pass/ResolutionPass.kt` | Build resolved tree | Int |
| `pass/FeasibilityPass.kt` | Codegen feasibility | Int |
| `typing/TypeChecker.kt`, `typing/Assignability.kt` | Expression typing | Int |
| `scope/ScopeAnalysis.kt` | Provided/required scopes | Int |
| `resolved/ResolvedDocument.kt`, `ResolvedNode.kt`, `ResolvedProp.kt`, `ResolvedModifier.kt`, `ResolvedActions.kt`, `ResolvedTheme.kt`, `ResolvedPage.kt` | Derived typed model | Pub |

### 33.6 `:engine:editing` — `dev/forge/engine/editing/`

| File | Responsibility | Vis |
|---|---|---|
| `DocumentController.kt`, `DocumentState.kt`, `DocumentChange.kt` | Controller & state | Pub |
| `command/Command.kt`, `CommandResult.kt`, `CommandPlanner.kt` | Intent → patch | Pub |
| `command/planners/*.kt` | One file per command family | Int |
| `patch/Patch.kt`, `PatchOp.kt`, `PatchApplier.kt`, `PatchInverter.kt` | Mechanism | Pub |
| `history/History.kt`, `Transaction.kt` | Undo/redo | Pub |
| `diff/DocumentDiff.kt` | Compute patch between docs | Pub |
| `template/NodeTemplate.kt`, `IdRemapper.kt` | Clipboard/palette subtrees | Pub |
| `policy/DropTargets.kt` | Legal drop targets from specs | Pub |
| `migration/SpecMigrationPass.kt` | Apply component migrations as a patch | Pub |

### 33.7 `:engine:codegen` — `dev/forge/engine/codegen/`

| File | Responsibility | Vis |
|---|---|---|
| `KotlinGenerator.kt`, `CodegenOptions.kt`, `CodegenResult.kt`, `GeneratedFiles.kt` | Facade & results | Pub |
| `ir/KtExpr.kt`, `KtStmt.kt`, `KtDeclaration.kt`, `KtFile.kt`, `KtSymbol.kt` | Kotlin IR | Exp |
| `ir/KtBuilders.kt` | Builder helpers | Exp |
| `print/KotlinPrinter.kt` | Deterministic printer | Int |
| `print/FormattingRules.kt` | Wrapping/trailing comma rules | Int |
| `print/ImportSet.kt` | Import collection/alias resolution | Int |
| `print/LiteralPrinter.kt` | String/number escaping | Int |
| `naming/NamingPolicy.kt`, `KotlinKeywords.kt` | Identifiers | Int |
| `plan/GenPlan.kt`, `GeneratedLayout.kt` | File planning | Int/Pub |
| `emit/NodeEmitter.kt` | Node → `KtExpr` via bindings | Int |
| `emit/ComposeCallEmitter.kt`, `ModifierEmitter.kt` | Binding interpreters | Int |
| `emit/ExprEmitter.kt`, `ActionEmitter.kt`, `ValueEmitter.kt` | Expression/action/value emission | Int |
| `emit/StateEmitter.kt` + `strategy/StateStrategy.kt` | State | Pub (interface) |
| `emit/ScreenEmitter.kt`, `ComponentEmitter.kt`, `ThemeEmitter.kt`, `ModelEmitter.kt`, `AppRootEmitter.kt`, `ResourceEmitter.kt` | File-level emitters | Int |
| `strategy/NavigationStrategy.kt`, `SimpleBackStack.kt` | Navigation | Pub |
| `ext/CodegenExtensions.kt` | Overrides for custom bindings | Exp |
| `coverage/CodegenCoverage.kt` | Binding sanity check | Pub |

### 33.8 `:engine:runtime` — `dev/forge/engine/runtime/`

| File | Responsibility | Vis |
|---|---|---|
| `UiRuntime.kt`, `UiScreen.kt`, `RuntimeEnvironment.kt` | Entry points | Pub |
| `registry/RendererRegistry.kt`, `ModifierApplierRegistry.kt`, `RuntimeCoverage.kt` | Runtime registries | Pub |
| `render/ComponentRenderer.kt`, `RenderScope.kt`, `RenderNode.kt`, `PropertyReader.kt`, `ScopeBag.kt`, `RenderErrorBoundary.kt` | Rendering core | Pub/Int |
| `render/DeclInstanceRenderer.kt`, `SlotOutletRenderer.kt` | Document components | Int |
| `modifier/ModifierApplier.kt`, `ModifierFold.kt` | Modifier application | Pub/Int |
| `state/SnapshotStateStore.kt`, `PageStateHost.kt` | Compose-backed state | Pub |
| `nav/BackStackNavigator.kt` | Default navigator | Pub |
| `theme/ThemeHost.kt`, `TokenResolver.kt` | Theme | Pub/Int |
| `resource/ResourceProvider.kt` | Interface | Pub |
| `hooks/RenderHooks.kt` | Editor hooks | Pub |
| `marshal/ComposeMarshal.kt` | `ColorArgb`→`Color`, `Dp`… | Pub |
| `platform/PlatformBackHandler.kt` (`expect`/`actual`) | Back handling | Int |
| `contribution/RuntimeContribution.kt` | Plugin facet | Pub |

### 33.9 `:engine:builtins` and `:engine:builtins-compose`

`builtins/` (`dev/forge/engine/builtins/`)

| File | Responsibility |
|---|---|
| `CorePack.kt`, `Material3Pack.kt` | `SchemaContribution` + `InterpreterContribution` + `CodegenContribution` (as needed) |
| `core/ColumnSpec.kt`, `RowSpec.kt`, `BoxSpec.kt`, `SpacerSpec.kt`, `SlotOutletSpec.kt` | Specs with codegen bindings |
| `m3/TextSpec.kt`, `ButtonSpec.kt`, `TextFieldSpec.kt`, `CardSpec.kt` | Specs |
| `modifiers/LayoutModifiers.kt`, `DecorationModifiers.kt`, `InteractionModifiers.kt` | Modifier specs |
| `enums/Alignments.kt`, `Arrangements.kt`, `Shapes.kt`, `TextAligns.kt` | `EnumTypeSpec`s with Kotlin symbols |
| `actions/NavActions.kt`, `StateActions.kt`, `FlowActions.kt`, `HostActions.kt`, `UiActions.kt` | Action specs + pure handlers |
| `functions/ListFunctions.kt`, `StringFunctions.kt`, `NumberFunctions.kt`, `CoreFunctions.kt` | Function specs + impls |
| `dsl/TypedDsl.kt` | Typed builder sugar over model DSL |

`builtins-compose/` (`dev/forge/engine/builtins/compose/`)

| File | Responsibility |
|---|---|
| `BuiltinsRuntime.kt` | `RuntimeContribution` registering all renderers/appliers |
| `renderers/ColumnRenderer.kt`, `RowRenderer.kt`, `BoxRenderer.kt`, `SpacerRenderer.kt`, `TextRenderer.kt`, `ButtonRenderer.kt`, `TextFieldRenderer.kt`, `CardRenderer.kt` | One file per component |
| `modifiers/*Appliers.kt` | Modifier appliers |
| `enums/EnumMaps.kt` | Enum entry → Compose value (coverage-tested) |

### 33.10 Remaining modules

| Module | Files |
|---|---|
| `:engine:test-support` | `Golden.kt`, `FixtureLoader.kt`, `TestSchemas.kt` (toy pack), `SampleDocuments.kt`, `FakeActionEnv.kt`, `DocumentGenerator.kt` (property-based Arb), `Assertions.kt` |
| `:tools:cli` | `Main.kt`, `commands/ValidateCommand.kt`, `GenerateCommand.kt`, `MigrateCommand.kt`, `DiffCommand.kt`, `FileSink.kt`, `Output.kt` |
| `:tools:architecture-tests` | `ModuleGraphTest.kt`, `NoComposeInDomainTest.kt`, `SerialNameRuleTest.kt`, `NoComponentWhenTest.kt`, `NoRawMapAnyTest.kt` |
| `:integration:generated-compile` | `build.gradle.kts` (generate task), `src/commonTest/.../ConformanceTest.kt`, `ExpressionCorpusTest.kt`, `SemanticsDump.kt`, `fixtures/*.json`, `src/desktopTest/.../PixelCompareTest.kt` |
| `:samples:desktop-preview` | `Main.kt` (window, file watcher, `UiScreen` host, diagnostics panel — a viewer, not an editor) |

---

## 34. ADRs

### ADR-001 Normalized node table instead of nested tree
- **Decision:** One document-wide `Map<NodeId, Node>` with ordered child-id lists per slot; pages/components hold root ids.
- **Why:** O(1) lookup, cheap moves, patch/diff friendliness, stable references, structural sharing.
- **Alternatives:** nested tree; per-page tables; adjacency list with parent pointers.
- **Trade-offs:** invariants (single parent, acyclic) must be validated; parent lookup requires `DocumentIndex`.
- **Consequences:** structural pass is blocking; all mutations go through invariant-preserving patch ops; JSON has flat nodes sorted by id.

### ADR-002 Registry architecture: specs as data, five schema registries, two runtime registries
- **Decision:** Registries only for components, modifiers, actions, functions, types (schema) and renderers/modifier appliers (runtime).
- **Why:** These are the real extension points; other candidates duplicate information or belong to documents.
- **Alternatives:** registry for everything; big `when`; DI/service locator.
- **Trade-offs:** slightly more explicit wiring; some duplication of keys across schema/runtime (guarded by coverage checks).
- **Consequences:** plugins are pairs of contributions; no global state.

### ADR-003 Split component definition by capability
- **Decision:** `ComponentSpec` (data) + `ComponentRenderer` (Compose) + declarative `CodegenBinding` (data) instead of one `ComponentDefinition` with `createRuntime()/generateCode()`.
- **Why:** Keep schema Compose-free and codegen platform-neutral; single resolver ensures parity.
- **Alternatives:** unified definition; annotation processing that derives both.
- **Trade-offs:** renderer and binding can diverge → coverage checks and conformance tests.
- **Consequences:** Compose is confined to two modules; codegen runs anywhere. Post-MVP option: generate renderers from bindings via KSP.

### ADR-004 Custom Kotlin IR + printer instead of KotlinPoet or templates
- **Decision:** Small `KtExpr/KtStmt/KtDeclaration` IR with a deterministic printer in `commonMain`.
- **Why:** KotlinPoet is JVM-only (verify at kickoff); templates cannot guarantee validity/imports; the needed Kotlin subset is small.
- **Alternatives:** KotlinPoet (JVM), KotlinPoet behind an `expect` layer, text templates, kotlin-compiler PSI.
- **Trade-offs:** we own formatting and import logic (~1–2k LOC); mitigated by golden tests.
- **Consequences:** codegen usable from wasm/CLI/server; formatting rules are versioned by goldens. A KotlinPoet backend could still be added for JVM-only consumers.

### ADR-005 Serialization annotations in the model; formats in `:serialization`
- **Decision:** `@Serializable/@SerialName` on model types; codec, envelope, canonicalization, migrations in `:engine:serialization`.
- **Why:** Avoids a duplicated DTO layer (a second representation) while keeping the model format-agnostic.
- **Alternatives:** DTO surrogates; hand-written codecs; custom binary.
- **Trade-offs:** model depends on kotlinx-serialization annotations; `@SerialName` names become a persistence contract.
- **Consequences:** Konsist rule mandates `@SerialName`; renaming a class is safe, renaming a serial name is a migration.

### ADR-006 JSON canonical primary; CBOR deferred; migrations on JSON
- **Decision:** Canonical JSON only; migrations work on `JsonElement`.
- **Why:** Human-readable, Git-friendly, debuggable; migrations need a schema-free tree.
- **Alternatives:** CBOR, custom binary, protobuf.
- **Trade-offs:** larger files, slower parsing.
- **Consequences:** binary encodings are caches of the *current* schema only; any change to persisted shape needs a migration.

### ADR-007 Immutable model with persistent collections
- **Decision:** All model types immutable; `NodeTable` on persistent map; no in-place mutation APIs.
- **Why:** Safe sharing across threads/Compose; cheap snapshots; trivially testable; structural sharing for undo and analysis caching.
- **Alternatives:** mutable model with change events; Compose snapshot state in model.
- **Trade-offs:** allocation on edits; API returns new documents.
- **Consequences:** editing layer owns the mutation story; model has no Compose dependency.

### ADR-008 Minimal typed expression language with open function registry
- **Decision:** Closed AST and operator set; extensible functions; typed with no implicit conversion; no `/` and `%` in MVP.
- **Why:** Semantic parity between interpreter and generated Kotlin; small surface to validate.
- **Alternatives:** embed Kotlin script; JS/CEL/JSONata; free-form strings.
- **Trade-offs:** limited expressiveness; users use host functions for complex logic.
- **Consequences:** the expression corpus guarantees parity; extending operators needs parity tests.

### ADR-009 Actions as data; two-way binding not modeled
- **Decision:** `ActionSequence`/`ActionStep` with registry-defined actions; intrinsic actions for state/nav/flow/host; no `bind` sugar.
- **Why:** Executable at runtime and translatable to Kotlin; explicit data flow; small interpreter.
- **Alternatives:** lambdas in model (unserializable); scripting; two-way bindings.
- **Trade-offs:** slightly verbose for text fields (value ref + set action); editors can hide it.
- **Consequences:** `ActionEmit` templates for plugin actions; intrinsic emitters in the engine.

### ADR-010 Static plugin composition in MVP
- **Decision:** Plugins are installed in code as contribution facets; no dynamic loading; document records required plugins.
- **Why:** `ServiceLoader`/class loading is not portable across KMP; MVP does not need it.
- **Alternatives:** JVM `ServiceLoader`; OSGi-like; scripting-based plugins.
- **Trade-offs:** third parties recompile the host.
- **Consequences:** builtins are implemented as plugins (dogfooding); dynamic discovery can layer on top.

### ADR-011 Generated code is one-way and generate-only
- **Decision:** No parsing generated Kotlin back; users extend via host functions, slots, and reusable components.
- **Why:** Round-tripping requires a Kotlin parser and destroys determinism.
- **Alternatives:** partial regeneration with markers; protected regions.
- **Trade-offs:** hand-edits to generated files are lost on regeneration.
- **Consequences:** generated directory is treated as build output; header says "Do not edit."

### ADR-012 State architecture: framework-agnostic model, snapshot-state default emission
- **Decision:** `StateDecl` with scopes; `StateStrategy` chooses emission; default is Compose snapshot state with per-page holder classes.
- **Why:** Avoids coupling to ViewModel/Redux; generated code is idiomatic and dependency-free.
- **Alternatives:** ViewModel-first, MVI, Molecule.
- **Trade-offs:** persistence/external state postponed.
- **Consequences:** future strategies plug in without touching the model or analysis.

### ADR-013 Patch-based undo/redo
- **Decision:** Commands plan invertible `Patch`es; history stores patches.
- **Why:** Small, precise, diffable, reusable for sync and tests.
- **Alternatives:** snapshots; event sourcing; OT.
- **Trade-offs:** each `PatchOp` needs correct inverse (property-tested).
- **Consequences:** future collaboration can build on the same ops.

### ADR-014 Editor/runtime separation
- **Decision:** The editor consumes `:editing`, `:analysis`, `:runtime`, `:codegen`; the engine has no editor types except `RenderHooks`.
- **Why:** Engine usable by CLI/tests/server; editor cannot own or fork the model.
- **Alternatives:** editor-owned document; runtime with built-in editing overlays.
- **Trade-offs:** editor must implement selection/overlay UX itself using hooks.
- **Consequences:** `:runtime` stays small; controller is the single mutation gateway.

### ADR-015 Resolved model as the single input to runtime and codegen
- **Decision:** `Analyzer` produces `ResolvedDocument`; both backends read only that.
- **Why:** One place for defaults, scopes, tokens and typing.
- **Alternatives:** each backend walks the raw document.
- **Trade-offs:** an additional derived structure (memory/time).
- **Consequences:** parity is structural, not accidental; incremental analysis later benefits both.

### ADR-016 Canonical numerics and schema-free decoding
- **Decision:** ≤4 fractional digits, integer-arithmetic formatting; self-describing `Value` tags; decoding never consults the schema.
- **Why:** Cross-platform determinism; lossless round trip for unknown plugin content.
- **Alternatives:** raw doubles; schema-directed compact encoding.
- **Trade-offs:** slightly more verbose JSON; precision limit (4 decimals) for UI values.
- **Consequences:** `Value` factories canonicalize; Konsist enforces factory use.

### ADR-017 Reusable components by composition only
- **Decision:** `ComponentDecl` with params/slots; no inheritance and no structural overrides.
- **Why:** Matches Compose functions; predictable codegen; avoids override-diff complexity.
- **Alternatives:** override maps keyed by inner node ids; inheritance.
- **Trade-offs:** less flexible than design-tool variants.
- **Consequences:** variants are expressed as params (enum/boolean) and slots.

---

## 35. Anti-Patterns

The implementation must **not** become any of the following (each has an automated or review-level guard):

| ❌ Anti-pattern | Guard |
|---|---|
| Giant `when(componentType)` / hard-coded component lists | Konsist `NoComponentWhenTest`; registries only |
| UI model containing Compose lambdas or `@Composable` | No Compose in model; Konsist |
| Domain modules depending on Compose | Module graph + Konsist |
| Editor owning the document | Only `DocumentController` mutates; editor holds ids |
| String-concatenated Kotlin generation | Only `Kt*` IR + printer; `LiteralPrinter` for escapes; review rule |
| `Map<String, Any>` as domain data | Konsist `NoRawMapAnyTest` |
| Platform APIs (`java.*`, `android.*`) in `commonMain` | Konsist + canary targets |
| Duplicated runtime and codegen representations | Both consume `ResolvedDocument` only |
| Hundreds of Gradle modules | Module list is a reviewed table (§22) |
| Premature plugin marketplace/dynamic loading | ADR-010 |
| Building a full IDE | Non-goals (§3) |
| Global mutable registries / singletons / service locators | Immutable `Schema`; explicit constructor injection; Konsist `object` with `var` rule |
| Resolver logic re-implemented inside a renderer/emitter (defaults, scopes) | Code review checklist; renderers read `PropertyReader` only |
| Serialization by class name / default polymorphic discriminators | Mandatory `@SerialName` |
| Locale-/platform-dependent formatting (`toString`, `format`, default sort) | `Decimal`, explicit comparators, Konsist ban on `Float.toString` in engine code paths |
| Hidden global state in generators (timestamps, random) | `HeaderPolicy.Minimal`, injected `IdGenerator`, determinism tests |
| Auto-fixing invalid documents silently | Diagnostics with suggestions/patches, never silent mutation |
| Swallowing unknown data | Preserve + diagnose (§19.4) |
| One mega "Engine" facade class owning everything | Small composable services (`Analyzer`, `KotlinGenerator`, `UiRuntime`, `DocumentController`) |
| Expression language creep (loops, lambdas, user functions) | ADR-008; new constructs require parity tests and an ADR |
| Persisting derived data (`ResolvedDocument`, indexes) | `Resolved*` and indexes are not `@Serializable` (Konsist) |
| Runtime-only features with no codegen equivalent (and vice-versa) | Coverage checks; feature checklist includes both backends |

---

## 36. Definition of Done

The MVP is complete only when **all** items are demonstrably true (CI evidence in parentheses):

### 36.1 Functional

- [ ] A document can be created programmatically (DSL) and equals the JSON-decoded document for the same content (`DslEqualsJsonTest`).
- [ ] Components can be registered via `SchemaContribution` + `RuntimeContribution`; a component defined **only in a test module** passes validation, rendering, codegen and serialization without any change to engine modules (`ThirdPartyComponentTest`).
- [ ] Properties are represented in a typed manner (`Value`/`TypeRef`/`PropertySpec`); Konsist finds no `Map<String, Any>` in engine code (`NoRawMapAnyTest`).
- [ ] The document serializes to canonical JSON and deserializes back to an equal document; `encode(decode(encode(d))) == encode(d)` for ≥ 1,000 generated documents (`RoundTripPropertyTest`).
- [ ] Schema migrations work: fixtures `v1`…`vCURRENT` in `resources/migrations` migrate to the golden current form; future versions are rejected with `UnsupportedFutureVersion` (`MigrationTests`).
- [ ] The document validates through all eight passes; every code in §17.3 has a positive and a negative test; diagnostics are sorted deterministically (`DiagnosticCatalogTest`).
- [ ] The runtime renders the §30 example and all wave-1 fixtures with Compose (`RuntimeSemanticsTest` on Desktop and Android unit runner).
- [ ] The same documents generate Kotlin/Compose code (`CodegenGoldenTest` ≥ 25 fixtures incl. multi-file navigation, state, expressions, custom component, import alias conflict).

### 36.2 Determinism and correctness

- [ ] Generated code is byte-identical across runs, across operating systems, and across engine targets (Desktop, Android, wasm and iOS canaries) for all golden fixtures (`DeterminismTest`, canary jobs).
- [ ] Permutation tests (different construction orders) yield identical bytes/diagnostics/generated files (`PermutationTest`).
- [ ] Generated code **compiles** for every conformance fixture as a real Gradle module compile, and the nightly fuzz batch of ≥ 200 random valid documents compiles with zero errors (`:integration:generated-compile:desktopTest`, nightly job).
- [ ] Runtime and generated code have the same semantics: semantics dumps equal for all conformance fixtures, interaction scripts produce equal dumps, and Desktop pixel comparison has zero difference (`ConformanceTest`, `PixelCompareTest`).
- [ ] The expression corpus (≥ 150 cases) passes in the interpreter and in compiled generated code (`ExpressionCorpusTest`).
- [ ] Coverage checks pass: every `ComponentSpec` has a renderer and a valid binding; every enum entry has a runtime mapping and Kotlin symbol; every `FunctionSpec` has an impl; every `ActionSpec` has a handler and an emitter (`CoverageTests`).
- [ ] `apply(patch.inverse(), apply(patch, d)) == d` for all commands over ≥ 1,000 generated documents (`PatchInversePropertyTest`); `apply(diff(a, b), a) == b`.

### 36.3 Architecture

- [ ] The architecture does not require a visual editor: `:tools:cli` performs decode → validate → generate, and `:samples:desktop-preview` renders a document, using only public APIs.
- [ ] The core domain model (`:engine:model`, `:schema`, `:serialization`, `:interpreter`, `:analysis`, `:editing`, `:codegen`, `:builtins`) has **no Compose dependency** (`verifyModuleGraph`, `NoComposeInDomainTest`).
- [ ] The module dependency graph matches §23 exactly, with no cycles (`ModuleGraphTest`).
- [ ] `:engine:codegen` and `:engine:runtime` do not depend on each other.
- [ ] `explicitApi` is on everywhere; ABI dumps are committed and checked; internal APIs are `internal` or `@EngineInternalApi`.
- [ ] No `when` over component types anywhere in engine code (`NoComponentWhenTest`).

### 36.4 KMP readiness

- [ ] All pure modules compile and pass `commonTest` on Android, Desktop, iOS simulator (canary) and wasmJs (canary).
- [ ] No `java.*`/`android.*` import in any `commonMain` source set (`CommonMainPurityTest`).
- [ ] `expect/actual` declarations exist only in the allow-listed files of §21.3.

### 36.5 Performance and quality gates

- [ ] Benchmarks on a 10,000-node document meet the §29 targets (recorded baselines committed; nightly regression alert configured).
- [ ] `./gradlew check` is green with `allWarningsAsErrors`.
- [ ] Documentation exists: format specification (envelope, canonical JSON rules, `@SerialName` catalog), plugin authoring guide (the 4-step recipe of §7.4), CLI usage, and this plan's ADRs kept in `docs/adr/`.

### 36.6 The final question

A new senior developer or coding agent, given only this `PLAN.md`, must be able to implement Phase 0 → Phase 10 in order **without making any architectural decision** beyond the ones explicitly listed as open in the phase descriptions (target/library version selection at kickoff, verifying KotlinPoet's JVM-only status, verifying `kotlinx-io` target coverage). If a phase forces a new architectural decision, the phase is amended by adding an ADR **before** implementation continues.
