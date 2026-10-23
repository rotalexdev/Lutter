# 0017. Reusable components by composition only

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** `ComponentDecl` with params/slots; no inheritance and no structural overrides.
- **Why:** Matches Compose functions; predictable codegen; avoids override-diff complexity.
- **Alternatives:** override maps keyed by inner node ids; inheritance.
- **Trade-offs:** less flexible than design-tool variants.
- **Consequences:** variants are expressed as params (enum/boolean) and slots.
