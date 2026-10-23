# 0012. State architecture: framework-agnostic model, snapshot-state default emission

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `StateDecl` with scopes; `StateStrategy` chooses emission; default is Compose snapshot state with per-page holder classes.
- **Why:** Avoids coupling to ViewModel/Redux; generated code is idiomatic and dependency-free.
- **Alternatives:** ViewModel-first, MVI, Molecule.
- **Trade-offs:** persistence/external state postponed.
- **Consequences:** future strategies plug in without touching the model or analysis.
