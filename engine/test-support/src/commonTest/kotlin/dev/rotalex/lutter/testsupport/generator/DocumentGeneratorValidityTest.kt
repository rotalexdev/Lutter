package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every generated document analyses with no error-severity diagnostic.
 *
 * ### Why this is the load-bearing test of the four
 *
 * A generator that produces *invalid* documents is not a weaker generator, it is a generator
 * that produces nothing: every property test downstream is allowed to assume its input is
 * analysable, so an invalid document turns `RoundTripPropertyTest` into a test of the analyzer's
 * refusals and `PatchInversePropertyTest` into a test of how a rejected patch is applied. Both
 * would still pass — neither property can fail on a document nothing can render — which is the
 * exact failure §5.6 makes invisible: a document in the wrong order is indistinguishable from
 * one in the right order until the engine reads it.
 *
 * So this test is not "the generator is nice". It is the precondition every other test in the
 * phase rests on, and it is here rather than in W1 because a property test is the wrong place to
 * discover that the corpus is unusable.
 *
 * ### Every failure names its seed
 *
 * The seed is the replay handle: `DocumentGenerator(generatorTestSchema, 417).generate()` is the
 * whole reproduction, and the message says so before it says anything else. The document follows
 * in full rather than as a count, because a count says "one of forty nodes" and the renderer
 * says which one — the same reasoning Phase 9's `renderDocument` was written for.
 *
 * ### Why the range is two hundred and not a thousand
 *
 * §36's thousand belongs to the property tests, which are cheap per document; this one runs the
 * whole seven-pass analysis over every seed, so it is the most expensive test in the module. Two
 * hundred documents is enough that a systematic defect — a slot filter always wrong, a scope
 * always missing — shows up on essentially every seed rather than on one, and the failure list
 * below is capped so a systematic defect produces a readable report instead of a wall.
 *
 * The documents are not sampled by draw. A contiguous range is reproducible, and a defect that
 * only appears on odd seeds would be a defect worth finding rather than one worth missing.
 */
class DocumentGeneratorValidityTest {

    @Test
    fun `every generated document analyses without an error`() {
        val refused = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (!result.hasErrors) continue
            for (diagnostic in result.diagnostics) {
                if (diagnostic.severity != Severity.Error) continue
                refused += render(seed, generated, diagnostic)
                if (refused.size >= REPORT_LIMIT) break
            }
            if (refused.size >= REPORT_LIMIT) break
        }

        assertTrue(
            refused.isEmpty(),
            "${refused.size} error diagnostics over ${VALIDITY_SEED_COUNT} generated documents.\n" +
                "Replay any one with DocumentGenerator(generatorTestSchema, <seed>).generate().\n" +
                refused.joinToString("\n\n"),
        )
    }

    @Test
    fun `analysis resolves rather than merely reporting`() {
        // `hasErrors == false` is not the same as "the document lowered": `Analyzer` returns
        // `AnalysisResult(resolved = null)` whenever any pass errored, and a corpus test that
        // only checked the flag would accept a document that stops at the analysis stage. W1
        // will run `ResolutionPass`'s product through the round trip, so the corpus is only
        // usable if that product exists.
        val unresolved = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (result.resolved != null) continue
            unresolved += "seed $seed resolved nothing:\n" + renderGenerated(generated)
            if (unresolved.size >= REPORT_LIMIT) break
        }

        assertTrue(
            unresolved.isEmpty(),
            "${unresolved.size} generated documents the analyzer refused to lower.\n" +
                unresolved.joinToString("\n\n"),
        )
    }

    @Test
    fun `a document with declared components is analysed through the overlay`() {
        // §8.4's overlay is built by `Analyzer` only when `document.components` is not empty, and
        // a `doc.*` instance analysed against the static schema alone is `component.unknown`.
        // Phase 9 found this from the editing side, where a `DropTargets` check could not resolve
        // a `doc.*` parent; this is the assertion that the corpus contains the case and that the
        // one public path over it handles it.
        var withInstance = 0
        val failures = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            if (generated.plan.instantiatedComponents.isEmpty()) continue
            withInstance += 1
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (!result.hasErrors) continue
            failures += "seed $seed:\n" + renderGenerated(generated)
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            withInstance > 0,
            "no generated document placed a doc.* instance, so nothing exercised the overlay",
        )
        assertTrue(
            failures.isEmpty(),
            "${failures.size} documents with a doc.* instance failed analysis.\n" +
                failures.joinToString("\n\n"),
        )
    }

    private fun render(seed: Long, generated: GeneratedDocument, diagnostic: Diagnostic): String =
        buildString {
            append("seed ").append(seed)
            append(" [").append(diagnostic.code).append("] ")
            append(diagnostic.message).append('\n')
            append("  at ").append(diagnostic.location).append('\n')
            append(renderGenerated(generated))
        }

    private companion object {
        const val VALIDITY_SEED_COUNT: Int = 200

        /** Enough failures to show every distinct cause, few enough to stay readable. */
        const val REPORT_LIMIT: Int = 5
    }
}
