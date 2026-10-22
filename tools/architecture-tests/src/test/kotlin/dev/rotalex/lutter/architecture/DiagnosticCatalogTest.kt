package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §36.1: every §17.3 code has a positive and a negative test.
 *
 * `EveryDiagnosticCodeIsReferencedTest` counts who names a code; both halves here are a
 * census over the same test sources: a reference whose line negates (`none {`, `isEmpty()`,
 * `assertFalse`, `assertNot…`) is negative evidence, and every other reference is positive
 * evidence. A code missing from either half is a code whose firing or not-firing a
 * document can never demonstrate.
 *
 * The two determinism rows of the same checklist item are already named: the order is
 * `DiagnosticSorterTest`'s and the empty census is
 * `EveryDiagnosticCodeIsReferencedTest.the census is not empty`.
 */
class DiagnosticCatalogTest {

    /** Declared §17.3 codes, their values and the files that declare them (D29's paths). */
    private val declarations: List<Triple<String, String, String>> = RepositoryRoot
        .engineModules()
        .values
        .flatMap { RepositoryRoot.kotlinFilesIn(it) }
        .filter { it.relativePath.endsWith(CATALOG_SUFFIX) }
        .flatMap { file ->
            DECLARATION.findAll(file.text).map { Triple(it.groupValues[1], it.groupValues[2], file.relativePath) }
        }

    private val declared: Map<String, String> = declarations.associate { it.first to it.third }

    private val values: Map<String, String> = declarations.associate { it.first to it.second }

    /** Test sources read only where code lines live: comments hold prose, not assertions. */
    private val codeLines: List<String> = RepositoryRoot
        .testSourceDirectories()
        .flatMap { RepositoryRoot.kotlinFilesIn(it) }
        .flatMap { file ->
            file.text.lines().filter { line ->
                val trimmed = line.trimStart()
                trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*") &&
                    !trimmed.startsWith("/*")
            }
        }

    private val positive: Set<String> = occurrences { !it.hasNegative() }

    private val negative: Set<String> = occurrences { it.hasNegative() }

    @Test
    fun `every declared code is asserted present`() {
        val missing = declared.keys - positive
        assertTrue(missing.isEmpty()) {
            report("no positive test names", missing)
        }
    }

    @Test
    fun `every declared code is asserted absent`() {
        val missing = declared.keys - negative
        assertTrue(missing.isEmpty()) {
            report("no negative test names", missing)
        }
    }

    @Test
    fun `the census is not empty`() {
        assertTrue(declared.isNotEmpty() && positive.isNotEmpty() && negative.isNotEmpty()) {
            "${declared.size} declared code(s), ${positive.size} positive and " +
                "${negative.size} negative reference(s); a scan that matches nothing is a " +
                "changed shape this rule stopped reading, not a clean catalog"
        }
    }

    /** One set of codes, collected from the lines whose direction [where] accepts. */
    private fun occurrences(where: (String) -> Boolean): Set<String> {
        val found = mutableSetOf<String>()
        for (line in codeLines) {
            if (!where(line)) continue
            for (match in PROPERTY.findAll(line)) found += match.groupValues[1]
            for (name in declared.keys) {
                if (line.contains("\"" + values.getValue(name) + "\"")) found += name
            }
        }
        return found
    }

    private fun String.hasNegative(): Boolean = NEGATIVE.any { contains(it) }

    private fun report(what: String, missing: Set<String>): String = declared.size.toString() +
        " declared code(s), ${missing.size} of them ${what}:\n" +
        missing.joinToString("\n") { "$it (${values.getValue(it)}) — declared in ${declared.getValue(it)}" }

    private companion object {

        const val CATALOG_SUFFIX: String = "diagnostic/DiagnosticCodes.kt"

        val DECLARATION = Regex(
            """^[ \t]*(?:\w+[ \t]+)*val[ \t]+([A-Za-z_]\w*)[ \t]*:[ \t]*DiagnosticCode[ \t]*=[ \t]*\n?""" +
                """[ \t]*DiagnosticCode\("([^"]+)"\)""",
            RegexOption.MULTILINE,
        )

        val PROPERTY = Regex("""\bDiagnosticCodes\.([A-Z]\w*)""")

        /** The corpus's negative spellings; a line carrying any of them asserts absence. */
        val NEGATIVE: List<String> = listOf(
            "none {",
            "none(",
            "isEmpty()",
            "assertFalse(",
            "assertNot",
        )
    }
}
