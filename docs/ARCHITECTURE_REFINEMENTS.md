# Architecture Refinements

**Reviewed document:** `PLAN.md`
**Review type:** strict architectural refinement and continuation — not a redesign.
**Default verdict unless stated otherwise: KEEP.**

All section/ADR references below (`§x.y`, `ADR-0xx`) point at the existing `PLAN.md` unless marked `(new)`.

---

## 1. Scope and Compatibility Rules

Two rules govern every recommendation in this document:

1. **Platform scope is narrower than the existing plan assumes.** The only supported runtime targets, now and for the planning horizon of this document, are **Android** and **Desktop/JVM**. `PLAN.md` was written for a broader KMP horizon and bakes in live iOS/wasmJs canary compilation (G9, §21.1, §28.7, §36.4). That is now out of scope and is addressed as Critical Refinement C1.
2. **`PLAN.md` remains authoritative.** Every item below is classified `KEEP`, `REFINE`, `CHANGE`, or `DEFER`. `CHANGE`/`REFINE` entries are additive or corrective patches to specific sections — they do not replace the model, the registry architecture, the runtime, codegen, serialization, or the module graph. Nothing here renames a working concept without a stated technical reason, and nothing here is a stylistic preference.

---

## 2. Existing Architecture Assessment

Reconstruction of the current architecture, confirmed against `PLAN.md`, with an overall verdict per system. This section is deliberately short — see §7 "Do NOT Repeat What Already Exists."

| System | PLAN.md | Verdict |
|---|---|---|
| Module graph | §22–§23: 11 engine modules + tools/integration/samples, explicit forbidden-dependency matrix, Konsist-enforced | **KEEP.** Sound, minimal, intentional. One scope-driven simplification proposed (§17 below). |
| Domain model | §5–§6: normalized `NodeTable`, closed `Value` union, stable ids, composition-only reuse (ADR-001, ADR-007, ADR-017) | **KEEP.** Correct trade-off for an editor-backing model. Two missing validation invariants found (§4). |
| Registry system | §8: 5 schema registries + 2 runtime registries, immutable-after-build, no silent override (ADR-002) | **KEEP.** Decisive and correct. One cross-cutting consistency gap found (§6). |
| Runtime | §15: `ResolvedDocument`-only input, declarative scope/modifier handling, Compose confined to 2 modules | **KEEP.** |
| Code generation | §16: custom Kotlin IR + deterministic printer, declarative `CodegenBinding` (ADR-004) | **KEEP.** Strong design. Naming-collision coverage has a gap (§7). |
| Serialization | §18–§19: canonical JSON, schema-free decoding, two-level versioning, migration chain on `JsonElement` (ADR-005, ADR-006, ADR-016) | **KEEP.** One API-completeness gap found (§16). |
| Validation | §17: 8 ordered passes, structured `Diagnostic`, stable code catalog | **KEEP.** Two missing passes found (§4, §9). |
| Editor boundary | §25: `DocumentController` as sole mutation gateway, engine owns the document, `Command`/`Patch`/`History` (ADR-013, ADR-014) | **KEEP.** Correct separation. Two contract gaps found (§8, §13). |

**Conclusion:** the architecture is sound. This review found no reason to redesign any of the above. It found concrete, narrow gaps — mostly *missing validation checks for reference graphs that are new in this plan* (component composition, derived state) and *underspecified contracts at module boundaries* (schema identity, transaction atomicity, decode failure) — plus one genuine scope correction (platform targets).

---

## 3. Critical Refinements

### C1 — Remove iOS/wasmJs from the build and CI; keep purity enforcement static-only

#### Change
Remove `iosSimulatorArm64()` and `wasmJs { browser() }` canary targets from the `forge.kmp.library` convention plugin (§21.1, §22.3) and from Phase 0 (§32). Remove the `canary` CI job (§28.7). Remove the two iOS/wasmJs bullet points from Definition of Done §36.4 ("compile canaries" and "pass `commonTest` on iOS simulator and wasmJs"). Reword G9 (§2) from a *verified-by-compilation* goal to a *verified-by-static-analysis* goal: "the domain stays portable to future KMP targets because it contains no Compose and no JVM/Android-only API — enforced by Konsist, not by compiling for those targets."

#### Reason
The task scope is explicit and absolute: only Android and Desktop/JVM are supported targets, and "if an abstraction exists solely to support hypothetical future platforms, question whether it is actually necessary." Live iOS/wasmJs canary compilation is exactly that: it requires installing and maintaining Kotlin/Native and Kotlin/Wasm toolchains in CI, produces target-specific compiler diagnostics for platforms nobody runs, and gates every PR on infrastructure that serves no current product goal. The *purity* it protects (no Compose/JVM leakage into `commonMain`) is already independently enforced by `NoComposeInDomainTest` and `CommonMainPurityTest` (Konsist, §23.4) — static source-level checks give the same guarantee at a fraction of the build cost and with zero toolchain risk.

#### Existing behavior preserved
`commonMain`-first structure, `expect/actual` policy (§21.3), the Compose confinement rule (§21.4), and every Konsist rule in §23.4 are unchanged. Nothing about the domain model, registries, runtime, or codegen changes. A future decision to target iOS/wasmJs (roadmap item 17, §32) re-adds the canary jobs at that time — the module code does not need to change, only the Gradle target declarations and CI config.

#### Impacted modules
`build-logic` (`forge.kmp.library` convention plugin), Phase 0 task list (§32), CI workflow definitions (§28.7), Definition of Done §36.4.

#### Migration required
None — this is a plan-level correction prior to Phase 0 implementation. If Phase 0 has already stood up the canary targets, removing them is a pure deletion (Gradle target blocks + one CI job file), not a breaking change to any public API.

#### Risk
Low. The only thing lost is early warning if `commonMain` code accidentally references a JVM-only API in a way Konsist's pattern-matching doesn't catch; this is a narrow residual risk against a real, ongoing CI cost.

#### Implementation constraint
When Phase 0 is implemented, `forge.kmp.library` declares `android()` and `jvm("desktop")` only. Konsist's `CommonMainPurityTest` must still ban `java.*`/`android.*` imports in `commonMain` (that rule has value independent of target count, since `commonMain` is shared between Android and Desktop/JVM and must not accidentally depend on one or the other).

---

## 4. Domain Model Refinements

The record model (§5–§6) is correct as designed: normalized `NodeTable`, stable ids, composition-only reuse, no parent pointers (derived via `DocumentIndex`). Two concrete gaps were found, both in the same family: **static reference graphs that are not covered by the existing cycle check.**

### D1 — Component-declaration instantiation cycles are undetected

#### Change
Add a dedicated acyclicity check over the **component declaration graph**: for every `ComponentDecl`, the directed edges are the `doc.<ComponentDeclId>` instance types appearing (anywhere, at any depth) inside its own `tree`. This graph is independent of per-instance slot content (slot content is supplied by the caller at each instantiation site, not part of the callee's static tree) and is therefore fully decidable with a standard DFS. Run it in a new dedicated pass (or as an addition to `ReferencePass`) **before** `ResolutionPass`, producing diagnostic code `component.cycle` with the full cycle path in the diagnostic's `args`.

#### Reason
`StructuralPass` (§17.1) only validates the parent/child graph *within one `NodeTree`*. It cannot see a cycle that exists only through `doc.*` instantiation edges across two or more `ComponentDecl`s (A's tree contains an instance of B; B's tree contains an instance of A). Such a cycle currently **type-checks successfully** through every existing pass (each individual reference is valid — B exists, A exists) and is only discovered at runtime, where `DeclInstanceRenderer` recurses into itself until the Compose call stack overflows, and in generated code, where the emitted composables call each other and compile fine but blow the stack the first time the screen renders. This is exactly the class of defect `PLAN.md` otherwise defends against everywhere else (ADR-011 "avoid generating invalid Kotlin," §16.7 "report invalid models"): a document that looks valid but is semantically broken, caught only by a crash instead of a diagnostic. Phase 8's task list already mentions "cycle detection between component decls (`struct.component_cycle`)" as a one-line task — this refinement makes it a first-class, specified check with its own diagnostic code and pass placement, consistent with the rest of §17.3's catalog.

#### Existing behavior preserved
No change to `ComponentDecl`, `SlotOutlet`, or the overlay mechanism (§5.7, §8.4). This is a pure addition to the validation pipeline; documents without instantiation cycles are unaffected.

#### Impacted modules
`:engine:analysis` (new pass or `ReferencePass` extension; add `component.cycle` to the diagnostic catalog, `pass/` directory per §33.5).

#### Migration required
None (new validation rule; no document format change).

#### Risk
Low. The only behavior change is that previously-crashing documents now fail validation with a clear diagnostic instead of crashing at render/compile time — strictly an improvement. Minor cost: one extra O(components + edges) graph pass per analysis run (negligible next to the existing eight passes).

#### Implementation constraint
Must run after `ReferencePass` establishes that every `doc.<id>` reference resolves to a real `ComponentDeclId` (cycle detection on dangling references is meaningless), and before `ResolutionPass` (which would otherwise recurse to resolve a cyclic overlay). Golden/negative test required (§28 catalog pattern: one positive, one negative per code).

### D2 — Derived-state dependency cycles are undetected

#### Change
Add the same cycle check for `StateDecl.derived: Expr` dependencies: build a graph where each derived `StateId` has edges to every other `StateId` its `derived` expression references (via `RefTarget.State`), and reject cycles with diagnostic code `state.derived_cycle`. Implement this and D1 as **one shared, reusable utility** — e.g. `GraphCycleCheck<N>(nodes, edges): List<N>` in `:engine:analysis` — invoked by both the component-graph check and the derived-state check, rather than two independent implementations.

#### Reason
This is structurally the same defect as D1: a `derived` expression that (directly or transitively) references itself type-checks fine per-edge in `ExpressionPass` (§17.1, pass 5) but causes unbounded recursion at evaluation time in the interpreter, and in generated code becomes a Kotlin property getter that compiles but stack-overflows the first time it's read (`val doubled: Int get() = count * 2` — if `count` were itself derived from `doubled`, this compiles and crashes identically to D1). Finding two independent instances of the same missing-invariant class in one review is a strong signal that the *general* capability ("detect cycles in a reference graph") is the actual missing piece, not two unrelated point fixes — hence the shared-utility recommendation.

#### Existing behavior preserved
No change to `StateDecl`, `Expr`, or the evaluator (§10, §12). Pure validation addition.

#### Impacted modules
`:engine:analysis` (new shared `GraphCycleCheck` utility + its two call sites in `ExpressionPass`/a new pass; new diagnostic code).

#### Migration required
None.

#### Risk
Low, same profile as D1.

#### Implementation constraint
Must run after `ExpressionPass` resolves every `Expr.Ref(RefTarget.State(...))` to a real, existing `StateDecl` (same ordering reasoning as D1).

---

## 5. Registry Refinements

§8 is already decisive. This section restates the task's required lifecycle questions explicitly (per the task's instruction to "be decisive, do not leave these as open questions") and records one genuine cross-module gap.

| Question | Answer (per PLAN.md §8.2–§8.4, restated decisively) |
|---|---|
| Can a component be registered twice (same `ComponentType`)? | **No.** Duplicate key at `Schema.build{}` time throws `SchemaBuildException`. There is no silent-override mode. |
| Can a project override a built-in component (e.g., restyle `m3.Button`)? | **No, not in MVP**, and this is intentional, not an oversight: the only two override mechanisms are (a) per-instance property overrides on a node, and (b) `ThemeDecl.componentDefaults`, which is explicitly post-MVP (§14.1, §31.3). To genuinely replace a built-in component's behavior, a project registers a **new** `ComponentType` under its own namespace (e.g. `local.Button`) — it cannot shadow `m3.Button`. This should be stated as a decisive product rule, not left implicit: *"override" in this engine means "define a new type," never "redefine an existing type."* |
| Can a registry be mutated after initialization? | **No.** `Schema` is an immutable value built once via `SchemaBuilder`; there is no `register()` method on `Schema` itself, only on the builder. |
| Can runtime code mutate registries? | **No.** `UiRuntime` holds immutable `RendererRegistry`/`ModifierApplierRegistry` built once; there is no mutation API on either. |
| Can code generation use a different registry than the runtime? | **Mechanically yes (this is the gap — see R1), intentionally no.** Nothing in the type system *prevents* a caller from building two different `Schema` instances and handing one to `Analyzer`/`KotlinGenerator` and a different one to `UiRuntime`. |

### R1 — No cross-check that Runtime and Codegen share the same Schema

#### Change
Add `Schema.fingerprint: String` — a stable hash computed once at `Schema.build{}` time over the sorted list of `(ComponentType, version)`, `(ModifierType)`, `(ActionId)`, `(FunctionId)`, `(TypeId)` entries. Introduce a thin, optional facade (e.g. `EngineSession(schema: Schema)`, `@EngineInternalApi`, living in `:engine:schema` or a new tiny coordination point) that any consumer needing **both** a runtime and a codegen path in the same process (the eventual visual editor, primarily) is encouraged to go through, so that `UiRuntime` and `KotlinGenerator` are constructed from the literal same `Schema` value by construction rather than by caller discipline. At minimum, document this as a hard rule with a runtime-checkable assertion: if an `EngineSession`-style facade is not adopted immediately, add a debug-mode check (`require(runtime.schemaFingerprint == generator.schemaFingerprint)`) usable by integration tests.

#### Reason
§4.5 states "the runtime and generator must consume the same authoritative model" and ADR-015 formalizes `ResolvedDocument` as the shared input — but `ResolvedDocument` is produced by `Analyzer(schema)`, and `UiRuntime` is independently constructed with its *own* `schema` argument (§15.2: `UiRuntime(renderers, modifiers, implementations, schema)`). The facade example in §24.2 happens to reuse one `schema` value for both paths, which is correct, but that is a usage convention shown in an example, not a contract enforced by any type or runtime check. In a long-lived process (the main place this risk materializes: a future editor or a long-running server), it is entirely possible for `Analyzer`'s schema to be built from a different pack-installation call site than `UiRuntime`'s schema (e.g., one omits a late-added plugin, or installs a different version of `Material3Pack`) without any error — `RuntimeCoverage.check()` and `CodegenCoverage.check()` each independently pass (each schema is internally coverage-complete), so nothing catches the *mismatch between the two*. This directly undermines the single most important architectural guarantee in the whole plan (G1, §4.5) through a hole that is pure accident-of-call-site, not a deliberate design choice — exactly the kind of "registry lifecycle / cross-module consistency" problem the review was asked to find.

#### Existing behavior preserved
`Schema`, `SchemaBuilder`, `UiRuntime`, `Analyzer`, `KotlinGenerator` keep their existing constructors and semantics. `fingerprint` is an additive field; nothing currently reading `Schema` is affected.

#### Impacted modules
`:engine:schema` (`Schema.kt`: add `fingerprint`), optionally a new small coordination type (`EngineSession.kt`, `:engine:schema` or a new minimal module-free top-level file — **not** a new Gradle module; this does not warrant one), `:engine:test-support` (an integration test asserting `UiRuntime.schemaFingerprint == KotlinGenerator`'s schema fingerprint for every conformance fixture's construction path, §28.4).

#### Migration required
None.

#### Risk
Low to add; the value is primarily defensive/diagnostic. Main risk is scope creep if `EngineSession` is over-built into something resembling a DI container — explicitly keep it to a single field comparison, not a service locator (that would itself violate §35's anti-pattern list).

#### Implementation constraint
`fingerprint` must be computed deterministically and must not depend on map/set iteration order (same discipline already applied everywhere else in the plan, e.g. canonical JSON key sorting, §18.2). Do **not** build a general plugin-dependency-injection mechanism around this — the fix is a one-field consistency check, not new architecture.

---

## 6. Runtime / Codegen Parity

This is the area given the most attention in the existing plan (§4.5, ADR-003, ADR-015, the conformance suite §28.4) and it holds up well. Beyond R1 (above, which is fundamentally a parity-mechanism gap and is cross-referenced here), two smaller parity items were found:

### P1 — Generated `AppTheme` should expose the same dark-mode override surface as the runtime

#### Change
Generated `theme/AppTheme.kt` should expose `@Composable fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`, mirroring the runtime's own default-with-override behavior ("chosen by `isSystemInDarkTheme()` or environment override," §14.2).

#### Reason
§14.2 specifies the runtime's dark-theme resolution precisely but is silent on whether the *generated* `AppTheme` composable exposes the same override point. Without an explicit parameter, a hand-written app built from the generated code has no supported way to plug in a user-facing light/dark toggle — forcing either a fork of generated code (defeats ADR-011's one-way-generation model) or an unsupported workaround. This is a one-parameter addition that brings codegen output in line with runtime behavior and with the idiomatic Compose/Material3 convention.

#### Existing behavior preserved
No model or analysis change — this only refines what `ThemeEmitter` (§33.7) emits.

#### Impacted modules
`:engine:codegen` (`emit/ThemeEmitter.kt`).

#### Migration required
None (codegen output change, covered by updating the relevant golden fixture).

#### Risk
Negligible.

#### Implementation constraint
Add/update one golden fixture (`theme_dark_override.expected.kt`) demonstrating the parameterized signature.

### P2 — No iteration mechanism at all in MVP (confirmed DEFER, flagged for consideration)

**Verdict: DEFER — explicitly re-confirmed, not changed.** `PLAN.md` deliberately excludes `LazyColumn`/iteration scopes from MVP (wave 2, §7.5, §31.3), which also means the MVP has **no** way to render a variable-length list bound to data — not even a non-virtualized `Column` + repeat. For a FlutterFlow-like product this is a materially common first-screen scenario (a list of items), so it is worth recording explicitly that this is a known, conscious limitation of the MVP's proof surface rather than an oversight. No change is proposed: pulling any form of iteration into MVP wave 1 would add a new mechanism (typed item scope variables, §7.5's "one new mechanism") to a phase whose stated job (§32, Phase 4) is proving the pipeline on the smallest possible surface. This is recorded here, not in §24 "Deferred Changes," because it is a confirmation of an existing decision, not a new proposal.

---

## 7. Code Generation Refinements

§16 treats codegen as a real compiler backend already (IR, deterministic printer, import/alias resolution, `NamingPolicy`). Two naming-collision gaps were found — both in the same family as D1/D2 (a class of input that currently compiles/validates per-rule but collides in aggregate).

### G1 — Codegen's own infrastructure identifiers are not reserved against document-declared names

#### Change
Introduce a `ReservedCodegenNames` set, parameterized by the active `NavigationStrategy` and `StateStrategy` (since the reserved set depends on which strategy is selected — different strategies may introduce different top-level symbols). For `SimpleBackStack` + `ComposeSnapshotState` (the MVP defaults), this includes at minimum: `Route`, `AppNavigator`, `AppState`, `AppTokens`, `AppHost`, `LocalAppState`, and the pattern `${pageName}State` (per-page state holder class names). Check every document-declared identifier (`Page.name`, `ComponentDecl.name`, `DataModelDecl` type name, `StateDecl.name`) against this set during `NamingPass` (or `CodegenFeasibility`, §17.1 pass 8, since the reserved set depends on `CodegenOptions`), producing diagnostic code `name.reserved`.

#### Reason
§16.4's `NamingPolicy` resolves collisions between **document-declared names** and **library symbols** (`androidx.compose.material3.Text` vs. a user component also named `Text`) deterministically — but it does not address collisions between document-declared names and the engine's **own generated infrastructure symbols**. If a user names a `DataModel` "Route" or a `Page` "AppState" (both entirely plausible, unremarkable names an app author might pick), the generated file either fails to compile (duplicate top-level declaration in the same file/package) or silently shadows engine-generated code in a way `NamingPolicy`'s current rules don't anticipate, because those rules were designed for user-vs-library collisions, not user-vs-generator-infrastructure collisions. This is exactly the kind of "invalid model" that §16.7 says must be *reported*, not discovered via a failed compile — currently it would only surface in the compile harness (§28.5), too late to give the author a structured, located diagnostic.

#### Existing behavior preserved
No change to `NamingPolicy`'s existing library/user collision rules (§16.4, first bullet). This is an additive check layered on top.

#### Impacted modules
`:engine:codegen` (`naming/NamingPolicy.kt`, new `ReservedCodegenNames` keyed by `CodegenOptions`), `:engine:analysis` (`FeasibilityPass`, new diagnostic code `name.reserved` in the catalog, §17.3).

#### Migration required
None.

#### Risk
Low. Slightly reduces the space of legal document names (a handful of reserved words), which is the entire point.

#### Implementation constraint
The reserved set must be derivable *before* full codegen runs (it needs to be available to the `FeasibilityPass` during analysis, not discovered only inside the generator), so it should be a static property of each `NavigationStrategy`/`StateStrategy` implementation (e.g. `val reservedTopLevelNames: Set<String>`), not something computed only while emitting files.

### G2 — Identifier-uniqueness scope across kinds is ambiguous

#### Change
Clarify explicitly in §17.1/§17.3 (`NamingPass`) that uniqueness of `Page.name`, `ComponentDecl.name`, and `DataModelDecl` names is enforced **per kind**, not globally (pages are unique among pages; components among components; data models among data models) — matching the fact that each kind is emitted into its own sub-package (`screens/`, `components/`, `model/`, §16.5) and therefore cannot physically collide at the Kotlin symbol level. Add one golden/conformance fixture where a `Page` and a `ComponentDecl` deliberately share a simple name (e.g., both called `ProfileCard`) to prove no import ambiguity arises when one references the other.

#### Reason
§17.1 states `NamingPass` checks "identifiers valid/unique (pages, components, state, params)" without specifying the uniqueness scope. Given the per-kind package layout this is almost certainly already the intended behavior, but it is exactly the kind of "insufficiently specified behavior" the review was asked to surface — a future implementer could reasonably read it as "unique across the whole document" and over-restrict, or under-specify and allow a real collision inside a single generated file (e.g., a `ComponentDecl` and a `DataModelDecl` both named `UserCard` could collide if a screen needs to import both).

#### Existing behavior preserved
This is a clarification, not a behavior change, assuming per-kind scoping was the intent (which the file-layout design strongly implies).

#### Impacted modules
`:engine:analysis` (`pass/NamingPass.kt` — documentation/implementation clarity only), `:engine:codegen` (one new golden fixture).

#### Migration required
None.

#### Risk
Negligible — pure specification clarity.

#### Implementation constraint
None beyond adding the fixture and the doc comment on `NamingPass`.

---

## 8. Mutation and History

§25–§26 already define a coherent `Command → Patch(+inverse) → History` pipeline that maps close to 1:1 onto the task's requested operation set (`InsertNode→InsertNode`, `DeleteNode→RemoveNode`, `MoveNode→MoveNode`, `ReplaceProperty→SetProperty`, `AddModifier/RemoveModifier→SetModifiers`, `ReplaceChildren` → composition of `MoveNode`s, `DuplicateNode→DuplicateNode`, `WrapNode/UnwrapNode→WrapNode/UnwrapNode`). **KEEP** the overall abstraction. Two contract gaps found:

### M1 — `transaction {}` atomicity is unspecified

#### Change
Make `DocumentController.transaction` explicitly atomic and give it a typed result instead of a bare `R`:

```kotlin
public sealed interface TransactionResult<out R> {
    public data class Committed<R>(val value: R, val patch: Patch) : TransactionResult<R>
    public data class RolledBack(val reasons: List<Diagnostic>) : TransactionResult<Nothing>
}
public fun <R> transaction(label: String, block: TransactionScope.() -> R): TransactionResult<R>
```

All `Command`s issued inside one `transaction {}` block are planned and validated as a batch; if any one is rejected, **none** are applied and the whole transaction returns `RolledBack` — never a partially-applied state and never a thrown exception for an ordinary (expected) rejection.

#### Reason
§25.2's current signature (`fun <R> transaction(label: String, block: TransactionScope.() -> R): R`) does not say what happens when a command partway through the block is illegal (e.g., an `InsertNode` into a slot that turns out not to accept the given component type). Editors will routinely build compound operations this way (e.g., "wrap in Card" = insert wrapper + move original + set one default property, or "paste" = insert N nodes + remap M references) — if a later step in such a sequence can fail, leaving the first N steps applied and silently "committed" to history is a correctness hazard for undo/redo (the user undoes once, expecting to undo "paste," but only undoes the last of several partially-applied ops, or worse, never gets a chance to undo a never-rejected-but-garbled state). Atomic, all-or-nothing transactions are the standard, expected semantics for this kind of API and cost nothing to specify now, before `:engine:editing` (Phase 9) is implemented.

#### Existing behavior preserved
`Command`, `Patch`, `PatchOp`, `History`'s merge-by-`mergeKey` behavior (§26.2–§26.3) are unchanged. Single-command `dispatch()` (`CommandResult = Ok(patch) | Rejected(reasons)`, §25.2) is unaffected — this refinement is scoped to the multi-command `transaction {}` path only.

#### Impacted modules
`:engine:editing` (`DocumentController.kt`, new `TransactionResult` type).

#### Migration required
None — `:engine:editing` is Phase 9, not yet implemented per the phase plan.

#### Risk
Low. The only behavior change versus an unspecified baseline is that failures now roll back instead of leaving an undefined partial state — this is a strict improvement in predictability.

#### Implementation constraint
"Validated as a batch" means: plan all commands' patches against the *document as it would be after the previous commands in the same transaction*, but do not commit any of them to the controller's live document/history until the entire block completes without rejection. This requires `transaction {}` to operate on a local, uncommitted working copy — straightforward given the model is already immutable/persistent (ADR-007).

### M2 — `dispatch()` vs. `Analyzer.analyze()` boundary is implicit

#### Change
State explicitly, in §17.1 or §25, that `DocumentController.dispatch()`/`CommandPlanner` perform only **structural/schema-policy legality** checks (slot cardinality, `ChildFilter`, scope availability for drop-target purposes) — sufficient to decide whether a command *can be planned into a valid patch* — and that they do **not** run the full `Analyzer` pipeline (expression type-checking, reference validation, codegen feasibility). Full `Diagnostic`s for live inline editor feedback (red squiggles, error panels) require the caller to invoke `Analyzer.analyze(document)` separately after a command or a batch of commands. Document the recommended pattern explicitly: callers debounce `Analyzer.analyze()` calls (e.g., on an edit-settle timer or on focus-loss) rather than invoking it synchronously after every `dispatch()`, given full re-analysis is the MVP's only analysis mode (incremental analysis is post-MVP, §29.1).

#### Reason
§6.2 already hints at this ("`Patch.touchedNodes` ... define the dirty set for incremental re-analysis," which is itself post-MVP) but nowhere does the plan state **who is responsible for calling `Analyzer` and when**, or that `dispatch()` itself does not. This matters concretely for the desktop editor use case the task highlights (§24 "high-frequency mutations"): if a naive editor implementation calls `Analyzer.analyze()` synchronously after every keystroke-level `dispatch()` against a document near the 10,000-node performance target (§29: ~200ms per full analysis), the editor will visibly stutter on every character typed into a text property. This is not a flaw in the engine — full re-analysis per edit is a knowingly deferred optimization (§29.1) — but the *contract* that makes debouncing both safe and necessary is currently implicit, and an implementer who doesn't infer it will build a laggy editor and then (incorrectly) blame the analyzer's performance rather than the calling pattern.

#### Existing behavior preserved
No API or behavior change — this is a specification/documentation refinement that makes an existing, correct design decision explicit and gives implementers the guidance needed to use it correctly.

#### Impacted modules
Documentation only: §17.1 (add one sentence to the pass-pipeline intro) and §25.5 (Threading model — add one paragraph on the dispatch/analyze relationship and the debounce recommendation).

#### Migration required
None.

#### Risk
None — pure clarification.

#### Implementation constraint
None.

---

## 9. Validation Refinements

§17's 8-pass pipeline and structured `Diagnostic` model (§17.2) are a strong design — **KEEP** in full. Additions, consolidating findings already detailed above:

- **D1 / D2** (§4 above) add two new validation checks (`component.cycle`, `state.derived_cycle`) via a shared cycle-detection utility — placed here for completeness of the diagnostic catalog (§17.3 gains two new rows).
- **G1** (§7 above) adds `name.reserved` to the catalog, produced during `FeasibilityPass`.
- **V1 (new, minor): typed `ActionPath` instead of the generic `path: List<String>` fallback.**

#### Change
Add a small closed type for pinpointing diagnostics inside nested `ActionSequence`s:

```kotlin
public sealed interface ActionPathSegment {
    public data class Step(val index: Int) : ActionPathSegment
    public data class Branch(val name: BranchName) : ActionPathSegment
}
```

and add `actionPath: List<ActionPathSegment> = emptyList()` to `DiagnosticLocation` (§17.2), used by `ActionPass` for errors inside nested `flow.if` branches, in place of relying on the generic, stringly `path: List<String>` catch-all.

#### Reason
Every other field on `DiagnosticLocation` (`nodeId`, `property`, `modifierIndex`, `event`) is strongly typed; `path: List<String>` is the one untyped escape hatch, and the one concrete place it's needed — pinpointing an error at, e.g., "step 3 of the `else` branch of a nested `flow.if`" — is exactly the kind of structured location data `Diagnostic` otherwise guarantees everywhere else (§17.2's own stated goal: "do not rely exclusively on strings"). This is a small, consistency-driven refinement, not a new capability.

#### Existing behavior preserved
`DiagnosticLocation`'s other fields and `Diagnostic`'s overall shape are unchanged; `path` can remain as a true last-resort fallback for genuinely unanticipated cases.

#### Impacted modules
`:engine:analysis` (`diagnostic/DiagnosticLocation.kt`, `pass/ActionPass.kt`).

#### Migration required
None (`Diagnostic` is not a persisted document type).

#### Risk
Negligible.

#### Implementation constraint
None.

---

## 10. Expression Refinements

§10 is decisive about scope (ADR-008) and already includes a hazard table (§10.6) and a differential corpus (§10.7) — the strongest anti-drift mechanism in the whole plan. One specification gap:

### E1 — Element-type-variable resolution for generic functions is named but not algorithmically specified

#### Change
Add a short, explicit specification to `FunctionSpec`'s type-signature model (§10.3, `ParamSig`/`TypeSig`) for the small, closed set of type variables actually needed (MVP only needs one: "element type of a `List<T>` argument," used by `list.get(items: List<T>, index: Int): T?`). Specify the unification algorithm precisely: for a call `f(a1, …, an)`, walk `FunctionSpec.params` left-to-right; the first parameter whose `TypeSig` contains `TypeVar.Element` binds that variable to the element type of the corresponding *actual* argument's type (which must itself be a `TypeRef.ListOf`); every subsequent occurrence of that variable (including in `returns`) is substituted with the bound type; a mismatch (the argument isn't a list, or a later occurrence disagrees with the binding) is `expr.type_mismatch`.

#### Reason
§10.3 names "generics limited to element-type variables" as part of `FunctionSpec` but does not specify *how* they're resolved. This is a small piece of surface area, but it sits directly inside the type checker that both backends rely on for parity (D2 in the existing register of difficult decisions, §4.6) — an under-specified unification rule is exactly the kind of ambiguity that produces subtly different behavior between a first implementation and a later one (or between the interpreter author's and the codegen author's mental model of it), which is precisely what the expression corpus (§10.7) exists to catch, but only if the behavior is specified clearly enough to disagree with in the first place.

#### Existing behavior preserved
No change to the MVP function set (§10.2's seed list) or to ADR-008's closed-AST philosophy — this only pins down a mechanism already implied by existing specs.

#### Impacted modules
`:engine:analysis` (`typing/TypeChecker.kt`), `:engine:schema` (`function/FunctionSpec.kt` — add `TypeVar.Element` to `TypeSig`'s sealed hierarchy).

#### Migration required
None.

#### Risk
Negligible — this narrows ambiguity, it doesn't change any currently-specified behavior.

#### Implementation constraint
Keep the type-variable set closed and minimal (one variable kind is sufficient for the entire MVP function list in §10.2); do not generalize into a full Hindley–Milner-style inference engine — that would violate ADR-008's explicit "no full language" boundary for no concrete MVP benefit.

---

## 11. Action Refinements

§11 is solid (data-only `ActionSequence`, intrinsic vs. plugin action split, §11.4). One behavior needs to move from implicit to explicit documentation (no code change):

### A1 — Action-sequence cancellation and non-transactional state writes should be stated as intended behavior

#### Change
Add one explicit paragraph to §11.5: "An `ActionSequence`'s coroutine scope is the composition's `rememberCoroutineScope()`; if the owning composable leaves composition (e.g., the user navigates away mid-sequence), remaining steps are cancelled silently — no `RuntimeDiagnostic` is emitted for an ordinary cancellation. Side effects already performed by completed steps (notably `state.set`) are **not** rolled back: `ActionSequence` execution is not transactional. Authors who need all-or-nothing semantics must express that explicitly (e.g., validate with `flow.if` before any `state.set`, or keep sequences short enough that partial execution is harmless)."

#### Reason
This behavior already follows correctly and automatically from the stated design (`rememberCoroutineScope()` cancels its children when the composable leaves composition — standard Compose semantics) — nothing needs to change in the interpreter or codegen. But it is a real, user-facing semantic (a multi-step `onClick` handler that does `state.set` then `nav.navigate` then another `state.set` can leave state partially updated if the user double-taps or navigates away mid-flight) that is currently only *derivable* from how `rememberCoroutineScope` happens to behave, not *stated* as an intended contract. Per the task's explicit focus on "insufficiently specified behavior" and "edge cases," this is worth promoting from implicit to explicit so a future implementer (or the eventual editor's UX for authoring actions) treats it as a documented constraint rather than rediscovering it as a bug report.

#### Existing behavior preserved
Entirely — this section documents existing, correct, already-implied behavior; no code changes anywhere.

#### Impacted modules
Documentation only (§11.5).

#### Migration required
None.

#### Risk
None.

#### Implementation constraint
None.

---

## 12. State Refinements

§12's `StateStrategy` abstraction (ADR-012) is a good, framework-agnostic design — **KEEP**. One ambiguity found:

### S1 — `Persistence.None` vs. configuration-change survival is ambiguous

#### Change
Clarify explicitly, in §12.1's state-scope table, that `Persistence` governs **process-death/restart survival only** (disk- or store-backed persistence — the dimension the `PersistentStore` hook in §12.1/§11 addresses). **Configuration-change (rotation) survival is orthogonal**: page-scope state whose declared `TypeRef` is saveable (primitives, and any `ObjectTypeSpec` the engine can map to a `Saver`) automatically uses `rememberSaveable` regardless of `Persistence`, exactly as Compose idiom expects. `Persistence.None` therefore means *"no disk/process-restart persistence,"* not *"discarded on rotation."* If a future need arises for state that must NOT survive rotation (rare, but real — e.g., a deliberately ephemeral animation flag), that is a distinct, currently-unsupported third option and should be named as such rather than overloaded onto `Persistence.None`.

#### Reason
§12.1's table entry for Page scope says "`PageStateStore` (snapshot state map)" at runtime and "`rememberSaveable` where type is saveable" — but doesn't say whether `rememberSaveable` usage is gated by `StateDecl.persistence` or applied unconditionally whenever the type permits it. This is precisely the sort of two-reading ambiguity the task asked to be run down: one reading makes `Persistence.None` state vanish on rotation (likely *not* what most authors assume "default/no persistence" means for ordinary UI state like a text field's current value), the other makes it survive rotation but not app restart (the far more standard, expected Android behavior, and almost certainly the intended reading given "`rememberSaveable` where type is saveable" is phrased as an unconditional, type-driven rule in the sentence as written). Given the architecture is pre-implementation, resolving this now — in the direction the text already leans — avoids a real behavioral surprise being discovered late (during Android device testing in Phase 8/10) instead of being a design decision made deliberately now.

#### Existing behavior preserved
This confirms and sharpens the reading already implied by the existing sentence; it does not propose a new mechanism.

#### Impacted modules
Documentation only (§12.1); if Phase 6's implementation (`:engine:runtime`, `SnapshotStateStore`/`PageStateHost`, §33.8) had been built against the opposite (gated) reading, this would need a one-line code change to make `rememberSaveable` usage type-driven rather than `Persistence`-driven — flagged here precisely so that choice is made deliberately during Phase 6, not accidentally.

#### Migration required
None (state representation is runtime/codegen behavior, not a persisted document field).

#### Risk
Low — this is a clarification that, if anything, prevents a user-facing data-loss surprise (text typed into a field vanishing on rotation) from ever shipping.

#### Implementation constraint
None beyond the Phase 6 implementation note above.

---

## 13. Component Contract Refinements

§7's `ComponentSpec`/`ComponentRenderer`/`CodegenBinding` split (ADR-003) already draws the declarative-metadata-vs-executable-implementation line correctly and precisely, and §17.4's `ComponentValidator` escape hatch is appropriately narrow (read-only, resolved data, single consumer — the analyzer). **No changes found here beyond the already-covered D1 (component-cycle detection, §4) and the already-covered R1 (schema-identity consistency, §5), both of which are component-contract-adjacent but detailed in their primary sections to avoid duplication.**

---

## 14. Layout and Modifier Semantics

§7.3's component-plus-modifier-plus-scope model (no `LayoutRegistry`, D6/D7) is the correct design and is already decisive about ordering (`List<ModifierEntry>`, "order is semantically significant," explicitly called out in §5.3's code comment). **KEEP in full — no gap found in ordering semantics, scope handling, or the Dimension-as-discrete-modifiers decision.** One concrete, low-risk functional gap in the MVP's *scope of modifiers shipped*, not in the architecture:

### L1 — Add scroll modifiers to the MVP modifier set

#### Change
Add `layout.verticalScroll` and `layout.horizontalScroll` to the MVP modifier list in §31.2 (`ModifierSpec`s wrapping `Modifier.verticalScroll(rememberScrollState())` / `Modifier.horizontalScroll(...)`), alongside the already-listed `fillMaxSize`/`padding`/etc.

#### Reason
The current MVP modifier set (§31.2: `fillMaxSize, fillMaxWidth, fillMaxHeight, padding, size/width/height, weight, align, background, clip, border, clickable, alpha`) and the MVP component set (§7.5 wave 1: `Column, Row, Box, …`, with `LazyColumn` explicitly deferred to wave 2) together mean **no MVP document can scroll any content that overflows its container** — not via a lazy list (wave 2) and not via a plain scrollable `Column` (modifier missing from wave 1). This is a real, practical ceiling on what the MVP's own walking-skeleton/example app (§30) and desktop preview sample (§31.2's tooling scope) can demonstrate, and it is the one missing piece that costs *nothing architecturally* — unlike `LazyColumn` (which needs the new iteration-scope mechanism, correctly deferred to wave 2, see P2 above), a scroll modifier is a plain `ModifierSpec` using the exact same declarative `ModifierEmit`/`ModifierApplier` mechanism every other MVP modifier already uses. It is a scope correction, not an architecture change.

#### Existing behavior preserved
No new mechanism. Fits the existing `ModifierSpec`/`CodegenBinding` shape exactly as every other modifier in §31.2 does.

#### Impacted modules
`:engine:builtins` (`modifiers/LayoutModifiers.kt`, §33.9), `:engine:builtins-compose` (one new modifier applier), `:engine:codegen` (one new `ModifierEmit` entry — purely data, no new emitter code path).

#### Migration required
None.

#### Risk
Negligible — purely additive to the component/modifier catalog that Phase 5 (§32) already builds out.

#### Implementation constraint
Add to Phase 5's task list (§32) alongside the rest of the wave-1 modifier set; add one golden fixture and one conformance fixture, per the existing per-modifier testing pattern (§28.2, §28.4).

---

## 15. Theme Refinements

### T1 — Theme resolution precedence deviates from a naive cascading model, and this is correct (confirm, no change)

**Verdict: KEEP, with explicit justification recorded.** A naive design might resolve every property through a four-level cascade (component override → component default → theme token → system default) with implicit fallthrough at each level. `PLAN.md`'s actual design (§9.2, §14.2) is simpler and more explicit: a property's effective value is its own explicit `PropertyValue` (which may itself be `Value.Token(...)`, a deliberate, visible reference into the active theme) if present, otherwise `PropertySpec.default`. There is no *implicit*, type-driven fallthrough from "no value set" to "look up a theme token for this property automatically" — token resolution only happens when the author *explicitly* wrote a token reference. This is the right call for this architecture specifically because it preserves the project's dominant principle (stated repeatedly: D4's self-describing values, §19.4's "never silently mutate," §35's ban on "auto-fixing invalid documents silently") that *nothing in a document's effective rendering is implicit* — every resolved value is traceable to something literally present in the document or the static spec default, never to a hidden multi-level lookup chain a reader would have to reconstruct. The one piece of the cascading model genuinely missing — per-component-type default overrides from the active theme (`componentDefaults`) — is already modeled and explicitly deferred to post-MVP (§14.1, §31.3) for the correct reason (it adds a real resolution-order question: does an explicit component default (`PropertySpec.default`) win over a theme's `componentDefaults` entry, or vice versa? — that question is appropriately deferred until there's a concrete need to answer it, rather than speculatively answered now).

---

## 16. Serialization and Migration

§18–§19 is one of the most mature sections of the plan (canonical JSON, schema-free decoding preserving unknown plugin data, two-level versioning, migration chain on `JsonElement`) — **KEEP in full.** One API-completeness gap:

### SR1 — `DecodeResult` does not model hard decode failures (corruption, malformed JSON, non-envelope input)

#### Change
Make `DecodeResult` an explicit sealed type covering both success and failure, instead of a shape that (per §18.3's description) only "carries `document`, `migratedFrom`, and `warnings`":

```kotlin
public sealed interface DecodeResult {
    public data class Success(val document: UiDocument, val migratedFrom: Int?, val warnings: List<Diagnostic>) : DecodeResult
    public data class Failure(val reason: DecodeFailureReason) : DecodeResult
}
public sealed interface DecodeFailureReason {
    public data class MalformedJson(val cause: String) : DecodeFailureReason
    public data object NotAnEnvelope : DecodeFailureReason
    public data class UnsupportedFutureVersion(val found: Int, val max: Int) : DecodeFailureReason
    public data class MigrationFailed(val atVersion: Int, val cause: String) : DecodeFailureReason
    public data class SchemaMismatch(val details: String) : DecodeFailureReason
}
```

`DocumentCodec.decode(...)` returns `DecodeResult` and does not throw for any input-data problem (malformed bytes, truncated file, a JSON file that isn't a Forge document at all, a future-version file). It may still throw for genuine programming-bug invariant violations (consistent with how `Analyzer`/`CodegenResult` already separate "domain problem → structured result" from "engine bug → exception," §16.7).

#### Reason
§18.3 already names `UnsupportedFutureVersion` as a specific, structured outcome — showing the design *wants* structured decode failures — but the described `DecodeResult` shape only has success-path fields (`document`, `migratedFrom`, `warnings`), which means either (a) `UnsupportedFutureVersion` and other hard failures are modeled as thrown exceptions, inconsistent with the "never bare exceptions, always structured results" discipline applied everywhere else in this plan (`Analyzer` never throws; `CommandResult`, `CodegenResult` are explicit result types), or (b) the shape is simply under-specified. The task explicitly calls out "corruption handling" as a required review area, and a user opening a truncated, hand-edited, or simply-wrong file (a very ordinary real-world event for a file-based format meant to be put under version control, §18.4) deserves the same clean, catchable, structured outcome as every other failure mode in this system — not a generic exception a CLI or future editor has to catch defensively and reverse-engineer a message for.

#### Existing behavior preserved
`Success`'s fields are exactly what §18.3 already describes; this is additive (naming the failure side that was already implied by `UnsupportedFutureVersion` being mentioned) rather than a redesign of the success path.

#### Impacted modules
`:engine:serialization` (`DocumentCodec.kt`, `JsonDocumentCodec.kt`), `:tools:cli` (`commands/ValidateCommand.kt`/`MigrateCommand.kt` — update to pattern-match the new sealed result instead of catching exceptions).

#### Migration required
None (this is an in-memory API shape, not a document format change).

#### Risk
Low. This is an API-surface clarification made before `:engine:serialization` is implemented (Phase 3, §32) — cheapest possible time to fix.

#### Implementation constraint
Each `DecodeFailureReason` case needs at least one test fixture (truncated JSON, valid-JSON-wrong-shape, future `schemaVersion`, a migration that throws) exercising the corresponding branch — add these to the existing migration test suite (§19.5, §28.1).

---

## 17. Android/Desktop Boundaries

§21 is correctly structured (`commonMain`-first, narrow `expect/actual` allow-list, Compose confined to two modules). Beyond C1 (removing iOS/wasmJs targets, §3 above), the narrower scope enables one concrete simplification:

### AD1 — Replace the `nonWebMain`/kotlinx-io file-storage abstraction with a plain shared-JVM source set

#### Change
Since the *only* two targets are `android()` and `jvm("desktop")` — both of which compile to the JVM — implement `DocumentStorage`/`FileSink` (§18.3, §33.3) directly against `java.nio.file` in a single intermediate Gradle source set shared by both targets (a standard KMP pattern: an intermediate source set, e.g. `jvmCommon`, with `androidMain` and the desktop `jvm("desktop")` main source set both `dependsOn` it). Drop the `nonWebMain` source set and the `kotlinx-io` dependency for this purpose entirely.

#### Reason
§21.2/§21.3 reach for `kotlinx-io` and a `nonWebMain` intermediate source set specifically so that file storage still compiles for a hypothetical wasmJs target someday ("via `kotlinx-io`... or JVM-specific in `desktopMain`/`androidMain`" — §21.2 already shows awareness this is a contingency, not a necessity). With wasmJs removed from scope entirely (C1), this abstraction protects against nothing currently real: both actual targets are plain JVMs, so `java.nio.file.Files`/`java.io.File` work identically and natively for both, with one fewer external dependency (`kotlinx-io`) and one fewer intermediate source set to maintain. This is a direct, low-risk simplification unlocked specifically by the corrected platform scope — exactly the kind of "unnecessary `expect/actual` abstraction" §21.3 already says requires justification, and the justification (future wasmJs support) no longer applies within this plan's scope.

#### Existing behavior preserved
`DocumentStorage`'s public interface (§18.3) is unchanged — this only changes the *implementation* source set and its dependency, not any public API. `expect/actual` is not needed for this concern at all once both targets are JVM-based (the existing `expect/actual` allow-list, §21.3, keeps its other entries — back-press handling, default font resolution, benchmark clocks — untouched).

#### Impacted modules
`:engine:serialization` (`storage/DocumentStorage.kt` interface unchanged; `FileDocumentStorage.kt` implementation moves from a `nonWebMain` source set to a shared `jvmCommon` source set, §33.3), `build-logic` (`forge.kmp.library` — add the `jvmCommon` intermediate source set wiring), version catalog (remove `kotlinx-io` if it has no other use in the project).

#### Migration required
None if Phase 3 has not yet been implemented (the normal case per the phase plan, §32). **If `:engine:serialization`'s file storage has already been implemented against `kotlinx-io`/`nonWebMain`,** this becomes optional cleanup rather than a required change — the existing implementation continues to work correctly on both Android and Desktop/JVM (`kotlinx-io` runs fine on the JVM); do not force a rewrite of working, tested code purely for this simplification.

#### Risk
Low either way: adopting it pre-implementation is strictly simpler; declining it post-implementation costs nothing but one unnecessary dependency.

#### Implementation constraint
If adopted, confirm the KMP Gradle DSL pattern for a custom intermediate source set shared between `android()`'s main and `jvm("desktop")`'s main is stable in the Kotlin Gradle Plugin version selected at kickoff (§22.3 already flags "verify `kotlinx-io` target coverage at kickoff" as an open item in §36.6 — this refinement simply changes what's being verified at kickoff, from "does kotlinx-io cover our targets" to "does the Kotlin Gradle Plugin's intermediate-source-set DSL work as expected for two JVM-based targets," which is the more established, lower-risk pattern of the two).

---

## 18. Performance

§29's targets and the explicit "not optimized prematurely" stance (§29.2) are both correct — **KEEP.** The one performance-relevant item this review adds is not a new finding but a **priority note** tying together two already-acknowledged facts: the MVP's full-reanalysis-per-edit cost (§29, deferred incremental analysis) and the task's emphasis that the visual editor will run primarily on Desktop with high-frequency mutations (§24 focus area). M2 (§8 above) already turns this into an explicit contract (debounce `Analyzer.analyze()` calls; `dispatch()` itself is cheap and does not trigger full analysis). No new performance work is proposed here — the existing §29.1 indexing/caching table already identifies the right post-MVP lever (analyzer memoization, incremental re-analysis) for when this actually becomes a bottleneck in practice.

---

## 19. Concurrency

§25.5 already states the core contract (`DocumentController` single-writer/not thread-safe by contract; `Analyzer`/`KotlinGenerator` pure and background-dispatcher-safe; preview renders an immutable `ResolvedDocument` snapshot). This section restates it decisively, as the task requires, and closes one small gap:

| Component | Thread-safety | Basis |
|---|---|---|
| `Schema`, `Registry<K,V>`, `TypeRegistry` | **Safe for concurrent reads** from any thread, after `Schema.build{}` returns. No mutation API exists post-build. | §8.2: "built once, then immutable." |
| `UiDocument`, `NodeTable`, `ResolvedDocument` | **Safe for concurrent reads** — immutable, persistent-collection-backed (ADR-007). | §5.5, §6.2. |
| `Analyzer.analyze(...)`, `KotlinGenerator.generate(...)` | **Pure functions, safe to call concurrently from multiple threads** with distinct (or even the same, immutable) inputs; safe on background dispatchers. | §25.5 (stated); reaffirmed here as the decisive answer to "can validation/codegen run off the UI thread" — **yes, by design, with no caveats.** |
| `UiRuntime`, `RendererRegistry`, `ModifierApplierRegistry` | **Safe for concurrent reads** — immutable after construction, same basis as `Schema`. | §8.3, §15.2. |
| `DocumentController`, `History` | **Not thread-safe; single-writer by contract.** Both are part of the same contract — `History` is owned by exactly one `DocumentController` and must not be driven from more than one caller/thread concurrently. | §25.5 (stated for the controller); extended here to make `History` explicitly part of the same contract rather than leaving it ambiguous. |
| `ActionExecutor` | Runs inside the composition's `rememberCoroutineScope()` — effectively single-threaded relative to the composition's dispatcher, cancelled on composition disposal (see A1, §11 above). | §11.5. |

**No locks are introduced anywhere by this review** — every answer above follows directly from the existing immutability design (ADR-007) plus one single-writer contract for the one genuinely mutable component (`DocumentController`), which matches the task's explicit instruction not to introduce locking where immutability already provides safety.

---

## 20. Testing and Invariants

§28's layered strategy (unit / golden / runtime / conformance / property-based / integration / architecture / benchmarks) is comprehensive — **KEEP in full.** New invariants introduced by this review's findings, to be added to the existing test catalog rather than as a separate testing system:

| New invariant | Covers | Test layer (existing, §28.1) |
|---|---|---|
| A document containing a component-declaration instantiation cycle is rejected with `component.cycle`, never crashes the runtime or compiles to stack-overflowing code | D1 | Unit (analysis) + one deliberately-cyclic fixture in the conformance fixture set, asserted to be *rejected before* reaching the runtime/codegen stage |
| A document containing a derived-state dependency cycle is rejected with `state.derived_cycle` | D2 | Unit (analysis), same pattern as above |
| A document using a reserved codegen infrastructure name (e.g. a `DataModel` named `Route`) is rejected with `name.reserved` before codegen runs | G1 | Unit (analysis/codegen feasibility) |
| A `Page` and a `ComponentDecl` sharing a simple name generate and compile without ambiguity | G2 | Golden + conformance (existing §28.2/§28.4 machinery, one new fixture) |
| `transaction {}` is atomic: for any sequence of commands containing at least one illegal command, the document is unchanged after the transaction returns `RolledBack` | M1 | Property-based (`:engine:editing`, same style as the existing patch/inverse property test, §28.1) |
| `DocumentCodec.decode` never throws for malformed/truncated/non-envelope/future-version input; it always returns a `DecodeResult` | SR1 | Unit (serialization), fuzz-style: feed random byte garbage and assert no exception escapes, only `Failure` results |
| `UiRuntime` and `KotlinGenerator` constructed for the same conformance fixture always share an equal `Schema.fingerprint` | R1 | Integration (`:integration:generated-compile`, alongside the existing conformance suite, §28.4) |

These are additions to the existing catalogs in §28.1–§28.4, not a new testing layer — they belong in the same modules and run under the same CI jobs already defined in §28.7.

---

## 21. API Stability

§24.1's three-tier model (Stable / Experimental / Internal) is sound — **KEEP.** One addition:

### AS1 — `DiagnosticCode` values are a Stable-tier contract, not merely data inside a Stable type

#### Change
State explicitly in §24.1 that individual `DiagnosticCode` catalog entries (§17.3's table — `struct.duplicate_id`, `prop.type_mismatch`, etc., plus the new codes added by this review) are themselves governed by the Stable tier's semver policy: a code, once shipped, is never repurposed for a different meaning and is never silently removed; retiring one requires a deprecation window (continuing to emit it, or emitting both old and new codes together) spanning at least one minor version, consistent with how `Diagnostic` the *type* is already Stable-tier (§24.1's table already lists `Diagnostic` itself as Stable).

#### Reason
§24.1 correctly marks the `Diagnostic` *type* as Stable, but a code catalog is a different kind of contract than a type signature — it's closer to an error-code enum in a compiler or an HTTP status registry, where external tooling (CI integrations, IDE plugins, the eventual editor's inline-error UI) is expected to pattern-match on the *string values*, not just deserialize the *shape*. Nothing in §17.3 or §24 currently says these strings carry the same stability guarantee as the Kotlin types that contain them, which is exactly the kind of "unclear API stability boundary" the task's §30 focus area asks to be resolved decisively.

#### Existing behavior preserved
No code or type changes — this is a policy clarification for an already-Stable-tier type.

#### Impacted modules
Documentation only (§17.3's table header, §24.1).

#### Migration required
None.

#### Risk
None — this only makes an already-sensible expectation explicit.

#### Implementation constraint
None.

---

## 22. Dependency Hygiene

§23's dependency matrix and forbidden-dependency list (§23.3) plus Konsist/module-graph enforcement (§23.4) are exactly right and require no change. The one dependency-hygiene-flavored finding from this review — that `Analyzer`'s schema and `UiRuntime`'s schema can diverge *by convention rather than by enforced contract* (R1, §5 above) — is a parity/consistency issue, not a module-graph violation (both already depend on `:engine:schema` correctly; the gap is a missing *runtime value* check, not a missing or cyclic *module* dependency), so it is detailed in §5/§6 rather than here, to avoid double-counting it as two different kinds of problem. No other dependency-hygiene issues were found; AD1 (§17 above) *removes* a dependency (`kotlinx-io`) rather than adding one, which is a net hygiene improvement in the same direction §23 already pushes.

---

## 23. Required Changes

Consolidated list of every `CHANGE`/`REFINE` above, for implementation tracking. All are small, additive, and scoped to a phase that (per §32) has not yet started implementation unless noted.

| ID | Title | Phase affected | Type |
|---|---|---|---|
| C1 | Remove iOS/wasmJs canary targets from build + CI + DoD | Phase 0 | CHANGE |
| D1 | Component-declaration instantiation cycle detection | Phase 8 (task already named; now fully specified) | CHANGE |
| D2 | Derived-state dependency cycle detection (shared `GraphCycleCheck` with D1) | Phase 6 | CHANGE |
| R1 | `Schema.fingerprint` + runtime/codegen schema-identity check | Phase 2 / Phase 4 (walking skeleton) | REFINE |
| G1 | Reserve codegen infrastructure names (`name.reserved`) | Phase 7 (navigation/state strategies exist) | CHANGE |
| G2 | Clarify per-kind identifier-uniqueness scope + fixture | Phase 4 | REFINE (docs + 1 fixture) |
| M1 | Atomic `transaction {}` with typed `TransactionResult` | Phase 9 | REFINE |
| M2 | Document `dispatch()` vs. `Analyzer.analyze()` boundary + debounce guidance | Phase 9 / docs | REFINE (docs only) |
| V1 | Typed `ActionPath` in `DiagnosticLocation` | Phase 7 | REFINE |
| E1 | Specify element-type-variable unification algorithm | Phase 6 | REFINE (docs, pins existing spec) |
| A1 | Document action-sequence cancellation + non-transactional semantics | Phase 7 / docs | REFINE (docs only) |
| S1 | Clarify `Persistence` vs. rotation-survival semantics | Phase 6 / docs | REFINE (docs, confirms existing lean) |
| L1 | Add `verticalScroll`/`horizontalScroll` to MVP modifier set | Phase 5 | CHANGE (scope addition) |
| P1 | Generated `AppTheme` exposes `darkTheme` override parameter | Phase 8 | REFINE |
| SR1 | `DecodeResult` models hard decode failures explicitly | Phase 3 | REFINE |
| AD1 | Replace `nonWebMain`/kotlinx-io with shared-JVM source set | Phase 3 | REFINE (optional if already implemented) |
| AS1 | `DiagnosticCode` catalog is Stable-tier, semver-governed | Docs | REFINE (docs only) |

*(M3 "finer-grained modifier patch ops" and the explicit `WrapNode`/`UnwrapNode` composite-op specification are listed in §24 as deferred/confirm-only items, not required changes.)*

---

## 24. Deferred Changes

| Item | Why deferred |
|---|---|
| Finer-grained modifier patch ops (`InsertModifierAt`/`RemoveModifierAt` vs. whole-list `SetModifiers`) | Not needed for MVP correctness (no collaborative editing, §3 non-goals); whole-list replacement is simpler and sufficient until multi-user merge semantics are ever in scope. Revisit only if/when collaboration (already noted as a future extension point via `PatchOp`'s shape, ADR-013) is actually pursued. |
| Explicit composite-op specification for `WrapNode`/`UnwrapNode` (document the exact `InsertNodes` + `MoveNode` sequence, and the dual-slot legality check — old parent accepts wrapper type, wrapper's target slot accepts original's type) | Genuinely useful to pin down before Phase 9 implementation, but low risk to defer to that phase's design step since `CommandPlanner`'s existing legality-check mechanism (§25.3) already generalizes to this case without any new abstraction — it only needs to be *exercised* for `WrapNode`/`UnwrapNode` specifically with a documented note that it must check both ends, not one. Tracked here so it isn't lost before Phase 9 begins, without blocking anything now. |
| Minimal non-virtualized `core.Repeat` pulled into MVP wave 1 (P2, §6 above) | Explicitly re-confirmed as DEFER — adds a new mechanism (item-scope variables) to the phase whose job is proving the pipeline on the smallest surface (Phase 4). Correct to leave in wave 2 as already planned (§7.5). |
| `componentDefaults` theme-level resolution order question (component default vs. theme component-default precedence) | Already correctly deferred in `PLAN.md` itself (§14.1, §31.3); no new information from this review changes that call — recorded here only to confirm it was reviewed, not overlooked. |

---

## 25. Implementation Order

All items are positioned relative to the **existing, unchanged** phase plan (§32) — none require reordering any phase, since every change is additive within a phase that hasn't started or is a documentation-only clarification. Apply in this order, grouped by when they become relevant:

1. **Before Phase 0 starts:** C1 (drop iOS/wasmJs targets) — this changes what Phase 0's convention plugin sets up, so it must land first.
2. **During Phase 2 (Schema):** R1's `Schema.fingerprint` field — cheap to add while `Schema.kt` is first being written.
3. **During Phase 3 (Serialization):** SR1 (`DecodeResult` as sealed success/failure) and, where applicable, AD1 (shared-JVM file storage) — both are foundational to how `:engine:serialization`'s public surface looks from day one.
4. **During Phase 4 (Analysis + walking skeleton):** G2 (per-kind naming scope + fixture) — cheap to settle while `NamingPass` is first written; R1's cross-check wiring into the conformance harness (§28.4) belongs here too, since Phase 4 is where the conformance harness is first stood up.
5. **During Phase 5 (Component wave 1):** L1 (scroll modifiers) — add alongside the rest of the wave-1 modifier set.
6. **During Phase 6 (Expressions and state):** D2 (derived-state cycle check, introducing the shared `GraphCycleCheck` utility), E1 (element-type-variable spec), S1 (Persistence/rotation clarification).
7. **During Phase 7 (Events, actions, navigation):** G1 (reserved codegen names — needs `NavigationStrategy`/`StateStrategy` to exist first), V1 (`ActionPath`), A1 (cancellation semantics doc).
8. **During Phase 8 (Reusable components, themes, resources):** D1 (component-decl cycle check, reusing `GraphCycleCheck` from step 6), P1 (`AppTheme` dark-mode parameter).
9. **During Phase 9 (Editing API):** M1 (atomic `transaction {}`), the deferred `WrapNode`/`UnwrapNode` composite-op write-up (§24).
10. **Documentation-only, any time before Phase 10 (hardening/release):** M2, AS1.

---

## 26. Final Architectural Rules

1. `PLAN.md` remains authoritative; this document is a patch set against it, not a replacement.
2. No architectural rewrite without a demonstrated defect — every item above is additive or clarifying, not a redesign.
3. **Android and Desktop/JVM are the only supported build targets.** No Gradle target, CI job, or abstraction exists solely to anticipate iOS/wasm/JS; purity that protects future portability is enforced statically (Konsist), not by live cross-compilation.
4. The domain model (`:engine:model`, `:engine:schema`, `:engine:serialization`, `:engine:interpreter`, `:engine:analysis`, `:engine:editing`, `:engine:codegen`, `:engine:builtins`) remains Compose-independent, with zero exceptions.
5. Runtime and code generation consume the same `ResolvedDocument`, built from the same `Schema` instance — the latter is now a checkable invariant (`Schema.fingerprint`), not just a convention.
6. Registries are deterministic, immutable after `Schema.build{}`, and never silently overridden; "override" means "register a new type under a new namespace," never "redefine an existing one."
7. Every static reference graph the model allows (node parent/child, component-declaration instantiation, derived-state dependencies, and any future graph introduced by a later phase) must have an explicit acyclicity check in `:engine:analysis` before `ResolutionPass` runs. A new kind of reference in a future phase is not considered validated until its cycle behavior (if any) has been decided and, if cycles are structurally possible, checked.
8. Serialization is versioned, and `DocumentCodec.decode` never throws for bad input data — every decode outcome, including corruption, is a structured, catchable result.
9. Generated code is deterministic, and no engine-generated infrastructure identifier may silently collide with a document-declared name — reserved names are validated, not discovered at compile time.
10. Editor mutations go through `DocumentController`/`Command` only; multi-command `transaction {}` blocks are atomic — no partially-applied transaction is ever observable or enters history.
11. Public APIs have explicit stability tiers, and a `DiagnosticCode` value is part of that contract exactly as much as the `Diagnostic` type that carries it.
12. New abstractions require a concrete use case. Nothing in this document adds a new Gradle module, a new registry, a new plugin facet, or a new expression/action construct — every change reuses an existing mechanism (an existing pass, an existing `ModifierSpec`/`CodegenBinding` shape, an existing `PatchOp`/`Command` pattern) or adds the smallest new primitive that closes a concretely demonstrated gap (one shared cycle-detection utility; one sealed decode-result type; one fingerprint field).
