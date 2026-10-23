# 0007. Immutable model with persistent collections

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** All model types immutable; `NodeTable` on persistent map; no in-place mutation APIs.
- **Why:** Safe sharing across threads/Compose; cheap snapshots; trivially testable; structural sharing for undo and analysis caching.
- **Alternatives:** mutable model with change events; Compose snapshot state in model.
- **Trade-offs:** allocation on edits; API returns new documents.
- **Consequences:** editing layer owns the mutation story; model has no Compose dependency.
