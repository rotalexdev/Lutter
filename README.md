# Forge Engine

A **UI document engine** for Kotlin Multiplatform and Compose Multiplatform.

One authoritative, serializable, versioned UI document goes in. A typed
`ResolvedDocument` comes out, and from that single input the engine either renders the
document live in Compose or generates deterministic Kotlin/Compose source from it. The
design is specified in [`PLAN.md`](PLAN.md); this repository implements it phase by phase
and is currently in the middle of the first one that contains domain code.

It is not a visual editor and not an IDE (PLAN §1, §3). The editor is a *future client* of
this engine; what lives here is the part it will consume.

## What goes in and what comes out

```
              ┌───────────────┐      ┌──────────┐      ┌───────────────────┐
              │  UiDocument   │ ───▶ │ Analyzer │ ───▶ │ ResolvedDocument  │
              │ pure records  │      │ 8 passes │      │ typed, defaults   │
              └───────────────┘      └──────────┘      └─────────┬─────────┘
                                 Diagnostics                      │
                                         ┌───────────────────────┴──────────────┐
                                         ▼                                      ▼
                                 ┌───────────────┐                    ┌────────────────┐
                                 │  Compose      │                    │ Kotlin source  │
                                 │  runtime      │                    │ files          │
                                 └───────────────┘                    └────────────────┘
```

Three decisions in that diagram carry most of the weight, and each of them was a rejection
of the obvious alternative (PLAN §4, §5.6, §34).

**The document is a normalized record set, not a nested tree.** There is one global
`Map<NodeId, Node>` for the whole document; nodes hold ordered lists of *child ids*, and
pages and component declarations hold a root id. A nested `children: List<UiNode>` is
easier to read on paper and far more expensive to work with: every edit copies the path to
the root, moving a node between parents is a delete plus an insert, lookup is a walk, and
references and selections have nothing stable to point at. The normalized form gives O(1)
lookup, O(depth) structural sharing, cheap moves and patches, and a diff that compares ids.
The price is real and stated in the plan: the invariants (single parent, no cycles, no
orphans) have to be validated, and a parent index has to exist. ADR-001, PLAN §5.6.

**One lowering pipeline, two backends.** Defaults, unknown-property handling, slot
normalization, modifier order, theme-token resolution, scope computation and expression
typing all happen once, in the analyzer. Neither backend re-implements any of it, and both
read only `ResolvedDocument`. That is what makes runtime-versus-generated parity structural
rather than a coincidence two teams have to maintain. PLAN §4.5, ADR-015.

**A component is split by capability, not defined in one place.** `ComponentSpec` is data:
the props, slots, events and the declarative Kotlin/Compose mapping. `ComponentRenderer` is
Compose and lives in `:engine:runtime`. The codegen mapping is a `CodegenBinding` *inside*
the spec, interpreted by a generic emitter. The obvious design — one `ComponentDefinition`
with `createRuntime()` and `generateCode()` — would force the schema to depend on both
Compose and the code generator, and the dependency direction does not allow that. The
accepted cost is that a renderer and a binding *can* drift apart, which is why coverage
checks and the conformance suite exist. PLAN §4.2, ADR-003.

## State of the project

**Phase 0 is merged. Phase 1 is in progress, with two of its nine work units merged.**
This is a greenfield project under construction, not a library anyone can depend on yet.

| | |
|---|---|
| Merged | Phase 0 in full: build logic, 15 module shells, the architecture guardrails, CI. Then Phase 1 T1, typed identifiers with validating constructors (`#12`), and T2, canonical numerics (`#13`). T3 through T9 — the `Value` union, `TypeRef`, the `Expr` AST, `Node`, the document records, `NodeTable` and the DSL — are not started. |
| Version | `0.1.0-SNAPSHOT`, group `dev.rotalex.lutter`, Gradle root project `forge-engine`. Never published — no publishing plugin is applied. |
| Releases | None. |
| Documentation | This file, and `PLAN.md`. The format spec, plugin guide and getting-started guide are Phase 10 and do not exist. |

| Module | What is actually in it |
|---|---|
| `:engine:model` | The identifier layer (18 `@JvmInline value class` identifiers with validating constructors, `IdSyntax`, `IdGenerator` with a sequential variant for fixtures), `SchemaVersion`, `EngineInternalApi`, and canonical numerics with their serializers. About 1400 lines including tests. |
| `:engine:test-support` | The `Golden` reader and a `GoldenSource` seam. No fixtures yet. |
| `:tools:cli` | A `main()` that prints its own stub and exits 0. No subcommand is implemented; `validate`, `generate` and `diff` arrive in Phase 4. |
| `:tools:architecture-tests` | Four working rules over `java.nio.file`. |
| everything else | A shell: one file holding a package declaration and a comment saying what the module will contain. |

What `:engine:model` does **not** have yet, and what the rest of the engine is blocked on:
`Value`, `TypeRef`, `PropertyValue`, `Expr`, `ActionSequence`, `Node`, `NodeTable`,
`UiDocument`, `DocumentIndex`, `ReferenceIndex` and the DSL. The plan is explicit that there
is no partial version of this module another module can be written against — either the
vocabulary is closed, or `:engine:codegen` waits (PLAN §32, Phase 1).

Consequently, none of this exists yet: document decoding, canonical JSON, the envelope, the
migration chain, the analyzer and its diagnostics, `ResolvedDocument`, any Compose
renderer, the code generator, the editing API, any real `forge` subcommand, and a runnable
sample application. The `conformance` CI job is wired to a module that is still a shell.

## What it requires

### The toolchain policy: no local toolchain, on purpose

**This workspace is source-only. No Gradle, Kotlin, JDK or Android SDK binary is ever
downloaded here.** The toolchain is provisioned by GitHub Actions, and the same rule is
restated at the top of `gradle.properties` so it is visible from the file where someone
reaches for a tool.

That is a deliberate decision, not an oversight. It has hard consequences:

- `gradle/wrapper/gradle-wrapper.jar` is **not committed**, because it is a binary we are
  not allowed to fetch. `gradle/wrapper/gradle-wrapper.properties` *is* committed, so the
  intended Gradle version stays pinned and reviewable.
- Do not run `gradle`, `./gradlew`, `kotlinc` or `java` here. There is no wrapper JAR to
  run, and installing a toolchain violates the policy.
- CI installs Gradle with `gradle/actions/setup-gradle` and an explicit, quoted
  `gradle-version`, then invokes `gradle` rather than `./gradlew`. That is the action's
  documented "project doesn't use Gradle wrapper" path.
- **Nothing can be verified locally.** Builds, tests, ABI checks and architecture rules all
  run in CI. Every check in this repository is CI-PENDING until a CI run reports it. If you
  need a build, push the branch and read the result — a check is not passing because the
  code looks right.

If this policy is ever relaxed, run `gradle wrapper` once on a machine that has Gradle, to
generate the JAR, and rewrite this section. The policy is the reason this repository is
unusual, and it should not be changed quietly.

### Versions come from a shared catalog, not from this repository

**This repository owns no dependency versions.** They live in
`io.github.alexanderrotela20.catalog:version-catalog` (at `1.2.7`, as declared in
`settings.gradle.kts`), consumed there as `rootLibs`. Kotlin, AGP, Compose Multiplatform,
kotlinx-serialization, kotlinx-coroutines, the Android SDK levels and the JVM target are all
defined in that catalog, and every Rotalex project resolves the same numbers.

The catalog **must** be named `rootLibs`: the convention plugins in
`rotalex-root-conventions` look up their own internal dependencies and SDK versions through
that exact name.

`gradle/libs.versions.toml` is a one-entry gap-filler, not a second catalog. It carries only
the JUnit 5 BOM (5.14.0), which `rootLibs` 1.2.7 does not have, and it is scheduled for
deletion once that coordinate is added upstream. Anything that exists in `rootLibs` must
never be duplicated here.

Build settings come from the same catalog, read by name through the `VersionCatalogsExtension`
— a precompiled script plugin is compiled separately from version-catalog accessor
generation, so the generated `rootLibs` accessors do not resolve inside one:

| Setting | Catalog key | Meaning |
|---|---|---|
| `jvmTarget` | `rootLibs.versions.jvmTarget` | Java's `sourceCompatibility`/`targetCompatibility` **and** Kotlin's `jvmTarget` |
| `compileSdk` | `rootLibs.versions.androidCompileSdk` | `compileSdk` |
| `minSdk` | `rootLibs.versions.androidMinSdk` | `minSdk` |

Two more values are local to CI, and they are the only versions in this repository:

| Value | Where | Why |
|---|---|---|
| `gradle-version` (`9.5.0`) | `check.yml`, `conformance.yml` | The Gradle release CI uses. Top of the range Kotlin 2.4 documents support for. |
| `java-version` (`21`) | `check.yml`, `conformance.yml` | The JDK that *runs* Gradle, pinned with `setup-java`. Deliberately not a Gradle toolchain. |

**Why the second JDK number is a trap.** There is no `jvmToolchain` call anywhere in this
build, and there must not be one. `jvmTarget` from the catalog is applied to **both** Java's
`sourceCompatibility`/`targetCompatibility` and Kotlin's `jvmTarget`; the JVM tool modules
set Kotlin's side by hand for the same reason, because the shared JVM convention only sets
Java's. A toolchain is a second, independent JDK number, and a Java compilation with no
explicit target falls back to it. Two numbers is how you get:

```
Inconsistent JVM-target compatibility detected for tasks
'compileTestJava' (17) and 'compileTestKotlin' (21)
```

The toolchain supplies one, the catalog supplies the other, and only one of them gets
applied.

Bump Kotlin, AGP and Compose Multiplatform together in **that** repository and let the
version cascade here. One PR, full CI matrix.

> **Watch item, recorded not verified:** the shared catalog was last observed pairing Kotlin
> `2.4.0` with AGP `9.2.1`, while Kotlin's own compatibility guide documents `2.4.0` as
> supported up to AGP `9.1.0`. Those numbers live in a Maven artifact this repository cannot
> read, so treat them as a report rather than a measurement. It is a decision owned by the
> catalog anyway, and this repository does not override it. If CI reports a version-matrix
> symptom, the fix belongs in `rotalex-root-conventions`.

> **Not currently enforced:** `forge.warningsAsErrors` is a Gradle property the convention
> plugin reads to append `-Werror` to the Kotlin compiler arguments of every module. It
> defaults to `false` in `gradle.properties`, and no workflow currently passes
> `-Pforge.warningsAsErrors=true`, so warnings are not build failures on CI. `PLAN.md` §28.7
> and §36.5 assume they are; that is a gap, not a guarantee.

### Targets

Exactly two: **Android and Desktop (JVM)**. No Kotlin/Native, no JS, no Wasm.

`commonMain` purity — no JVM type and no Compose import reaching it — is a **static** rule
and not a compiler guarantee. That is a change of kind, not of degree: with only JVM-family
targets, no compiler rejects a JVM type in `commonMain` at all, so the check is static. Two
tests in `:tools:architecture-tests` carry the weight, and the second exists because the first
would not have caught the case that actually shipped:

- `NoPlatformApisInCommonMainTest` bans a `java.`, `javax.` or `android.` **import** in any
  `commonMain`, in any module.
- `NoJvmOnlyCollectionMembersTest` bans the collection members that reach `commonMain`
  through JVM interop with **no import at all** — `putIfAbsent`, `computeIfAbsent`, `merge`,
  `removeIf` and the rest of `java.util.Map`'s Java 8 defaults, plus `forEach` in the two-
  argument `BiConsumer` shape that Kotlin's common `Map.forEach` cannot have.

That second rule is not speculative. `owners.putIfAbsent(name, page.name)` in
`ReservedCodegenNames.kt` compiled for Desktop, imported nothing, and failed only on
`:engine:codegen:compileKotlinWasmJs`. It is now `getOrPut`, and the rule is what keeps it
that way. `NoComposeInPureModulesTest` holds the Compose half of the same promise for the
eight pure modules.

Building needs network access to Maven Central and Google's repository.
`settings.gradle.kts` still declares the Node and Yarn distribution repositories, which the
Kotlin plugin registers per-project for the Wasm and JS targets; no target needs them now, and
they are left in place rather than removed on a guess, because an unused ivy block is inert
and a wrong removal is a resolution failure.

### To contribute

```bash
scripts/new-branch.sh feat your-change      # always from dev
git push -u origin feat/your-change
gh pr create --base dev --head feat/your-change \
             --title "feat(model): <what the change does>"
```

Nothing is ever pushed directly to `main` or `dev`; both are protected and both require a
pull request. The full branching contract is under [Branching](#branching) below. The short
version: the branch carries only the conventional-commit type, the PR title is a strict
conventional commit, and three checks have to pass.

## What it cannot do yet

This is the part worth reading. Two kinds of limitation are listed here: what the project
has decided not to do, and what it has decided to do and has not built yet.

### Not in scope by design (PLAN §3)

| | |
|---|---|
| **Not an IDE** | No code editor, debugger, terminal, LSP or project explorer. |
| **No visual editor** | None in this plan, only the editing API a future one will consume (§25). It is Phase 18, in a separate repository. |
| **No arbitrary code execution** | No embedded Kotlin, no general-purpose scripting. Expressions are a small, typed language with a closed operator set (§10), and custom logic goes through declared host functions. |
| **No round trip** | The engine never parses generated Kotlin back into a document. Generated code is a one-way artifact (ADR-011), so hand edits to a generated file are lost on the next regeneration. |
| **No plugin marketplace** | No dynamic plugin loading in the MVP. Plugins are static contributions composed in code, and third parties recompile the host (ADR-010). |
| **No FlutterFlow parity** | No backend integrations, no auth, no database designer, no asset pipeline. |
| **No complete Compose coverage** | The MVP ships a deliberately small component set that proves every mechanism, not a component library. |
| **No project scaffolding** | No generated `settings.gradle.kts`, no project templates. Designed, then postponed to post-MVP (Phase 14). |
| **No collaborative editing** | No OT in the MVP. The patch design keeps the door open (§26, ADR-013). |

And from the MVP scope itself (§31.3), explicitly postponed: `LazyColumn` iteration scopes,
persistent and external state, two-way binding sugar, component-level events and overrides,
`componentDefaults` in themes, Navigation Compose and Navigation 3 strategies, CBOR,
incremental analysis, an expression *text* parser, image and font resources, localization
beyond plain string resources, iOS/Wasm *runtime* support, and collaboration.

### Not built yet

Everything above the model. No document can be decoded, nothing can be validated, there is
no `ResolvedDocument` to render or generate from, and the sample application is a comment
file rather than an app. The phase plan is in PLAN §32: Phase 0 through Phase 10 plus a
post-MVP roadmap, and the project is in Phase 1.

Two specific gaps in the guardrails themselves, since a guardrail you assume exists is
worse than one you know is missing:

- PLAN §23.4 nominates seven architecture rules. **All seven are implemented**, plus two more
  that the plan did not anticipate: `NoIllegalJvmNameTest` (a backticked function name the JVM
  backend rejects, which cost this repository four CI round-trips) and
  `NoJvmOnlyCollectionMembersTest` (a `java.util` member reached through interop with no
  import, which is the defect the Wasm canary used to be the only thing to catch). The rules
  live in `:tools:architecture-tests` and run inside the root `gradle check`.
- PLAN §21.1 asks the pure modules to declare `iosSimulatorArm64` and `wasmJs` canary
  targets, and §28.7 lists a `canary` job and a `nightly` job. **None of the four exists.**
  The Wasm canary compile was real until it was removed; `commonMain` purity is now enforced
  statically by `NoPlatformApisInCommonMainTest` and `NoJvmOnlyCollectionMembersTest` rather
  than by a compiler that rejected JVM types. The fuzz-compile batch of §28.5 and the
  benchmark baselines of §29 still have nowhere to run.
- PLAN §36.5 asks for `allWarningsAsErrors` in CI. See the note under
  [Versions](#versions-come-from-a-shared-catalog-not-from-this-repository): the property
  exists, defaults to `false`, and no workflow sets it.

### Trade-offs accepted knowingly

These are decisions, not oversights. Each one buys something specific and pays for it
something specific.

| Decision | What it buys | What it costs |
|---|---|---|
| Normalized node table, not a nested tree (ADR-001) | O(1) lookup, cheap moves and patches, stable identity, diffable | Invariants must be validated; a parent index is required; JSON is flat and sorted by id |
| Canonical numbers: ≤4 fractional digits, integer-arithmetic formatting, magnitude bounded at `2.0e11` (D1, ADR-016) | One spelling per number, identical on every target and across JDK versions | Four decimals of precision; anything below `0.00005` rounds to `0`; a large value is refused rather than printed approximately |
| Schema-free decoding: a `Value` decodes without consulting the schema (D4) | Documents from a newer plugin round-trip losslessly | Verbose JSON; the decoder cannot validate anything it does not recognize |
| `ResolvedDocument` as the single input to both backends (ADR-015) | Runtime and generated code agree by construction | An extra derived structure in memory and time |
| Codegen and runtime may not see each other (ADR-003) | Neither backend can be built on the other | Renderer and binding *can* drift; coverage checks and the conformance suite are the only things holding them together |
| No `/` and no `%` in the expression language (D10) | No integer-division or overflow parity problem between the interpreter and the generated Kotlin | Genuinely less expressive: dividing requires a host function or an explicit `num.toDouble` |
| Actions are data, not lambdas; no two-way binding (ADR-009) | Serializable, translatable to Kotlin, explicit data flow | Verbose on text fields: a value reference plus a `state.set` action, which an editor has to hide |
| A hand-written Kotlin IR and printer instead of KotlinPoet (ADR-004) | Codegen runs in `commonMain`, so it works from the CLI, a server and a browser | The project owns ~1–2k lines of formatting and import logic, held in place by golden tests |
| Architecture rules are text scanners, not PSI queries | They cannot be fooled by formatting, and they cannot fail to compile | They are defeated by an unbalanced brace inside a raw string or a block comment |

That last one is worth expanding, because the reasoning is the useful part. PLAN §23.4
nominated Konsist and it was implemented with it first. `Konsist.scopeFromDirectory` refuses
any path outside the project it detects, and the test JVM's project is
`tools/architecture-tests`, so every scan of `engine/…` died with an
`IllegalArgumentException` before a single rule ran. It was also never doing the work: the
rules were already the text scanners, and Konsist only supplied file names, text and
imports. **A rule that cannot see the code it governs is worse than no rule, because it
looks like coverage.** Konsist is gone; JUnit's own assertions are enough. Its last release
also predates the Kotlin version this build uses. If a future rule genuinely needs a syntax
tree, that is when Konsist belongs — and the test task's working directory is the thing to
fix first.

## Modules

Base package `dev.rotalex.lutter.*`. The graph below is not a convention: it is
machine-enforced by `verifyModuleGraph` from a hard-coded allow-list (PLAN §23.2, §23.3).

```
:engine:model            ── records: ids, Value, TypeRef, Expr, Node, UiDocument
:engine:schema           ── model            specs, registries, ValueKinds, overlay
:engine:serialization    ── model            envelope, canonical JSON, migrations
:engine:interpreter      ── model, schema    evaluator, action executor, state store
:engine:analysis         ── model, schema    analyzer, diagnostics, ResolvedDocument
:engine:editing          ── model, schema    controller, commands, patches, history
:engine:codegen          ── model, schema, analysis    Kotlin IR + deterministic printer
:engine:runtime          ── model, schema, interpreter, analysis   UiRuntime (Compose)
:engine:builtins         ── model, schema, interpreter   core + Material3 specs, no Compose
:engine:builtins-compose ── runtime, builtins           renderers (Compose)
:engine:test-support     ── all              golden helper, fixtures, test schema
:tools:cli               ── model, serialization, analysis, codegen, builtins   `forge` CLI
:tools:architecture-tests ── (none)          architecture rules
:integration:generated-compile               compiles generated fixtures
:samples:desktop-preview                     renders a JSON document — `gradle :samples:desktop-preview:run`
```

Read the absences; they carry the architecture. `:engine:model` reaches nothing at all.
`:engine:serialization` may not see `:engine:schema`, because migrations have to outlive
schema changes. `:engine:codegen` and `:engine:runtime` may not see each other in either
direction. `:engine:analysis` may not see `:engine:interpreter`, so type checking never
depends on a value that only exists at runtime.

Three module classes, per PLAN §21.1:

- **Pure** — `model`, `schema`, `serialization`, `interpreter`, `analysis`, `editing`,
  `codegen`, `builtins`. `commonMain` only. No Compose, no JVM APIs, enforced by
  `NoComposeInPureModulesTest`, `NoPlatformApisInCommonMainTest` and
  `NoJvmOnlyCollectionMembersTest`.
- **Compose** — `runtime`, `builtins-compose`. `commonMain`, because Compose Multiplatform
  is common code.
- **Tools** — `cli`, `architecture-tests`. JVM only.

## Guardrails

| Guardrail | What it blocks | Where |
|---|---|---|
| `verifyModuleGraph` | Any project dependency outside the allow-list | root task |
| `selfTestModuleGraph` | A regression in the checker itself | root task |
| `NoComposeInPureModulesTest` | Compose imports in a pure engine module | `:tools:architecture-tests` |
| `NoPlatformApisInCommonMainTest` | `java.*` / `android.*` imported in any `commonMain` | `:tools:architecture-tests` |
| `NoJvmOnlyCollectionMembersTest` | `putIfAbsent`, `computeIfAbsent`, `merge` and the other JVM-only collection members reached through interop with no import | `:tools:architecture-tests` |
| `NoUntypedStringMapTest` | `Map<String, Any>` in engine sources | `:tools:architecture-tests` |
| `NoMutableObjectStateTest` | An `object` declaring a `var` | `:tools:architecture-tests` |
| `explicitApi()` | An undeclared public API surface | every KMP library module |
| `checkKotlinAbi` | A binary-incompatible public API change | every KMP library module |

`verifyModuleGraph` and `selfTestModuleGraph` are tasks on the root project, and root
`check` depends on the first, so `gradle check` covers the graph. The architecture rules are
plain JUnit tests in `:tools:architecture-tests` and run inside `check` like everything
else. The graph checker reads *declared* project dependencies across ten Gradle
configurations — `commonMain*` and `commonTest*` for the KMP modules, `api`,
`implementation`, `testImplementation` and friends for the two JVM tools — and never
resolves them, so checking a name never downloads anything. The source rules find the
repository root by walking up to the first ancestor containing `settings.gradle.kts`, and
read files with `java.nio.file`; `SourceRules.kt` holds the shared scanners and states its
own limitations.

ABI reference dumps are committed under `*/api/`, one per KMP library module — thirteen of
them. The two JVM tool modules have none: `abiValidation()` is applied by the KMP library
convention, and those modules are not KMP libraries.

## CI

Three required checks on `dev` and `main`, plus one workflow that is deliberately not one.

| Workflow | Required | Triggers | What it actually runs |
|---|---|---|---|
| `check` | yes | push to `dev`, any PR into `dev` or `main` | The convention plugins' own `:convention:check`; then `gradle check verifyModuleGraph checkKotlinAbi`; then `selfTestModuleGraph`, which runs even on an already-failing run so the checker is never only proven on green. On failure it regenerates and commits missing ABI reference dumps, and refuses to auto-commit from a fork. |
| `conformance` | yes | push to `dev`, any PR into `dev` or `main` | `:integration:generated-compile:desktopTest`. The module is a shell, so this is currently a job proving its own wiring. It exists from Phase 0 on purpose: a CI job discovered to be misconfigured the day it first has real work is a job that reports a false problem about the code under test. |
| `branch-policy` | yes | any PR into `dev` or `main` | That the branch name is `<type>/<slug>` with no version in it, and that the PR title is a strict conventional commit, at most 100 characters, not ending in a period. |
| `preview-artifacts` | **no** | only when a path that can change a pixel or a binary changes, on a PR into `dev` or `main`, on push to `dev`, or on demand | `:samples:desktop-preview:desktopTest` and `:samples:desktop-preview:packageDistributionForCurrentOS`, on both `ubuntu-latest` and `windows-latest`. It uploads the rendered screenshots and the packaged binary per OS. |

`preview-artifacts` is not a gate and should not be made one. `check` answers "is this branch
sound?", and discards everything it builds; this one answers a question a green tick cannot —
*what does it draw, and can I run it?* — by leaving the answer as a downloadable artifact.
Required status checks are branch-protection settings on GitHub's side rather than anything the
repository declares, so this workflow becoming merge-blocking would take a deliberate change
there. What it can do is cost minutes, which is what its `paths:` filter is for: it skips any
change to a module that cannot alter a rendered pixel or a packaged binary.

Both artifacts are downloaded from the run page's **Artifacts** section at the bottom of the run.
`preview-screenshots-<os>` holds the PNGs of the rendered UI, and `preview-binaries-<os>` holds
the packaged application, with two shapes inside it:

- `msi/LutterPreview-1.0.0.msi` (Windows) or `deb/lutterpreview_1.0.0_amd64.deb` (Linux) — the
  installer. This is the one to run; it puts the application where the platform expects it.
- `app/LutterPreview/` — the app-image, if you would rather not install anything.

The app-image is a folder that only works as a whole: `LutterPreview.exe` (or `bin/LutterPreview`
on Linux) beside an `app/` directory holding `LutterPreview.cfg` and every jar, and a `runtime/`
directory holding the bundled JVM. Copying the executable out on its own gives you
"Failed to launch JVM", because the launcher finds neither. **If Windows Defender quarantines a
file out of `runtime/bin/`, the same error appears** — these are unsigned binaries extracted
from a download, which is exactly the shape Defender is trained to stop, and the fix is to allow
the folder rather than to rebuild it.

`dependabot` is scoped to the GitHub Actions this repository owns and targets `dev`. It
cannot manage the Gradle dependency versions: those live inside a Maven artifact, so a
`gradle` ecosystem entry here would only open pull requests that fight the catalog.

## Branching

| Branch | Role |
|---|---|
| `main` | Protected. The stable line. Receives merges from `dev` only, through a pull request |
| `dev` | Protected. The integration line. Every change lands here, through a pull request |
| `<type>/<slug>` | Working branch. Each opens a pull request against `dev` |

Nothing is ever pushed directly to `main` or `dev`. Both are protected against direct
pushes, force-pushes and deletion, and both require a pull request — so the rule is enforced
by the repository rather than by good intentions.

**A branch carries only the conventional-commit type. No versions, no semver.** A branch is
a place to do one change; it is not a release record.

```
feat/foundation     fix/node-distribution     chore/ci-modernise     docs/format-spec
^^^^ ^^^^
|    |
|    short, lowercase, dashes
|
conventional-commit type
```

**A pull request title into `dev` is a strict conventional commit**, and that is what the
title rule checks — the title does **not** have to match the branch name. `fix/node-distribution`
opening `fix(build): declare the Node and Yarn distributions in settings` is correct: the
branch says which area was touched, the title says what the change does.

```
<type>[(scope)][!]: <description>

feat(build): project foundation on the shared Rotalex catalog
fix(build): declare the Node and Yarn distributions in settings
feat(runtime)!: drop the legacy renderer registry
chore: bump the shared catalog to 1.2.8
```

Types: `feat` `fix` `chore` `refactor` `perf` `docs` `test` `build` `ci` `style` `revert`.
The title is limited to 100 characters and must not end in a period.

Both rules are enforced twice, deliberately. `scripts/new-branch.sh` refuses to cut a
non-conforming name, so the mistake is caught before a push; and `branch-policy` is a
required check, so the rule is the repository's rather than a convention that decays.

### The branch lifecycle

```bash
# 1. cut the next branch, always from dev
scripts/new-branch.sh feat foundation          #  ->  feat/foundation

# 2. work there. Never on dev, never on main.
git push -u origin feat/foundation

# 3. open the PR into dev, with a conventional-commit title
gh pr create --base dev --head feat/foundation \
             --title "feat(build): project foundation on the shared Rotalex catalog"

# 4. CI goes green, the PR merges, and GitHub deletes the branch automatically
#    (delete_branch_on_merge is on for this repository)

# 5. cut the next one
scripts/new-branch.sh fix node-distribution
```

A branch is disposable. It exists for one change, it is deleted when that change lands, and
the next one starts from a fresh `dev`. Nothing accumulates on a long-lived feature branch,
so there is never a merge-base that has drifted.

## Layout

```
build-logic/            included build holding the convention plugins
engine/<name>/          KMP library modules
tools/<name>/           JVM tools
integration/            cross-module verification
samples/                runnable demos
docs/                   does not exist yet; format spec, plugin guide, getting started (Phase 10)
.github/ci-gradle.properties   properties CI overlays on ~/.gradle/gradle.properties
```

`odd/` is present in a working tree and **absent from the repository**. It holds the Organic
Driven Development documents — one per feature, with its objective, work units, verification
evidence and next step. It is a process artifact, not a product one, so it changes on every
work unit and committing it would put churn in diffs that are supposed to describe the
engine. The authoritative copy is the Engram observation at `odd/<feature>/tasks`, which
carries the full document and outlives both a session and a compaction. A *why* is not lost
by this: it belongs in KDoc, next to the code it explains, where it cannot drift.

## Where the reasoning lives

`PLAN.md` is the specification — 2575 lines, and the authority for anything this file claims
about the design. The sections cited above are the ones worth reading first: §1 for what the
project is, §3 for what it is not, §4 for the architecture and the difficult decisions,
§5.6 for the normalized-record trade-off, §21 for targets, §23 for the dependency graph and
its enforcement, §31 for MVP scope, §32 for the phase plan, §34 for the seventeen ADRs, and
§35 for the anti-patterns with the guard that catches each one.

The worked example of how this project explains a decision to itself is
`engine/model/src/commonMain/kotlin/dev/rotalex/lutter/model/value/CanonicalNumbers.kt`.
It answers the question "why not `toString()`" in about seventy lines, including the JDK 19
algorithm change, why `log10` is not an option, and why the magnitude bound is `2.0e11` and
not `Long.MAX_VALUE / 10^4`. That file is the standard: if a decision in this repository
cannot be explained at that level, the decision is not finished.
