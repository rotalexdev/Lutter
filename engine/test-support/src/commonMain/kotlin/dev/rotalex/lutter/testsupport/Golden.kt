package dev.rotalex.lutter.testsupport

/**
 * Reads a fixture by path, returning `null` when it is absent.
 *
 * Injected rather than resolved from a global, because a global would have to know which
 * filesystem it is talking about. On the JVM that is a classpath resource, on a native
 * target it is a bundle, and in a browser it is a fetch. See [Golden] for the phase this is
 * in.
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
 * Phase 0 keeps this object free of every platform API. There is deliberately no
 * filesystem-backed [GoldenSource] here: the implementations belong in this module's
 * platform source sets (`nonWebMain`, and later `androidMain` / `desktopMain`) or in the
 * JVM test harness, and writing a half-working one now would only hide which target it works
 * on. Until then a test supplies its own [GoldenSource] - a map, an in-memory string - which
 * is enough to fix the API and the failure messages.
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
