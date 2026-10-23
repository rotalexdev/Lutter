# 0013. Patch-based undo/redo

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** Commands plan invertible `Patch`es; history stores patches.
- **Why:** Small, precise, diffable, reusable for sync and tests.
- **Alternatives:** snapshots; event sourcing; OT.
- **Trade-offs:** each `PatchOp` needs correct inverse (property-tested).
- **Consequences:** future collaboration can build on the same ops.
