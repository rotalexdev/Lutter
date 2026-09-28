# Feature: Forge Engine — Project Foundation (Phase 0)

- **Feature id:** `forge-engine-foundation`
- **Status:** in progress
- **Authoritative plan:** [`PLAN.md`](../../PLAN.md) — Forge Engine, Kotlin Multiplatform UI document engine
- **Base package:** `dev.rotalex.lutter.*`
- **Route:** delegated direct (2+ non-trivial files → one writer)

## Objective

Create the repository, build logic, module shells, architecture guardrails and CI
for Forge Engine, exactly as specified in `PLAN.md` §21 (KMP/CMP), §22 (Gradle module
architecture), §23 (dependency graph), §28.7 (CI) and §32 Phase 0.

This deliverable is **source and configuration only**. No build can be run locally
(see the hard constraint below), so GitHub Actions is the verification surface.

## Problem

The plan is complete and detailed, but nothing exists on disk. A KMP project with
15 modules, 5 convention plugins, an architecture-rules engine and a 4-job CI matrix
is exactly the kind of scaffold that silently rots when it is improvised. It needs a
single version source of truth, an enforced module graph, and a CI pipeline that can
catch breakage that no local reviewer will see.

## Why now

Phase 0 is the only phase whose value is entirely in guardrails. Every later phase
(1–10) writes code *into* these modules and trusts these rules. Getting the graph
wrong is unrecoverable without a rewrite of the module structure.

## Hard constraint: no local toolchain (user decision)

**Do not download or install any Gradle, Kotlin or JDK binary in this environment.**
The user decided the toolchain is provisioned by GitHub Actions CI.

Consequences, applied throughout:

- No `./gradlew`, `gradle`, `kotlinc`, or `java` invocation locally, ever.
- `gradle/wrapper/gradle-wrapper.jar` is intentionally **absent** — it is a binary we
  are not allowed to fetch. `gradle/wrapper/gradle-wrapper.properties` **is** committed
  so the version is pinned and reviewable.
- CI uses `gradle/actions/setup-gradle` with an explicit, quoted `gradle-version`
  input and invokes `gradle` (not `./gradlew`). This is the officially documented
  "Project doesn't use Gradle wrapper" path and needs no wrapper JAR.
- **No check in this feature can be verified locally.** Every build, test, ABI and
  Konsist result is `CI-PENDING` until a CI run reports it. Nothing may be reported as
  passing on the strength of "it looks right".

## Resolved ambiguities in the plan

These are decisions taken while executing, recorded so a later phase does not
re-litigate them.

| # | Ambiguity | Resolution |
|---|---|---|
| A1 | Plan is self-contradictory on the base package: §1 and §33's intro say `dev.rotalex.lutter.*`, but §33.1–33.9 headings show `dev/forge/engine/...` | Use `dev.rotalex.lutter.<module>`. The repo name, the working-name note in §1 and the explicit §33 sentence all agree; the `dev/forge/engine` paths are pre-rename leftovers. PLAN §1 states renaming is mechanical. |
| A2 | Plan §22.3 asks for typesafe project accessors | **Enabled.** `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` in settings.gradle.kts. No `projects.*` reference exists yet — the module-graph task needs the string path to report it — so it is enabled before the first use rather than retrofitted. |
| A3 | Plan §22.3 says "use AGP's KMP library plugin if stable at kickoff; otherwise `androidTarget()`" | `com.android.kotlin.multiplatform.library` **is** stable. Using it, with the AGP ≥ 8.12 `kotlin { android { } }` block (not the deprecated `androidLibrary {}`). |
| A4 | Plan §22.3 names 4 convention plugins | A 5th, `forge.jvm.library`, is added. `:tools:architecture-tests` is a JVM *library*; applying `forge.jvm.tool` would wrongly apply the `application` plugin. |
| A5 | Plan §23.4 mandates Konsist as an architecture enforcer | Kept, but isolated in its **own CI job** that does not gate `check`. Konsist's last release is Dec 2024 and its PSI predates Kotlin 2.4, so it is the single highest-risk component in the scaffold. Isolation means a Konsist failure cannot block the rest of the pipeline. Documented fallback: Detekt. |
| A6 | Plan §32 Phase 0 requires an `embedFixtures` task | Implemented as a `Sync` that materialises `src/commonTest/resources` into `build/embedded-fixtures` for inspection, wired as a test-task dependency. It is deliberately **not** fed back via `resources.srcDir`, which would create a Gradle circular dependency. Classpath inclusion is left to KMP's native per-target resource handling. |
| A7 | Plan §32 Phase 0 requires a negative test for the graph checker | Implemented as a `selfTestModuleGraph` Gradle task that runs the pure rules engine against a synthetic known-bad graph and fails if the violation is *not* caught. Self-contained; no build mutation needed. |
| A8 | Plan §32 Phase 0 requires ABI validation "whichever is stable" | KGP built-in `abiValidation {}` (KEEP-0440), which supersedes `binary-compatibility-validator`. Task is `checkKotlinAbi`, **not** `checkLegacyAbi`/`apiCheck`. `keepLocallyUnsupportedTargets` stays at its default so iOS/wasm ABI is inferred on Linux runners instead of failing. |
| A9 | Plan §21.1 puts canary targets on pure modules "from Phase 0" | Canary targets live in their own convention plugin `forge.canary.targets`, applied explicitly to the 8 pure engine modules. They are deliberately **not** inherited from `forge.kmp.library`: §21.2 keeps Compose modules on Android + Desktop until iOS/Wasm *runtime* support lands (roadmap item 17), and a Compose module that inherited `wasmJs` would fail to compile for a reason that has nothing to do with the boundary being guarded. |

## Corrections made during review

Three defects were found after the scaffold was written and fixed before commit:

1. **Canary targets were universal.** They were declared inside `forge.kmp.library`, so
   `:engine:runtime`, `:engine:builtins-compose`, `:integration:generated-compile` and
   `:samples:desktop-preview` all inherited `iosSimulatorArm64` and `wasmJs`. This
   contradicts §21.2 and would very likely have failed CI on a Compose-on-Wasm resolution
   problem. Extracted to `forge.canary.targets` (A9).
2. **The module-graph checker was blind to the JVM modules.** It inspected only
   `commonMain*` configurations, so `:tools:cli` and `:tools:architecture-tests` — which
   declare dependencies in `implementation` / `testImplementation` — were entirely
   unguarded. A dependency added there would have passed unnoticed. The configuration list
   now covers both module classes.
3. **Only `check.yml` provisioned the Android SDK.** AGP resolves the SDK while
   *configuring* the build, and every workflow configures the whole project graph, so
   `architecture`, `canary` and `conformance` could all have failed on a missing SDK before
   running a single test. All four workflows now provision it.

## Known risks carried into the first CI run

| Risk | Why it is not pre-empted | Remedy |
|---|---|---|
| `checkKotlinAbi` fails because no reference dump is committed yet | The guardrail working as intended, not a defect | `gradle updateKotlinAbi`, commit `**/api/**`. Documented inline in `check.yml` |
| Konsist 0.17.3 cannot parse Kotlin 2.4 sources | Disabling it would erase a PLAN §23.4 guardrail to make a badge green | Replace the `architecture` job with Detekt |
| Gradle 9's `failOnNoDiscoveredTests` | It fires only "if test sources are present", and the 13 empty modules have none | If it does fire, set `failOnNoDiscoveredTests = false` deliberately, with a comment |
| `konsist` / `kotest` 6.2.5 coordinates unverified against Maven Central | Only the `architecture` job consumes them | Fix the version in the catalog; nothing else depends on it |

## Version matrix (pinned inside Kotlin's documented support range)

Kotlin 2.4.0–2.4.10 is documented as compatible with **Gradle 7.6.3–9.5.0** and
**AGP 8.5.2–9.1.0**. Kotlin 2.4.20 exists (released Sept 2026) but its support range
is not yet published, so "latest everything" would mean shipping past the documented
matrix. For a scaffold that cannot be tested locally, the documented matrix wins.

| Component | Version | Basis |
|---|---|---|
| Kotlin | 2.4.10 | Current stable per the KMP compatibility guide; inside the published matrix |
| Compose Multiplatform | 1.12.1 | Latest (2026-09-22). Gates a *minimum* KGP of 2.2.0, no maximum |
| Compose compiler plugin | 2.4.10 | Must match the Kotlin version exactly — a different axis from CMP |
| AGP | 9.1.0 | Top of Kotlin 2.4's documented AGP range; ≥ 8.12 so `android {}` is the non-deprecated block |
| Gradle | 9.5.0 | Top of Kotlin 2.4's documented Gradle range |
| JDK (toolchain + CI) | 21 | AGP 9 needs 17+; 21 is LTS. `jvmToolchain(21)` makes builds runner-independent |
| kotlinx-serialization-json | 1.11.0 | Verified on Maven Central, 2026-04-09 |
| kotlinx-coroutines | 1.11.0 | Verified, 2026-05-08 |
| kotlinx-collections-immutable | 0.5.2 | Verified, 2026-08-29. **0.5.x renamed every copy-returning method per KEEP-0459** (`add`→`adding`, `put`→`putting`, `removeAt`→`removingAt`, `set`→`replacingAt`, …). Write the new names from day one |
| compileSdk / minSdk | 36 / 24 | Conservative; both available with AGP 9.1 |

`kotlinx-io` (0.9.1) is catalogued but unused until `:engine:serialization` needs
`nonWebMain` filesystem storage. It is deliberately kept out of every source set until
then, because its `FileSystem` API is still experimental.

## Scope

### In scope

- Git repository initialisation on a feature branch, Conventional Commits, work-unit commits.
- `settings.gradle.kts` (composite `build-logic`, `FAIL_ON_PROJECT_REPOS`), `gradle.properties`, root `build.gradle.kts`.
- `gradle/libs.versions.toml` — the single source of truth for every version.
- `gradle/wrapper/gradle-wrapper.properties` (text only, no JAR).
- `build-logic/` with convention plugins `forge.kmp.library`, `forge.kmp.compose`, `forge.jvm.tool`, `forge.jvm.library`, `forge.moduleGraph`.
- 15 module shells on the exact dependency allow-list of §23.2, with correct namespaces and no extra deps.
- `@EngineInternalApi` opt-in marker in `:engine:model`.
- `verifyModuleGraph` + `selfTestModuleGraph` guardrails.
- Konsist baseline rules in `:tools:architecture-tests` (§23.4), isolated in their own CI job.
- `embedFixtures` task and the `:engine:test-support` `Golden` skeleton.
- ABI validation wired into every library module.
- GitHub Actions: `check`, `architecture`, `canary`, `conformance`; plus `dependabot.yml`.
- README with the no-local-toolchain policy stated up front.

### Out of scope

Any domain code. Phases 1–10 (§32) own `:engine:model` records, specs, serialization,
analysis, codegen, runtime, editing, the CLI commands and the preview sample. Phase 0
creates shells plus a genuinely minimal `@EngineInternalApi` annotation, a `Golden`
skeleton and a CLI stub that says plainly that it is not implemented.

## Tasks

| ID | Task | Route | Status |
|---|---|---|---|
| T1 | Repo init, `.gitignore`, `.editorconfig`, `README.md` | inline | done |
| T2 | Version catalog + `settings.gradle.kts` + `gradle.properties` + root build | delegated | done |
| T3 | `build-logic` composite + 5 convention plugins | delegated | done |
| T4 | 15 module shells with §23.2 dependency allow-list | delegated | done |
| T5 | Guardrails: `@EngineInternalApi`, module-graph tasks, Konsist rules, `embedFixtures`, `Golden`, ABI | delegated | done |
| T6 | GitHub Actions workflows + Dependabot | delegated | done |
| T7 | Work-unit commits on the feature branch | inline | done |

## Acceptance criteria

1. `git log` shows work-unit commits on a feature branch, none on the default branch.
2. Every one of the 15 modules exists with a build file, namespace and only its §23.2-allowed project dependencies.
3. `gradle/libs.versions.toml` is the only place a version is written. No module build file contains a hard-coded version.
4. `verifyModuleGraph` encodes §23.2 and §23.3 and `selfTestModuleGraph` proves the checker catches a deliberate violation.
5. `explicitApi()` is on for every library module; `@EngineInternalApi` exists and is opt-in.
6. CI has `check`, `architecture`, `canary` and `conformance` jobs, and none of them invokes `./gradlew`.
7. The no-local-toolchain policy is documented in the README and in this document.

## Verification

**Local verification is impossible and is not claimed.** No Gradle, Kotlin or JDK
binary may be installed here, so the following are all `CI-PENDING` — pending the
first GitHub Actions run:

- [ ] `check` job green: `gradle check` over empty modules
- [ ] `verifyModuleGraph` green
- [ ] `selfTestModuleGraph` green
- [ ] `checkKotlinAbi` green
- [ ] Konsist rules green (independent job)
- [ ] `canary` green: iOS simulator + wasmJs compile on pure modules
- [ ] `conformance` green
- [ ] Version resolution succeeds for every catalogued coordinate

Structural review that *is* possible locally, and was performed:

- [x] All 15 modules exist, are included in `settings.gradle.kts`, and have a build file and sources
- [x] 55 declared `project(...)` edges match `ModuleGraphRules.ALLOWED` exactly — no extra edge, no allow-list entry nothing declares (machine-checked against the parsed allow-list)
- [x] Canary targets resolve to exactly the 8 pure engine modules
- [x] Zero hard-coded versions outside `gradle/libs.versions.toml` (the only literals are the four plugin artifacts in `build-logic/convention/build.gradle.kts`, which use catalog versions inside interpolated coordinates)
- [x] No `./gradlew` in any executable line of any file; the only occurrences are prose instructing against it
- [x] Delimiters balanced across all 51 `.kt`/`.kts` files
- [x] All four workflows provision the Android SDK and quote `gradle-version`
- [ ] YAML validity — no `pyyaml` in this environment, hand-reviewed only


## Next step

Push the branch and let CI run. Treat the first CI run as the real Phase 0 test and
iterate on its findings before starting Phase 1.
