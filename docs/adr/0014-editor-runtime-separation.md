# 0014. Editor/runtime separation

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** The editor consumes `:editing`, `:analysis`, `:runtime`, `:codegen`; the engine has no editor types except `RenderHooks`.
- **Why:** Engine usable by CLI/tests/server; editor cannot own or fork the model.
- **Alternatives:** editor-owned document; runtime with built-in editing overlays.
- **Trade-offs:** editor must implement selection/overlay UX itself using hooks.
- **Consequences:** `:runtime` stays small; controller is the single mutation gateway.
