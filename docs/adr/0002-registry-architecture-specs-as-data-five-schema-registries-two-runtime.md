# 0002. Registry architecture: specs as data, five schema registries, two runtime registries

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** Registries only for components, modifiers, actions, functions, types (schema) and renderers/modifier appliers (runtime).
- **Why:** These are the real extension points; other candidates duplicate information or belong to documents.
- **Alternatives:** registry for everything; big `when`; DI/service locator.
- **Trade-offs:** slightly more explicit wiring; some duplication of keys across schema/runtime (guarded by coverage checks).
- **Consequences:** plugins are pairs of contributions; no global state.
