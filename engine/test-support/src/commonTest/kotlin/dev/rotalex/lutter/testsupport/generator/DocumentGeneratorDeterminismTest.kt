package dev.rotalex.lutter.testsupport.generator

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One seed, one document, byte for byte.
 *
 * ### What "deterministic" has to mean here, and where it stops
 *
 * It means: two calls with the same [DocumentGenerator.seed] produce equal documents and equal
 * canonical bytes, and two calls with different seeds do not. It does **not** yet mean "the same
 * on every operating system and every engine target" — that is W2's `DeterminismTest`, and it
 * needs a second machine to mean anything. What this file can prove is the part that makes W2
 * possible: that the generator's output is a function of the seed alone and of nothing else, so
 * a cross-target difference would be a difference in `Random` rather than in the generator.
 *
 * ### Why bytes and not just equality
 *
 * `UiDocument.equals` is structural over data classes and `NodeTable.equals` is order-independent
 * by §6.2, which is exactly right for the format and exactly wrong for this question: two
 * documents that differ only in map iteration order are equal *and* are the same document under
 * §18's canonical writer, but a generator that emitted them in different orders would still be a
 * generator whose output depends on something other than its seed. Encoding both sides and
 * comparing the bytes is the assertion that closes that gap, and it is the same comparison W1's
 * `RoundTripPropertyTest` will make, which is why it is made here first and on generated
 * documents rather than on fixtures.
 *
 * ### Why the different-seeds test compares the whole set and not a pair
 *
 * A pair is the cheap version and it has a hole: it passes for a generator whose second seed
 * happens to differ while seeds 3 and 4 do not. Comparing every seed against every other seed
 * costs `n²` byte comparisons over a small range and turns "these two seeds differ" into "no two
 * seeds in this range collide", which is the claim the coverage tests downstream rely on.
 *
 * ### What the three cases do and do not each prove
 *
 * Read together they are stronger than any one of them, and the middle one is the weakest:
 *
 *  * **`the same seed produces the same document twice`** — two independent generators, one seed.
 *    This is the case that failed, and the only one of the three that can see an input the
 *    generator did not get from its seed.
 *  * **`one generator reused is the same as two fresh ones`** — one generator, two calls. It
 *    proves that `generate()` resets its accumulators and reseeds, and that is genuinely worth
 *    proving because a W1 loop reuses one generator. It cannot see a global random source: a
 *    reused generator consumes that source the same way on both calls, so `first == second` holds
 *    either way. Its name says more than it proves.
 *  * **`no two seeds in the range produce the same document`** — each seed built once. It is
 *    about distinctness across seeds, which is what its name says, and it is the case least able
 *    to see anything about reproducibility.
 */
class DocumentGeneratorDeterminismTest {

    @Test
    fun `the same seed produces the same document twice`() {
        for (seed in seeds(DETERMINISM_SEED_COUNT)) {
            val first = generatedDocument(seed)
            val second = generatedDocument(seed)

            // `firstDifference` rather than `assertEquals` over the documents: the documents are
            // `UiDocument`s, so a plain assertion answers "different" and prints two renderings
            // that are byte-identical, since the node table prints as `NodeTable(size=13)`. The
            // walk names the field. The byte comparison below is still the real assertion — it is
            // what §18's canonical writer is for — and this one says *where*.
            assertEquals(
                null,
                firstDifference(first.document, second.document),
                "seed $seed produced two documents that differ:\n${renderGenerated(first)}",
            )
            assertTrue(
                encodedBytes(first.document).contentEquals(encodedBytes(second.document)),
                "seed $seed produced two byte strings that differ:\n${renderGenerated(first)}",
            )
        }
    }

    @Test
    fun `no generated document depends on the platform's default random source`() {
        // The minimal reproduction of the defect this file was written for, and the one test in
        // it that could not have passed by accident.
        //
        // The bug was a single `pool.randomOrNull()` in the action builder. The stdlib's
        // no-argument `randomOrNull()` — like `random()` and `shuffled()` — reads
        // `kotlin.random.Random.Default`, the platform's global generator, and not the seed the
        // generator was handed. Every other test in this file would have passed with that line
        // still in place, and the reason is worth writing down:
        //
        //  * Building a document once consults `Random.Default` once and cannot see a difference.
        //  * Reusing one generator consults it the same number of times on the first and the
        //    second call, so `first == second` still held — which is exactly why the reuse test
        //    passed while this one failed.
        //  * Only a *second, independent* generator for the same seed lands on a different point
        //    in `Random.Default`'s stream.
        //
        // So the experiment is to disturb that global stream between the two builds. Before the
        // fix, burning it changed the `nav.navigate` target in any document that happened to
        // navigate. After the fix, no amount of disturbance reaches this generator at all, because
        // `drawFrom` is the only element-picking helper here and it reads the seeded field.
        var navigated = 0

        for (seed in seeds(ISOLATION_SEED_COUNT)) {
            val first = generatedDocument(seed)
            if (first.plan.navigations.isNotEmpty()) navigated += 1

            disturbTheDefaultRandomSource()

            val second = generatedDocument(seed)
            assertEquals(
                null,
                firstDifference(first.document, second.document),
                "seed $seed changed once the platform's default random source advanced.\n" +
                    "A document must be a function of its seed and of nothing else.\n" +
                    renderGenerated(first),
            )
        }

        // Without this the test could pass on a corpus that never picked `nav.navigate` — and so
        // never drew from the global source at all. Read from the generator's declared intent,
        // not from a scan of the documents.
        assertTrue(
            navigated > 0,
            "no generated document in $ISOLATION_SEED_COUNT seeds used nav.navigate, so the " +
                "isolation case proved nothing",
        )
    }

    /**
     * Advances `Random.Default` a long way.
     *
     * Mutating a JVM global from a test is impolite in general and harmless here: `Random.Default`
     * is a plain PRNG that nothing in this repository seeds from, so advancing it can only change
     * what the *next* reader of it gets — which is the point. The alternative, perturbing it by a
     * known number of draws, would couple this test to how many draws the generator happens to
     * make.
     */
    private fun disturbTheDefaultRandomSource() {
        repeat(DISTURBANCE) { Random.nextInt() }
    }

    @Test
    fun `one generator reused is the same as two fresh ones`() {
        // The generator holds its accumulators between calls, so a second `generate()` on the
        // same instance is the path where leftover state would show. It must produce exactly what
        // a fresh generator produces for that seed, or every W1 loop over one generator is testing
        // something the API does not promise.
        //
        // Read this next to the isolation case: on its own it cannot see a global draw, because a
        // reused generator consumes `Random.Default` the same way on both calls. It is still worth
        // having — it is the test that would have caught leftover accumulators — but it is not the
        // test that proves determinism, and its name overstates it.
        val reused = DocumentGenerator(generatorTestSchema, 4242L)
        val first = reused.generate()
        val second = reused.generate()
        val fresh = generatedDocument(4242L)

        assertEquals(
            null,
            firstDifference(fresh.document, second.document),
            "the second generate() on one instance differs from a fresh generator:\n" +
                renderGenerated(second),
        )
        assertEquals(
            null,
            firstDifference(first.document, second.document),
            "one generator produced two different documents for the same seed:\n" +
                renderGenerated(second),
        )
    }

    @Test
    fun `no two seeds in the range produce the same document`() {
        val encoded = seeds(DETERMINISM_SEED_COUNT).associateWith { seed ->
            encodedBytes(generatedDocument(seed).document).decodeToString()
        }
        val firstSeen = mutableMapOf<String, Long>()

        for (entry in encoded) {
            val clash = firstSeen.put(entry.value, entry.key)
            assertTrue(
                clash == null,
                "seeds $clash and ${entry.key} produced identical bytes; " +
                    "the seed has stopped reaching the document.\n" + renderGenerated(generatedDocument(entry.key)),
            )
        }
        assertEquals(
            DETERMINISM_SEED_COUNT,
            encoded.size,
            "the seed range produced fewer documents than seeds asked for",
        )
    }

    private companion object {
        /**
         * Enough to catch a stream that ignores its seed and not so many that the test is slow.
         *
         * Twenty documents is a thousand encodings' worth of pairs at a thousandth of a second
         * each; the coverage tests below use far more, and this one is about *distinctness*, which
         * saturates early.
         */
        const val DETERMINISM_SEED_COUNT: Int = 20

        /**
         * Seeds for the isolation case, which only has to be wide enough that one document
         * navigates.
         *
         * Thirty is generous for a coin with a few flips per document, and the case asserts that
         * at least one navigated rather than trusting it.
         */
        const val ISOLATION_SEED_COUNT: Int = 30

        /**
         * Draws burned from `Random.Default` between the two builds.
         *
         * A thousand is far more than any document's own draw count, so a generator still reading
         * the global would land somewhere unrelated. It costs microseconds.
         */
        const val DISTURBANCE: Int = 1000
    }
}
