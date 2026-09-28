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

## Versions

**This repository owns no dependency versions.** They live in the shared catalog
`io.github.alexanderrotela20.catalog:version-catalog`, consumed in
`settings.gradle.kts` as `rootLibs`. Kotlin, AGP, Compose Multiplatform,
kotlinx-serialization, kotlinx-coroutines, the Android SDK levels and the JVM target are all
defined there, and every Rotalex project resolves the same numbers.

The catalog **must** be named `rootLibs`: the convention plugins in
`rotalex-root-conventions` look up their own internal dependencies and SDK versions through
that exact name.

`gradle/libs.versions.toml` is a two-entry gap-filler, not a second catalog. It carries only
what `rootLibs` 1.2.7 does not — the JUnit 5 BOM and Konsist, both test-only — and it is
scheduled for deletion once those two coordinates are added upstream. Anything that exists
in `rootLibs` must never be duplicated here.

Build settings come from the shared catalog too, read by name:

| Setting | Catalog key | Meaning |
|---|---|---|
| `jvmTarget` | `rootLibs.versions.jvmTarget` | Java `sourceCompatibility`/`targetCompatibility` **and** Kotlin's `jvmTarget` |
| `compileSdk` | `rootLibs.versions.androidCompileSdk` | `compileSdk` |
| `minSdk` | `rootLibs.versions.androidMinSdk` | `minSdk` |

Two more values are local to CI:

| Value | Where | Why |
|---|---|---|
| `gradle-version` ('9.5.0') | each workflow | The Gradle release CI uses. Top of the range Kotlin 2.4 documents support for |
| `java-version` ('21') | each workflow | The JDK that *runs* Gradle, pinned with `setup-java`. Deliberately not a Gradle toolchain: a toolchain is a second, independent JDK number, and a Java compilation with no explicit target falls back to it |

There is no second JDK number in the build. `jvmTarget` from the catalog is applied to **both**
Java's `sourceCompatibility`/`targetCompatibility` and Kotlin's `jvmTarget`, and no convention
calls `jvmToolchain`. Two numbers is how you get `Inconsistent JVM-target compatibility
detected for tasks 'compileTestJava' (21) and 'compileTestKotlin' (17)` — the toolchain
supplies one, the catalog supplies the other, and only one of them gets applied.

Bump Kotlin, AGP and Compose Multiplatform together in **that** repository, and let the
version cascade into this build through a Dependabot-free catalog update. One PR, full CI
matrix.

> **Watch item:** the shared catalog currently pairs Kotlin `2.4.0` with AGP `9.2.1`, and
> Kotlin's own compatibility guide documents `2.4.0` as supported up to AGP `9.1.0`. That is
> a decision owned by the catalog, not by this project, so this repository does not override
> it. If CI reports a version-matrix symptom, the fix belongs in
> `rotalex-root-conventions`.


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
required check on `dev`, so the rule is the repository's rather than a convention that
decays.

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

`dependabot` targets `dev`, because a dependency bump is verified by the same matrix as
everything else before it can reach `main`.




```
build-logic/            included build holding the convention plugins
engine/<name>/          KMP library modules
tools/<name>/           JVM tools
integration/            cross-module verification
samples/                runnable demos
odd/tasks/              feature documents: objective, tasks, verification evidence
docs/                   format spec, plugin guide, getting started (Phase 10)
```

```
build-logic/            included build holding the convention plugins
engine/<name>/          KMP library modules
tools/<name>/           JVM tools
integration/            cross-module verification
samples/                runnable demos
odd/tasks/              feature documents: objective, tasks, verification evidence
docs/                   format spec, plugin guide, getting started (Phase 10)
.github/ci-gradle.properties   properties CI overlays on ~/.gradle/gradle.properties
```
