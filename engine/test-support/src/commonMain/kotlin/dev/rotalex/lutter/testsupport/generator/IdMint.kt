package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import kotlin.random.Random

/**
 * Mints document-local identifiers off one seeded [Random], and remembers what it minted.
 *
 * ### Why not [dev.rotalex.lutter.model.ids.SequentialIdGenerator]
 *
 * Because §6.2's rule is that an id is assigned once and never derived from a position, and a
 * counter makes the position recoverable: `n_7` says this is the seventh node the generator
 * made, and a patch that inserts one ahead of it renumbers nothing only because nothing renumbers
 * ids — but a *test* that reads `n_7` is reading the construction order, which is exactly the
 * thing §5.6's normalisation is supposed to make invisible. A round-trip test over `n_1 … n_n`
 * cannot tell "the encoder preserved the ids" from "the encoder rebuilt the table in order".
 *
 * ### Why not [dev.rotalex.lutter.model.ids.RandomIdGenerator]
 *
 * Because it takes its own `Random` with `Random.Default` as the default and this module needs
 * the seed to be the caller's. Re-drawing from the generator's own stream is the whole of the
 * difference, and it is why the alphabet and the retry loop are written here rather than
 * borrowed: a collision is not an error in a property test, it is a document with two nodes
 * sharing an id, which `StructuralPass` would report as `struct.duplicate_id` and the validity
 * test would fail on for a reason nobody could see.
 *
 * ### The alphabet, and the two shapes it has to satisfy
 *
 * Every id this mints goes through a `@JvmInline value class` whose `init` validates it, so the
 * alphabet is not a style choice — it is the difference between a generator and a crash.
 * [dev.rotalex.lutter.model.ids.IdSyntax] allows `[A-Za-z0-9_]` for a simple id and refuses
 * anything longer than sixty-four characters, so `0` and `1` and `O` are dropped and the draw is
 * short. `NodeId` would throw on every one of them, and it throws from a `require` in a
 * constructor, which is the least legible place a seed can fail in.
 */
internal class IdMint(private val random: Random) {

    private val taken: MutableSet<String> = mutableSetOf()

    /** A fresh node id. */
    fun node(): NodeId = NodeId(mint("n"))

    /** A fresh page id. */
    fun page(): PageId = PageId(mint("p"))

    /** A fresh document-component id. The tail of the `doc.*` component type it backs. */
    fun componentDecl(): ComponentDeclId = ComponentDeclId(mint("cd"))

    /** A fresh state id. */
    fun state(): StateId = StateId(mint("s"))

    /** A fresh theme id. */
    fun theme(): ThemeId = ThemeId(mint("t"))

    /**
     * [prefix] followed by six characters nobody has been handed yet.
     *
     * The `take` loop is a rejection sampler rather than a counter: the alphabet is thirty-two
     * symbols, a document holds tens of ids and a collision is possible and harmless as long as
     * it is not a duplicate.
     */
    private fun mint(prefix: String): String {
        while (true) {
            val builder = StringBuilder(prefix.length + ALPHABET.length)
            builder.append(prefix)
            repeat(SYMBOLS) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            val candidate = builder.toString()
            if (taken.add(candidate)) return candidate
        }
    }

    private companion object {

        /**
         * Lower-case letters and digits, minus `0`, `1` and `o`.
         *
         * Thirty-two symbols, which is the number `RandomIdGenerator` uses for the same reason
         * this does: an id read off a screen or read aloud cannot be mistranscribed into a
         * *different but still valid* id. `IdSyntax` accepts every character here.
         */
        const val ALPHABET: String = "abcdefghijkmnpqrstuvwxyz23456789"

        /** Characters drawn per id, which keeps a prefix plus them well inside the length bound. */
        const val SYMBOLS: Int = 6
    }
}
