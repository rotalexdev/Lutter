# 0003. Split component definition by capability

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `ComponentSpec` (data) + `ComponentRenderer` (Compose) + declarative `CodegenBinding` (data) instead of one `ComponentDefinition` with `createRuntime()/generateCode()`.
- **Why:** Keep schema Compose-free and codegen platform-neutral; single resolver ensures parity.
- **Alternatives:** unified definition; annotation processing that derives both.
- **Trade-offs:** renderer and binding can diverge → coverage checks and conformance tests.
- **Consequences:** Compose is confined to two modules; codegen runs anywhere. Post-MVP option: generate renderers from bindings via KSP.
