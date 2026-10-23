# 0011. Generated code is one-way and generate-only

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** No parsing generated Kotlin back; users extend via host functions, slots, and reusable components.
- **Why:** Round-tripping requires a Kotlin parser and destroys determinism.
- **Alternatives:** partial regeneration with markers; protected regions.
- **Trade-offs:** hand-edits to generated files are lost on regeneration.
- **Consequences:** generated directory is treated as build output; header says "Do not edit."
