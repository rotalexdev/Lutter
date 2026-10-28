package dev.rotalex.lutter.generated.compile

/**
 * Where conformance fixtures live, so the harness and its docs name one path.
 *
 * String only: reading and writing stay in the harness, which owns the filesystem.
 */
public object FixturePaths {
    /** Fixture directory, relative to this module's root. */
    public const val FIXTURES_DIR: String = "fixtures"

    /** The path of fixture [name] under [FIXTURES_DIR]. */
    public fun fixture(name: String): String = "$FIXTURES_DIR/$name"
}
