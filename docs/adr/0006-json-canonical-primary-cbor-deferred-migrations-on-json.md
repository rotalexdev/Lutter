# 0006. JSON canonical primary; CBOR deferred; migrations on JSON

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** Canonical JSON only; migrations work on `JsonElement`.
- **Why:** Human-readable, Git-friendly, debuggable; migrations need a schema-free tree.
- **Alternatives:** CBOR, custom binary, protobuf.
- **Trade-offs:** larger files, slower parsing.
- **Consequences:** binary encodings are caches of the *current* schema only; any change to persisted shape needs a migration.
