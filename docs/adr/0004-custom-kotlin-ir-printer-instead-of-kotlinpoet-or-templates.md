# 0004. Custom Kotlin IR + printer instead of KotlinPoet or templates

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** Small `KtExpr/KtStmt/KtDeclaration` IR with a deterministic printer in `commonMain`.
- **Why:** KotlinPoet is JVM-only (verify at kickoff); templates cannot guarantee validity/imports; the needed Kotlin subset is small.
- **Alternatives:** KotlinPoet (JVM), KotlinPoet behind an `expect` layer, text templates, kotlin-compiler PSI.
- **Trade-offs:** we own formatting and import logic (~1–2k LOC); mitigated by golden tests.
- **Consequences:** codegen usable from wasm/CLI/server; formatting rules are versioned by goldens. A KotlinPoet backend could still be added for JVM-only consumers.
