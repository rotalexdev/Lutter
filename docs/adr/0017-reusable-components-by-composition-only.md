# 0017. Reusable components by composition only

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `ComponentDecl` with params/slots; no inheritance and no structural overrides.
- **Why:** Matches Compose functions; predictable codegen; avoids override-diff complexity.
- **Alternatives:** override maps keyed by inner node ids; inheritance.
- **Trade-offs:** less flexible than design-tool variants.
- **Consequences:** variants are expressed as params (enum/boolean) and slots.
