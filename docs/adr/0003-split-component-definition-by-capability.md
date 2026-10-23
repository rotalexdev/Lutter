# 0003. Split component definition by capability

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** `ComponentSpec` (data) + `ComponentRenderer` (Compose) + declarative `CodegenBinding` (data) instead of one `ComponentDefinition` with `createRuntime()/generateCode()`.
- **Why:** Keep schema Compose-free and codegen platform-neutral; single resolver ensures parity.
- **Alternatives:** unified definition; annotation processing that derives both.
- **Trade-offs:** renderer and binding can diverge → coverage checks and conformance tests.
- **Consequences:** Compose is confined to two modules; codegen runs anywhere. Post-MVP option: generate renderers from bindings via KSP.
