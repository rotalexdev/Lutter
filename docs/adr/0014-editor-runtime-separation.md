# 0014. Editor/runtime separation

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** The editor consumes `:editing`, `:analysis`, `:runtime`, `:codegen`; the engine has no editor types except `RenderHooks`.
- **Why:** Engine usable by CLI/tests/server; editor cannot own or fork the model.
- **Alternatives:** editor-owned document; runtime with built-in editing overlays.
- **Trade-offs:** editor must implement selection/overlay UX itself using hooks.
- **Consequences:** `:runtime` stays small; controller is the single mutation gateway.
