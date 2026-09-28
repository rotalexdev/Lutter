package dev.rotalex.lutter.model

/**
 * Version of the schema itself: the shape of the component, modifier and action vocabulary.
 *
 * Separate from [FORMAT_VERSION] because the two move for different reasons. A schema change
 * means a document can mean something new; a format change means the same document is
 * written or read differently. A migration that bumps one does not have to bump the other,
 * and conflating them turns every format tweak into a schema migration.
 */
public const val CURRENT_SCHEMA_VERSION: Int = 1

/**
 * Version of the on-disk representation: envelope layout, encoding of values, key ordering.
 *
 * Bumped whenever a document written by an older version cannot be read by this one without
 * a migration.
 */
public const val FORMAT_VERSION: Int = 1
