package dev.rotalex.lutter.testsupport

/**
 * Reads a fixture by path, returning `null` when it is absent.
 *
 * Injected rather than resolved from a global, because a global would have to know which
 * filesystem it is talking about. On the JVM that is a classpath resource, on a native
 * target it is a bundle, and in a browser it is a fetch. [classpathGoldenSource] is the
 * classpath one, and a test may supply its own for any of the others.
 */
public fun interface GoldenSource {
    /**
     * @param path fixture path, relative to the fixture root, using `/` separators.
     * @return the fixture text, or `null` when no fixture exists at [path].
     */
    public fun read(path: String): String?
}

/**
 * Golden-file comparison for tests that must fail when a rendering changes.
 *
 * Phase 0 keeps this object free of every platform API. That is still true, and it is why
 * the one [GoldenSource] this module ships — [classpathGoldenSource] — is an `expect` here
 * with its lookup in `androidMain` and `desktopMain`, exactly where a platform call belongs.
 * Nothing here resolves a source on its own, and a filesystem-backed source is still
 * deliberately absent: a `File` read at execution time is an untracked input, and
 * `org.gradle.configuration-cache.problems=fail` is set in both property files.
 *
 * Until the classpath reader existed, a test supplied its own [GoldenSource] - a map, an
 * in-memory string - which fixed the API and the failure messages but never the bytes. That
 * is what left zero `.golden` files in the repository, and it is why this object was
 * unexercised code in a module nothing could reach: `ALLOWED_IN_TESTS` now lets a module's
 * `commonTest` depend on this one, and `CanonicalJsonGoldenTest` in `:engine:serialization`
 * is the consumer.
 *
 * The source is a parameter rather than a property of this object on purpose. A `var` here
 * would be shared mutable state across every test in the JVM, which is exactly what the
 * `NoMutableObjectStateTest` architecture rule exists to forbid.
 */
public object Golden {

    /**
     * @return the golden text for [name].
     * @throws IllegalStateException when no golden exists, listing the name that was looked
     *   up. An absent golden is a test-authoring error, not a mismatch.
     */
    public fun read(name: String, source: GoldenSource): String =
        source.read(name)
            ?: error(
                "No golden file named '$name'. Create it, or pass update = true to " +
                    "assertEquals to have the current value returned for writing.",
            )

    /**
     * Asserts that [actual] matches the golden for [name].
     *
     * @param update when true, a mismatch is reported but not thrown, and the text to write
     *   is returned instead. Writing is left to the caller because this module has no
     *   injected writer, and adding one before the platform source sets exist would fix the
     *   wrong abstraction.
     * @return the golden text when [update] is false; the text to persist when it is true.
     * @throws IllegalStateException when [update] is false and the values differ. The
     *   message shows both values in full, because a golden diff without context is the
     *   single most common reason a UI test failure goes unread.
     */
    public fun assertEquals(
        name: String,
        actual: String,
        source: GoldenSource,
        update: Boolean = false,
    ): String {
        val expected = source.read(name)

        if (expected == null || expected != actual) {
            if (update) return actual

            if (expected == null) {
                error("No golden file named '$name'. Pass update = true to get its initial content.")
            }

            error(
                "Golden mismatch for '$name'.\n" +
                    "--- expected ---\n$expected\n" +
                    "--- actual ---\n$actual\n" +
                    "Re-run with update = true to accept the actual value.",
            )
        }

        return expected
    }
}
