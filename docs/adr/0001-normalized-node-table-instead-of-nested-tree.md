# 0001. Normalized node table instead of nested tree

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** One document-wide `Map<NodeId, Node>` with ordered child-id lists per slot; pages/components hold root ids.
- **Why:** O(1) lookup, cheap moves, patch/diff friendliness, stable references, structural sharing.
- **Alternatives:** nested tree; per-page tables; adjacency list with parent pointers.
- **Trade-offs:** invariants (single parent, acyclic) must be validated; parent lookup requires `DocumentIndex`.
- **Consequences:** structural pass is blocking; all mutations go through invariant-preserving patch ops; JSON has flat nodes sorted by id.
