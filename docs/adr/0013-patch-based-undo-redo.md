# 0013. Patch-based undo/redo

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** Commands plan invertible `Patch`es; history stores patches.
- **Why:** Small, precise, diffable, reusable for sync and tests.
- **Alternatives:** snapshots; event sourcing; OT.
- **Trade-offs:** each `PatchOp` needs correct inverse (property-tested).
- **Consequences:** future collaboration can build on the same ops.
