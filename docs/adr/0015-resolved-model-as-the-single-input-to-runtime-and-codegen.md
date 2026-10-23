# 0015. Resolved model as the single input to runtime and codegen

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** `Analyzer` produces `ResolvedDocument`; both backends read only that.
- **Why:** One place for defaults, scopes, tokens and typing.
- **Alternatives:** each backend walks the raw document.
- **Trade-offs:** an additional derived structure (memory/time).
- **Consequences:** parity is structural, not accidental; incremental analysis later benefits both.
