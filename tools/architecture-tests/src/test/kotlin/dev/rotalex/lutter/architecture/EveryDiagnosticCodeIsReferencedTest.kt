package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §36.1 wants every code in §17.3 named by a test, and nothing could say when one stopped being
 * named. Both sides are derived from the sources and compared: a declared code no test names, and
 * a name a test spells that no catalog declares.
 *
 * ### Test sources, and why that is what makes the row mean something
 *
 * The references are read from test source sets, not from the repository. Every code has a
 * producer in `commonMain` — that is what declaring one means — so counting production references
 * passed with every test deleted and could not have failed for anything a code needs. The narrower
 * question is the one worth asking: the census is red for a code nothing asserts, and
 * `codegen.name_collision` was reachable through its producer alone until a test named it.
 *
 * Source sets are matched by directory name, so a new one is covered the day it exists. The
 * declaration side is not re-scoped: the catalog lives in `commonMain` by D29 and the rule reads
 * whichever engine module holds one.
 *
 * ### Two spellings, and what keeps the second one honest
 *
 * A test names a code as `DiagnosticCodes.<Property>` or as the code's own value, and
 * `DiagnosticCode` calls the value the stable contract tests assert, so both spellings are the
 * same act. The value counts only as a whole quoted literal — a quote immediately before and
 * after it — because this codebase explains itself in prose and `CodegenCorpus` mentions three
 * codes that way: backticked in a `//` comment, in a KDoc, and embedded inside a longer message.
 * A scan that matched the value loosely would take all three for a test asserting it.
 *
 * The phantom direction reads the property spelling only, and the asymmetry is the price. A bare
 * quoted string is not a reference to a code until a catalog makes it one, so scanning values the
 * other way would report every route and every message in the repository. The gap it leaves is
 * narrow: a stale name in a producer's prose now reads as prose, while live code cannot carry one
 * at all — an undeclared `DiagnosticCodes.<Property>` does not resolve, and that is the compiler's
 * row rather than this one's.
 *
 * ### Where the negative half already lives
 *
 * `DocumentGeneratorValidityTest` analyses 200 generated documents and fails on any error-severity
 * finding, and `RoundTripPropertyTest` and `PatchInversePropertyTest` run 1,000 each, so "no error
 * fires on a valid document" is one row over documents nobody chose, covering every code at once.
 *
 * A text scanner, like every rule here — see `SourceRules`. Its one false positive is prose that
 * looks like a reference, and this file holds an example of the harmless kind: the code named
 * above is written out unquoted and in a KDoc, so neither pattern reads it, and the catalog is
 * not itself a test source set.
 */
class EveryDiagnosticCodeIsReferencedTest {

    private val catalogs: List<KotlinFile> = RepositoryRoot
        .engineModules()
        .values
        .flatMap { RepositoryRoot.kotlinFilesIn(it) }
        .filter { it.relativePath.endsWith(CATALOG_SUFFIX) }

    /** Each declaration this rule found, beside the catalog file that carries it. */
    private val declarations: List<Pair<MatchResult, String>> = catalogs.flatMap { file ->
        DECLARATION.findAll(file.text).map { match -> match to file.relativePath }
    }

    /** Every declared code paired with the file that declares it. */
    private val declared: Map<String, String> = declarations.associate { (match, path) ->
        match.groupValues[1] to path
    }

    /** The value each declared code carries, which is the other spelling a test may assert. */
    private val values: Map<String, String> = declarations.associate { (match, _) ->
        match.groupValues[1] to match.groupValues[2]
    }

    /** The catalog entries this rule cannot read a value out of, which are entries it cannot see. */
    private val unreadable: Set<String> = catalogs
        .flatMap { file -> DECLARATION_HEAD.findAll(file.text).map { it.groupValues[1] } }
        .toSet() - declared.keys

    private val testSources: List<KotlinFile> = RepositoryRoot
        .testSourceDirectories()
        .flatMap { RepositoryRoot.kotlinFilesIn(it) }

    private val namedByTest: Set<String> = testSources
        .flatMap { file -> REFERENCE.findAll(file.text).map { it.groupValues[1] } }
        .toSet()

    private val quotedByTest: Set<String> = declared.keys
        .filter { name ->
            val quoted = "\"" + values.getValue(name) + "\""
            testSources.any { it.text.contains(quoted) }
        }
        .toSet()

    @Test
    fun `the census is not empty`() {
        val found = catalogs.map { it.relativePath }
        val sets = RepositoryRoot.testSourceDirectories().size

        assertTrue(found.isNotEmpty() && declared.isNotEmpty() && testSources.isNotEmpty()) {
            "${declared.size} code(s) declared across ${found.size} catalog file(s) and " +
                "${testSources.size} test source(s) across $sets source set(s); a pattern that " +
                "stops matching reads nothing rather than finding nothing, so both halves are " +
                "checked for emptiness here rather than read as an answer.\n" +
                found.joinToString("\n")
        }
    }

    /**
     * A code this rule cannot read a value from is a code it cannot check.
     *
     * The declaration pattern takes the value as well as the name, so a catalog entry written in
     * some other shape drops out of the census entirely and nothing reports the hole. Without
     * this row a silent omission is indistinguishable from a code nobody has written a test for.
     */
    @Test
    fun `every declared code carries a value this rule can read`() {
        assertTrue(unreadable.isEmpty()) {
            "${unreadable.size} code(s) are declared in a shape this rule cannot read, so they are " +
                "neither counted nor checked. A declaration whose value is not a string literal " +
                "leaves the catalog, not just this scan.\n" +
                unreadable.joinToString("\n")
        }
    }

    @Test
    fun `every declared code is named by a test`() {
        val unnamed = declared.keys - namedByTest - quotedByTest

        assertTrue(unnamed.isEmpty()) {
            "${declared.size} code(s) declared, ${unnamed.size} of them named by no test: a code no " +
                "test asserts is a string with a producer, and §36.1 asks each one for a test.\n" +
                unnamed.joinToString("\n") {
                    "$it (${values.getValue(it)}) — declared in ${declared.getValue(it)}"
                }
        }
    }

    @Test
    fun `every name a test spells is a declared code`() {
        val phantom = namedByTest - declared.keys

        assertTrue(phantom.isEmpty()) {
            "${namedByTest.size} name(s) read as a `DiagnosticCodes` reference across the test " +
                "source sets, and ${phantom.size} of them no catalog declares: the declaration was " +
                "renamed or removed and the test was not.\n" +
                phantom.joinToString("\n")
        }
    }

    private companion object {

        /**
         * The catalog's path and not its file name, because D29 lets two engine modules hold a
         * catalog each and their vocabularies are meant to stay apart.
         */
        const val CATALOG_SUFFIX: String = "diagnostic/DiagnosticCodes.kt"

        /**
         * The head of a `val` of type `DiagnosticCode`, without its value.
         *
         * Read on its own so a declaration whose value is written in some shape this rule does not
         * recognise is still *seen* — otherwise the catalog entry and the scan gap are the same
         * absence, and nothing says so. The modifier run is there so `public` — or a qualifier
         * added later — cannot hide it.
         */
        val DECLARATION_HEAD = Regex(
            """^[ \t]*(?:\w+[ \t]+)*val[ \t]+([A-Za-z_]\w*)[ \t]*:[ \t]*DiagnosticCode[ \t]*=""",
            RegexOption.MULTILINE,
        )

        /**
         * A `val` of type `DiagnosticCode` and its literal value.
         *
         * The type is what makes this a code rather than any other property, and the value is what
         * lets a test that asserts `code.value` count as naming it. The single newline is the
         * formatter's, not a convenience: `ComponentConflictingProperties` is written across two
         * lines, and a pattern that stopped at the first would drop a code out of the census the
         * same way the row above exists to catch.
         */
        val DECLARATION = Regex(
            """^[ \t]*(?:\w+[ \t]+)*val[ \t]+([A-Za-z_]\w*)[ \t]*:[ \t]*DiagnosticCode[ \t]*=[ \t]*\n?""" +
                """[ \t]*DiagnosticCode\("([^"]+)"\)""",
            RegexOption.MULTILINE,
        )

        /**
         * `DiagnosticCodes.<PropertyName>`, which is the spelling a rename breaks and the only one
         * the phantom direction can read.
         *
         * The capital after the dot is the catalog's own convention doing work. Every entry is a
         * PascalCase property, and without it the suffix of `diagnostic/DiagnosticCodes.kt` reads
         * as a code named `kt` — this rule's own path constant, reported as a phantom.
         */
        val REFERENCE = Regex("""\bDiagnosticCodes\.([A-Z]\w*)""")
    }
}
