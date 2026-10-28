package dev.rotalex.lutter.testsupport.generator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus checkers, called on the shapes that decide whether they are right.
 *
 * `DeterminismTest`'s rows pass vacuously over a corpus that emits nothing, so a checker's first
 * non-vacuous run is also its first real input, and its first finding is whatever it misreads.
 * Both directions are pinned below: a hex colour is not a decimal, and blanking it did not
 * silence the decimals, which is where a careless fix goes.
 */
class CodegenCorpusChecksTest {

    /**
     * A hex colour reports nothing, and each literal below carries a `\d+ [eE] \d+` run.
     *
     * The lowercase one is here because a pattern accepting upper case only would report `1e2`.
     */
    @Test
    fun `a hex colour is not a number the canonical form could have written`() {
        val content = """
            val primary = Color(0x0E375A4C)
            val accent = Color(0xFF37E594)
            val surface = Color(0xff1e2b3c4)
        """.trimIndent()

        val violations = nonCanonicalNumbers(outcomeWithFile(PATH, content))

        assertTrue(
            violations.isEmpty(),
            "a hex colour reported as a non-canonical number, out of:\n$content\n$violations",
        )
    }

    /**
     * An exponent and a widened float are both still reported, the exponent first.
     *
     * Neither literal contains `0x`, so the two decimals are the only findings this content can
     * produce, and a fix that silenced the row would produce none.
     */
    @Test
    fun `an exponent and a widened float are both still reported`() {
        val content = """
            val padded = 1.0E-4
            val widened = 0.10000000149011612
        """.trimIndent()

        val violations = nonCanonicalNumbers(outcomeWithFile(PATH, content))

        assertEquals(
            listOf(
                "'$PATH' writes '1.0E-4', which is scientific notation",
                "'$PATH' writes '0.10000000149011612', which carries more than four " +
                    "fractional digits and is a widened float",
            ),
            violations,
        )
    }

    /**
     * One emitted file and nothing else, which is all [nonCanonicalNumbers] reads.
     *
     * Only `files` is filled: a `PlannedFile` and a canonical encode would be a second description
     * of a run this file does not make, and a check that reads nothing else cannot tell.
     */
    private fun outcomeWithFile(path: String, content: String): CorpusOutcome = CorpusOutcome(
        seed = 0,
        documentBytes = ByteArray(0),
        analysisDiagnostics = emptyList(),
        codegenDiagnostics = emptyList(),
        files = listOf(CorpusFile(path, content)),
        planned = emptyList(),
    )

    private companion object {
        const val PATH: String = "screens/GenScreen0Screen.kt"
    }
}