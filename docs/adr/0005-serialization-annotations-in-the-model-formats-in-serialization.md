# 0005. Serialization annotations in the model; formats in `:serialization`

> Mirrored verbatim from `PLAN.md` §34 — the plan stays the source of record; edit it first and re-mirror. Accepted (MVP).

- **Decision:** `@Serializable/@SerialName` on model types; codec, envelope, canonicalization, migrations in `:engine:serialization`.
- **Why:** Avoids a duplicated DTO layer (a second representation) while keeping the model format-agnostic.
- **Alternatives:** DTO surrogates; hand-written codecs; custom binary.
- **Trade-offs:** model depends on kotlinx-serialization annotations; `@SerialName` names become a persistence contract.
- **Consequences:** Konsist rule mandates `@SerialName`; renaming a class is safe, renaming a serial name is a migration.
