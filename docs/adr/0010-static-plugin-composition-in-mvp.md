# 0010. Static plugin composition in MVP

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** Plugins are installed in code as contribution facets; no dynamic loading; document records required plugins.
- **Why:** `ServiceLoader`/class loading is not portable across KMP; MVP does not need it.
- **Alternatives:** JVM `ServiceLoader`; OSGi-like; scripting-based plugins.
- **Trade-offs:** third parties recompile the host.
- **Consequences:** builtins are implemented as plugins (dogfooding); dynamic discovery can layer on top.
