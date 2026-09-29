# Feature: Forge Engine — Core Domain Model (Phase 1)

- **Feature id:** `forge-engine-domain-model`
- **Status:** in progress
- **Branch:** `feat/phase-1-domain-model` (cut from `dev`, PR targets `dev`)
- **Authoritative plan:** [`PLAN.md`](../../PLAN.md) — §5 (Core Domain Model), §9 (Property and Type System), §23.3 (dependency graph)
- **Module:** `:engine:model` — `dev.rotalex.lutter.model`
- **Route:** delegated direct (2+ non-trivial files per task → one writer)

## Objective

Fill `:engine:model` with the closed, typed, serializable domain vocabulary every other
engine module will be written against: the identifiers, the value union with its canonical
numerics, `TypeRef`, the normalized node record, the node table, and the derived indexes.

PLAN §5.3 states the reason this module must land before anything else: `Node` carries
`Map<PropertyKey, PropertyValue>`, and *"both key and value are closed, typed, serializable
domain types"*. There is no partial version of this module that another module can be
written against. Either the vocabulary is closed, or `:engine:codegen` waits.

## Problem

Phase 0 shipped guardrails and empty module shells. `:engine:model` contains
`SchemaVersion`, `EngineInternalApi` and the identifier layer — and the branch is
**currently red on CI**.

## Why now

Every layer above depends on this one. `:engine:schema` builds registries over these types,
`:engine:analysis` type-checks `Expr` against `TypeRef`, `:engine:codegen` emits Compose
from `Node` + `Value`, and `:engine:serialization` owns the envelope. None of them can be
written honestly against a stub.

## Hard constraint: no local toolchain (inherited from Phase 0)

**Do not download or install any Gradle, Kotlin or JDK binary in this environment.**

Consequence, unchanged from Phase 0: **no check in this feature is verifiable locally.**
Every compile, test and ABI result is `CI-PENDING` until a CI run reports it. Nothing may
be reported as passing on the strength of "it looks right".

## Resolved ambiguities

| # | Ambiguity | Resolution |
|---|---|---|
| P1 | PLAN §5.2 shows the identifiers in package `dev.rotalex.lutter.model`; they are useful as a group and `ids` is already established | `dev.rotalex.lutter.model.ids`. PLAN §1 states path renames are mechanical, and a flat `model` package would mix 20 value classes with `Node`/`UiDocument`. |
| P2 | The branch's `Ids.kt` documented `@JvmInline` as "not needed, and does not resolve in `commonMain`" | **Wrong, and reverted.** `@JvmInline` is declared in `kotlin-stdlib-common` as an `expect annotation class` (Since Kotlin 1.5) and the compiler *requires* it on the JVM target. What is genuinely true is that `kotlin.jvm.*` is **not** in the common default-import set, so `commonMain` needs an explicit `import kotlin.jvm.JvmInline`. Removing the annotation yields `Value classes without '@JvmInline' annotation are not yet supported`. See T1. |
| P3 | PLAN §5.4 mentions `ColorArgb`, `RefKind`, `TokenKind` without defining them | Defined in this module as part of T3 — they are value-closed and belong with the union that references them, not in `:engine:schema`. |
| P4 | PLAN §5.5 says `NodeTable` wraps a persistent map but calls it an "internal detail, not exposed in signatures", while listing public members | The *implementation type* is internal; the public surface is the listed operators. `kotlinx-collections-immutable` is therefore an `implementation` dependency, not `api`. |
| P5 | PLAN §5.4 `Float32`/`Dp`/`Sp` all use `CanonicalFloat`; `Float64` uses `CanonicalDouble` | Taken as written. `Dp`/`Sp` are dimensionally distinct from a bare float, so they stay separate variants rather than collapsing into `Float32`. |
| P6 | PLAN §5.2's id list omits `BranchName`, but §5.4 needs a branch to be nameable and §10's expression AST carries one | `BranchName` is kept. It is a *simple* id (a document-local label, not a registry key) and it validates under the same rule as the other 12 simple ids. |

## Tasks

### T1 — Unblock `:engine:model` (restore `@JvmInline`)

- [x] **Diagnose the red build.** The problems report, not the task name, carries the
      message: `Value classes without '@JvmInline' annotation are not yet supported` at
      `Ids.kt` lines 40/50/60…/195, on both `compileKotlinDesktop` and `compileAndroidMain`.
- [x] **Establish that the premise behind the previous fix was false.** Two authoritative
      sources: the stdlib API reference lists `expect annotation class JvmInline` under the
      **Common** tab (Since Kotlin 1.5), and the JetBrains response on
      kotlinx.coroutines#4671 states the annotation is available in user common code and is
      only missing from the *stdlib's* non-JVM source sets. The compiler's own requirement
      that it be present is the third proof.
- [x] **Restore `@JvmInline` on all 18 identifiers** (13 simple + 5 namespaced) and add the
      explicit `import kotlin.jvm.JvmInline`.
- [x] **Delete the incorrect comment** and replace it with the real reason: the annotation
      is required, and common code must import it because `kotlin.jvm.*` is not a common
      default import.
- [ ] **Verify in CI** — `checkKotlinAbi` and the module graph must both be green.

### T2 — Canonical numerics (PLAN §5.4, D1)

- [ ] `CanonicalFloat` / `CanonicalDouble` serializers.
- [ ] Canonicalize at construction: round to ≤ 4 fractional digits, reject NaN and ±Inf.
- [ ] Format through **integer arithmetic** (`165000/10000` → `16.5`), never `toString()`.
- [ ] Tests: rounding boundaries, rejection, and a byte-stable round trip.

### T3 — The `Value` union (PLAN §5.4)

- [ ] All 16 variants, every persisted one with a mandatory `@SerialName` (§5.4 rule).
- [ ] `ColorArgb`, `RefKind`, `TokenKind` (P3).
- [ ] **Decoding is schema-free** — a `Value` must decode without knowing its component,
      so unknown properties from a newer plugin round-trip losslessly (D4).
- [ ] `ValueKind` (§9.3).

### T4 — `TypeRef` (PLAN §9.1)

- [ ] The `TypeRef` union and the Type → 5 dimensions mapping (§9.2).

### T5 — `Node` and `ModifierEntry` (PLAN §5.3)

- [ ] Normalized: children referenced by `NodeId`, never a recursive tree.
- [ ] `ModifierEntry` order is semantically significant — assert it.

### T6 — `NodeTable` (PLAN §5.5)

- [ ] `NodeTableSerializer` encoding `Map<NodeId, Node>` **sorted by id** — determinism is
      what makes a document diffable.
- [ ] Structural sharing via `with` / `without`.

### T7 — `DocumentIndex` and `ReferenceIndex` (PLAN §5.6)

- [ ] Derived, never persisted. `parentOf`, `ownerOf`, `pathTo`, `descendants`.
- [ ] Invariants the normalized form owes: single parent, no cycles, no orphans (§5.6).

## Acceptance criteria

- [ ] `:engine:model` compiles for **Android, Desktop (JVM) and Wasm** — three targets, so
      nothing platform-specific can hide (PLAN §21.1 canary).
- [ ] `checkKotlinAbi` green; the reference dumps are regenerated, not stale.
- [ ] The module graph rule holds: `:engine:model` depends on **no other module** (§23.3).
- [ ] Every persisted `Value` variant carries a `@SerialName`.
- [ ] A `Value` decodes with no schema in hand.
- [ ] All checks reported honestly; anything unverified stays `CI-PENDING`.

## Verification evidence

No local toolchain — every row below is a CI run, not a local command.

| Task | Command | Result |
|---|---|---|
| T1 | CI `check` on `feat/phase-1-domain-model` | PENDING |

## Delivery

- Feature doc: [`odd/tasks/forge-engine-domain-model.md`](forge-engine-domain-model.md)
- Engram mirror: topic `odd/forge-engine-domain-model/tasks`
- Work-unit commits on `feat/phase-1-domain-model`; PR title is a strict conventional
  commit targeting `dev`.
