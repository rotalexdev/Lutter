# Forge Engine

A **UI document engine** for Kotlin Multiplatform and Compose Multiplatform.

One authoritative, serializable, versioned UI document goes in. A typed
`ResolvedDocument` comes out, and from that single input the engine either renders
the document live in Compose or generates deterministic Kotlin/Compose source from
it. The design is specified in [`PLAN.md`](PLAN.md); this repository implements it
phase by phase.

Current state: **Phase 0 — project foundation.** Module shells, build logic,
architecture guardrails and CI. No domain code yet.

## Toolchain policy — read this first

**This workspace is source-only. No Gradle, Kotlin or JDK binary is ever downloaded
here.** The toolchain is provisioned by GitHub Actions.

That is a deliberate decision, not an oversight. It has hard consequences:

- `gradle/wrapper/gradle-wrapper.jar` is **not committed**, because it is a binary we
  are not allowed to fetch. `gradle/wrapper/gradle-wrapper.properties` *is* committed,
  so the Gradle version stays pinned and reviewable.
- Do not run `./gradlew` or `gradle` locally. There is no wrapper JAR to run and
  installing a toolchain here violates the policy.
- CI installs Gradle with `gradle/actions/setup-gradle` and an explicit, quoted
  `gradle-version` input, then invokes `gradle` rather than `./gradlew`. This is the
  action's documented "project doesn't use Gradle wrapper" path.
- **Nothing can be verified locally.** Builds, tests, ABI checks and architecture
  rules all run in CI. If you need a build, push the branch and read the CI result —
  do not report a check as passing because it looks right.

If you later decide a local toolchain is acceptable, run `gradle wrapper` once on a
machine that has Gradle to generate the wrapper JAR, and update this section.

## Version matrix

Every version lives in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). No
module build file may hard-code a version.

| Component | Version | Why this one |
|---|---|---|
| Kotlin | 2.4.10 | Current stable, and inside its own published support matrix |
| Compose Multiplatform | 1.12.1 | Latest stable |
| Compose compiler plugin | 2.4.10 | Must equal the Kotlin version — a different axis from CMP |
| Android Gradle Plugin | 9.1.0 | Top of Kotlin 2.4's documented AGP range |
| Gradle | 9.5.0 | Top of Kotlin 2.4's documented Gradle range |
| JDK | 21 | AGP 9 needs 17+; 21 is LTS |

Kotlin 2.4.0–2.4.10 is documented as compatible with Gradle 7.6.3–9.5.0 and AGP
8.5.2–9.1.0. Newer Kotlin and AGP releases exist; they are deliberately not used
because their support range is not published yet, and this scaffold is verified by CI
alone. Upgrade Kotlin and Compose Multiplatform together, one PR at a time, gated by
the full CI matrix.

## Modules

Base package `dev.rotalex.lutter.*`. The dependency graph below is not a convention —
it is machine-enforced by `verifyModuleGraph` from a hard-coded allow-list
(PLAN §23.2, §23.3).

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
:tools:cli               ── serialization, analysis, codegen, builtins   `forge` CLI
:tools:architecture-tests ── (none)          Konsist rules
:integration:generated-compile               compiles generated fixtures
:samples:desktop-preview                     renders a JSON document
```

Three module classes, per PLAN §21.1:

- **Pure** — `model`, `schema`, `serialization`, `interpreter`, `analysis`, `editing`,
  `codegen`, `builtins`. `commonMain` only. No Compose, no JVM APIs. These also
  declare `iosSimulatorArm64` and `wasmJs` **canary** targets so JVM leakage into the
  domain fails the build instead of surviving review.
- **Compose** — `runtime`, `builtins-compose`. `commonMain`, because Compose
  Multiplatform is common code.
- **Tools** — `cli`, `architecture-tests`. JVM only.

## Guardrails

| Guardrail | What it blocks | Where |
|---|---|---|
| `verifyModuleGraph` | Any project dependency outside the allow-list | root task |
| `selfTestModuleGraph` | A regression in the checker itself | root task |
| Konsist rules | Compose in pure modules, `java.*`/`android.*` in `commonMain`, `Map<String, Any>`, mutable `object` | `:tools:architecture-tests` |
| `explicitApi()` | An undeclared public API surface | every library module |
| `checkKotlinAbi` | A binary-incompatible public API change | every library module |

`verifyModuleGraph` and `checkKotlinAbi` are Gradle tasks and fail the `check` job.
The Konsist rules run in their own CI job on purpose: Konsist's last release predates
Kotlin 2.4, so it is the least trustworthy component in the build and it should not be
able to block the pipeline.

## CI

| Job | Runs on | What it does |
|---|---|---|
| `check` | ubuntu | `gradle check` + `verifyModuleGraph` + `checkKotlinAbi`, `-Werror` enabled |
| `architecture` | ubuntu | Konsist rules, isolated so they cannot block `check` |
| `canary` | macos + ubuntu | Compiles iOS simulator and wasmJs test sources for pure modules |
| `conformance` | ubuntu | `:integration:generated-compile:desktopTest` (empty until Phase 4) |

## Layout

```
build-logic/            included build holding the convention plugins
engine/<name>/          KMP library modules
tools/<name>/           JVM tools
integration/            cross-module verification
samples/                runnable demos
odd/tasks/              feature documents: objective, tasks, verification evidence
docs/                   format spec, plugin guide, getting started (Phase 10)
```
