package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticSorter
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §17.2 calls the diagnostic order total and stable, and nothing held it to that.
 *
 * Every row builds its own findings, because a pass decides which codes are reachable and this
 * contract is about all of them at once. Nothing here runs `Analyzer`: the keys are fields.
 *
 * ### What the order is total over
 *
 * The ten fields named in `FIELDS_17_2`, and nothing else. `args` and `suggestions` are on
 * `Diagnostic` and are not keys, so a row pins that rather than leaving it to a reader's memory.
 */
class DiagnosticSorterTest {

    @Test
    fun `an error outranks a warning on one node whatever the code`() {
        val error = onNode(Severity.Error, DiagnosticCodes.ValueNonFinite)
        val warning = onNode(Severity.Warning, DiagnosticCodes.PropRange)
        val info = onNode(Severity.Info, DiagnosticCodes.ComponentUnknown)

        assertEquals(listOf(error, warning, info), DiagnosticSorter.sort(listOf(info, warning, error)))
    }

    @Test
    fun `each of §17.2's fields decides before every field after it`() {
        for (index in FIELDS_17_2.indices) {
            val decisive: String = FIELDS_17_2[index]
            val lateAt: Diagnostic = probe { it == decisive }
            val lateAfter: Diagnostic = probe { FIELDS_17_2.indexOf(it) > index }

            assertEquals(
                listOf(lateAfter, lateAt),
                DiagnosticSorter.sort(listOf(lateAt, lateAfter)),
                "field $index, $decisive, must decide over every field after it, or nothing does",
            )
        }
    }

    @Test
    fun `two findings differing in one field never sort as equals`() {
        for (field in FIELDS_17_2) {
            val low: Diagnostic = probe { false }
            val high: Diagnostic = probe { it == field }

            assertNotEquals(high, low, "the $field probes came out identical, so the row is vacuous")
            assertEquals(
                DiagnosticSorter.sort(listOf(low, high)),
                DiagnosticSorter.sort(listOf(high, low)),
                "$field is not a key: the two findings tie and the input order survives",
            )
        }
    }

    @Test
    fun `args and suggestions are carried but never compared`() {
        val plain: Diagnostic = probe { false }
        val annotated: Diagnostic = plain.copy(args = mapOf("expected" to "1"))

        assertEquals(listOf(plain, annotated), DiagnosticSorter.sort(listOf(plain, annotated)))
        assertEquals(listOf(annotated, plain), DiagnosticSorter.sort(listOf(annotated, plain)))
    }

    @Test
    fun `the sorted order does not depend on the order the findings arrived in`() {
        val findings: List<Diagnostic> = corpus()
        val reference: List<Diagnostic> = DiagnosticSorter.sort(findings)
        var reordered = 0

        assertEquals(reference, DiagnosticSorter.sort(findings.reversed()))
        for (seed in 0 until PERMUTATION_SEEDS) {
            val shuffled: List<Diagnostic> = findings.shuffled(Random(seed))
            if (shuffled != findings) reordered += 1
            assertEquals(reference, DiagnosticSorter.sort(shuffled))
        }

        assertTrue(reordered > 0, SEEDS_REORDERED)
    }

    @Test
    fun `sorting an already sorted list changes nothing`() {
        val once: List<Diagnostic> = DiagnosticSorter.sort(corpus())

        assertEquals(once, DiagnosticSorter.sort(once))
    }

    /** A finding on one shared node, so severity and code are the only fields free to order a row. */
    private fun onNode(severity: Severity, code: DiagnosticCode): Diagnostic = Diagnostic(
        severity = severity,
        code = code,
        location = DiagnosticLocation(nodeId = NodeId("n_1")),
        message = "one node",
    )

    /**
     * A finding whose fields carry their late value exactly where [late] says so.
     *
     * Two values per field because "these two findings differ here, and nowhere else" is the
     * premise every row rests on. The late value is the greater of its pair under the key that
     * reads it — `Info` over `Error`, `zulu` over `alpha` — so a row's expected order is no guess.
     */
    private fun probe(late: (String) -> Boolean): Diagnostic = Diagnostic(
        severity = if (late("severity")) Severity.Info else Severity.Error,
        code = if (late("code")) DiagnosticCodes.ValueNonFinite else DiagnosticCodes.ComponentUnknown,
        message = if (late("message")) "zulu" else "alpha",
        location = DiagnosticLocation(
            pageId = PageId(if (late("pageId")) "p_zulu" else "p_alpha"),
            componentDeclId = ComponentDeclId(if (late("componentDeclId")) "Zulu" else "Alpha"),
            nodeId = NodeId(if (late("nodeId")) "n_zulu" else "n_alpha"),
            property = PropertyKey(if (late("property")) "zulu" else "alpha"),
            modifierIndex = if (late("modifierIndex")) 9 else 0,
            event = EventKey(if (late("event")) "onZulu" else "onAlpha"),
            path = if (late("path")) listOf("zulu") else listOf("alpha"),
        ),
    )

    /**
     * Findings no two of which tie, so a permutation has exactly one possible answer.
     *
     * Distinct probes differ in a field the sorter reads, so deduplicating is enough to guarantee
     * it. A corpus holding a tie would make the permutation row fail against itself rather than
     * against the sorter, which is a failure nobody can act on.
     */
    private fun corpus(): List<Diagnostic> = buildList {
        for (field in FIELDS_17_2) add(probe { it == field })
        for (index in FIELDS_17_2.indices) add(probe { FIELDS_17_2.indexOf(it) >= index })
        add(probe { false })
        add(probe { true })
    }.distinct()

    private companion object {

        /**
         * §17.2's fields, in the order the sorter reads them.
         *
         * `severity`, `code` and `message` are `Diagnostic`'s and the other seven are
         * `DiagnosticLocation`'s, interleaved so that neither type's fields ever move: each one
         * keeps the position its own declaration gives it.
         */
        val FIELDS_17_2: List<String> = listOf(
            "severity",
            "pageId",
            "componentDeclId",
            "nodeId",
            "code",
            "property",
            "modifierIndex",
            "event",
            "path",
            "message",
        )

        const val PERMUTATION_SEEDS: Int = 60

        val SEEDS_REORDERED: String =
            "no seed reordered the corpus, so the shuffled comparison proved nothing"
    }
}
