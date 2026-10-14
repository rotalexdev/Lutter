package dev.rotalex.lutter.testsupport.generator

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
 */
class DocumentGeneratorDeterminismTest {

    @Test
    fun `the same seed produces the same document twice`() {
        for (seed in seeds(DETERMINISM_SEED_COUNT)) {
            val first = generatedDocument(seed)
            val second = generatedDocument(seed)

            assertEquals(
                first.document,
                second.document,
                "seed $seed produced two documents that are not equal:\n${renderGenerated(first)}",
            )
            assertTrue(
                encodedBytes(first.document).contentEquals(encodedBytes(second.document)),
                "seed $seed produced two byte strings that differ:\n${renderGenerated(first)}",
            )
        }
    }

    @Test
    fun `one generator reused is the same as two fresh ones`() {
        // The generator holds its accumulators between calls, so a second `generate()` on the
        // same instance is the path where leftover state would show. It must produce exactly what
        // a fresh generator produces for that seed, or every W1 loop over one generator is testing
        // something the API does not promise.
        val reused = DocumentGenerator(generatorTestSchema, 4242L)
        val first = reused.generate()
        val second = reused.generate()
        val fresh = generatedDocument(4242L)

        assertEquals(
            fresh.document,
            second.document,
            "the second generate() on one instance differs from a fresh generator:\n" +
                renderGenerated(second),
        )
        assertEquals(
            first.document,
            second.document,
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
    }
}
