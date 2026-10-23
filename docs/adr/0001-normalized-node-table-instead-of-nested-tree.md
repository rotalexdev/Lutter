# 0001. Normalized node table instead of nested tree

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** One document-wide `Map<NodeId, Node>` with ordered child-id lists per slot; pages/components hold root ids.
- **Why:** O(1) lookup, cheap moves, patch/diff friendliness, stable references, structural sharing.
- **Alternatives:** nested tree; per-page tables; adjacency list with parent pointers.
- **Trade-offs:** invariants (single parent, acyclic) must be validated; parent lookup requires `DocumentIndex`.
- **Consequences:** structural pass is blocking; all mutations go through invariant-preserving patch ops; JSON has flat nodes sorted by id.
