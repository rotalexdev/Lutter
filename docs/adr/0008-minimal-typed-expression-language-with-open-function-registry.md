# 0008. Minimal typed expression language with open function registry

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** Closed AST and operator set; extensible functions; typed with no implicit conversion; no `/` and `%` in MVP.
- **Why:** Semantic parity between interpreter and generated Kotlin; small surface to validate.
- **Alternatives:** embed Kotlin script; JS/CEL/JSONata; free-form strings.
- **Trade-offs:** limited expressiveness; users use host functions for complex logic.
- **Consequences:** the expression corpus guarantees parity; extending operators needs parity tests.
