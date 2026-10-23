# 0005. Serialization annotations in the model; formats in `:serialization`

> Status: accepted at MVP. Source of record: the repository's planning document — edit it first and re-mirror here.

- **Decision:** `@Serializable/@SerialName` on model types; codec, envelope, canonicalization, migrations in `:engine:serialization`.
- **Why:** Avoids a duplicated DTO layer (a second representation) while keeping the model format-agnostic.
- **Alternatives:** DTO surrogates; hand-written codecs; custom binary.
- **Trade-offs:** model depends on kotlinx-serialization annotations; `@SerialName` names become a persistence contract.
- **Consequences:** Konsist rule mandates `@SerialName`; renaming a class is safe, renaming a serial name is a migration.
