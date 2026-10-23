# 0015. Resolved model as the single input to runtime and codegen

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `Analyzer` produces `ResolvedDocument`; both backends read only that.
- **Why:** One place for defaults, scopes, tokens and typing.
- **Alternatives:** each backend walks the raw document.
- **Trade-offs:** an additional derived structure (memory/time).
- **Consequences:** parity is structural, not accidental; incremental analysis later benefits both.
