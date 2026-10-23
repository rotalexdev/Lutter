# 0009. Actions as data; two-way binding not modeled

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `ActionSequence`/`ActionStep` with registry-defined actions; intrinsic actions for state/nav/flow/host; no `bind` sugar.
- **Why:** Executable at runtime and translatable to Kotlin; explicit data flow; small interpreter.
- **Alternatives:** lambdas in model (unserializable); scripting; two-way bindings.
- **Trade-offs:** slightly verbose for text fields (value ref + set action); editors can hide it.
- **Consequences:** `ActionEmit` templates for plugin actions; intrinsic emitters in the engine.
