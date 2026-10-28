package dev.rotalex.lutter.serialization

/**
 * The registered document chain, empty until a second schema version exists (§19.2).
 *
 * `schemaVersion = 1` is unfrozen pre-MVP, so every real migration is synthetic until
 * then; tests carry their own chain instead of registering here.
 */
public val Migrations: MigrationChain = MigrationChain(emptyList())
