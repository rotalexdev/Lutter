# 0002. Registry architecture: specs as data, five schema registries, two runtime registries

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** Registries only for components, modifiers, actions, functions, types (schema) and renderers/modifier appliers (runtime).
- **Why:** These are the real extension points; other candidates duplicate information or belong to documents.
- **Alternatives:** registry for everything; big `when`; DI/service locator.
- **Trade-offs:** slightly more explicit wiring; some duplication of keys across schema/runtime (guarded by coverage checks).
- **Consequences:** plugins are pairs of contributions; no global state.
