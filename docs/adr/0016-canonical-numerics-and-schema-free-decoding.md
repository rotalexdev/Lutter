# 0016. Canonical numerics and schema-free decoding

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** ≤4 fractional digits, integer-arithmetic formatting; self-describing `Value` tags; decoding never consults the schema.
- **Why:** Cross-platform determinism; lossless round trip for unknown plugin content.
- **Alternatives:** raw doubles; schema-directed compact encoding.
- **Trade-offs:** slightly more verbose JSON; precision limit (4 decimals) for UI values.
- **Consequences:** `Value` factories canonicalize; Konsist enforces factory use.
