package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §21.3: `expect`/`actual` is allowed only for back-press handling, default font resolution,
 * the `nonWebMain` filesystem shim and benchmark clocks. Anything else in the domain layer needs an
 * ADR written before it lands.
 *
 * ### What §21.3 names is a purpose, and a rule cannot read a purpose
 *
 * So the rule is a file allow-list, and every entry carries the purpose that lets it in. That is a
 * proxy for the plan's own words, and a proxy is only worth having if it cannot be extended
 * silently: an entry here has to name a category, so a fifth declaration is a decision somebody
 * wrote down rather than a line somebody typed.
 *
 * The single entry is `classpathGoldenSource`, which reads a golden off the classpath and so is
 * the closest of §21.3's four purposes in a repository that has no `nonWebMain` source set — and it
 * lives in the test harness, which "any other `expect/actual` **in the domain layer**" does not
 * reach. It is called out here rather than left for the next reader to work out.
 *
 * ### The scanner, and why it demands a keyword
 *
 * `actual` and `expect` are ordinary English words, and this repository's rules are text scanners
 * by design — see [SourceRules]. A pattern matching the bare word reports a KDoc line or a raw
 * string that happens to start with one, and a rule that cries wolf over the repository's own
 * sources gets deleted by the next reader instead of fixed. So the keyword is required after it:
 * `actual fun`, `expect class`, `actual typealias`, never `actual sentence`.
 *
 * ### Both directions are asserted
 *
 * A declaration outside the list is the violation §21.3 is about. A listed file that no longer
 * declares anything is the list rotting in the other direction, and an allow-list that silently
 * stops matching is worse than no list: it reads as permission.
 */
class ExpectActualOnlyWhereAllowedTest {

    private val declaring = RepositoryRoot
        .kotlinFilesIn(RepositoryRoot.path)
        .filter { DECLARATION.containsMatchIn(it.text) }
        .associate { it.relativePath to Unit }

    @Test
    fun `no expect or actual declaration sits outside an allowed file`() {
        val unlisted = declaring.keys - ALLOWED_BY_PURPOSE.keys

        assertTrue(unlisted.isEmpty()) {
            "${unlisted.size} file(s) declare expect/actual outside §21.3's allow-list.\n" +
                unlisted.joinToString("\n") { "$it — an ADR under §21.3 is what admits a new one" }
        }
    }

    @Test
    fun `every allowed file still declares one`() {
        val rotted = ALLOWED_BY_PURPOSE.keys - declaring.keys

        assertTrue(rotted.isEmpty()) {
            "${rotted.size} allowed file(s) no longer declare expect/actual, so the entry reads as " +
                "permission rather than as an exemption.\n" + rotted.joinToString("\n")
        }
    }

    private companion object {

        const val HARNESS: String = "engine/test-support/src"
        private const val PACKAGE: String = "kotlin/dev/rotalex/lutter/testsupport"

        /**
         * The three `actual`/`expect` files this repository has, and why each one is here.
         *
         * An `expect` and its `actual`s are one decision, so all three share the reason: a reader
         * sees one permission rather than three separate exemptions.
         */
        val ALLOWED_BY_PURPOSE = mapOf(
            "$HARNESS/commonMain/$PACKAGE/ClasspathGoldenSource.kt" to FILESYSTEM_SHIM,
            "$HARNESS/androidMain/$PACKAGE/ClasspathGoldenSource.android.kt" to FILESYSTEM_SHIM,
            "$HARNESS/desktopMain/$PACKAGE/ClasspathGoldenSource.desktop.kt" to FILESYSTEM_SHIM,
        )

        const val FILESYSTEM_SHIM: String =
            "§21.3 nonWebMain filesystem shim — a classpath read, in the test harness"

        /**
         * A declaration, and not the bare word.
         *
         * The keyword after `expect`/`actual` is what keeps a comment or a raw string out, and the
         * optional-modifier run is what lets `public expect fun` match.
         */
        val DECLARATION = Regex(
            """^[ \t]*(?:\w+[ \t]+)*(?:expect|actual)[ \t]+""" +
                """(?:fun|val|var|class|object|interface|typealias)[ \t*\n]""",
            RegexOption.MULTILINE,
        )
    }
}
