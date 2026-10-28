package dev.rotalex.lutter.model.ids

import kotlin.random.Random

/**
 * Produces the ids of new nodes.
 *
 * An interface with one method, and a `fun interface` so a lambda satisfies it. The point
 * of the indirection is determinism: document fixtures have to be byte-identical on every
 * machine and every run, so the generator that produced them has to be replaceable with one
 * that counts. [SequentialIdGenerator] is that replacement.
 *
 * Deliberately only node ids. Every other identifier in a document is derived from
 * something the author or the editor chose — a page name, a component name, a state key —
 * and inventing them would mean guessing at intent. A node id, by contrast, is the one
 * identifier the engine has to mint on its own, and minting it is the only decision that
 * would otherwise be a random one.
 */
public fun interface IdGenerator {

    /** A fresh id that no node in the document is using. */
    public fun nextNodeId(): NodeId
}

/**
 * Ids of the form `<prefix><n>`: `n_1`, `n_2`, `n_3`.
 *
 * For tests, fixtures, and any workflow where a document has to be readable line by line.
 * Counting is what makes a golden document reviewable; a random id in a fixture is noise
 * that hides the thing the fixture is about.
 */
public class SequentialIdGenerator(private val prefix: String = "n_") : IdGenerator {

    private var next: Int = 1

    override fun nextNodeId(): NodeId = NodeId("$prefix${next++}")

    /** The id the next call will return, without consuming it. */
    public fun peek(): NodeId = NodeId("$prefix$next")
}

/**
 * Ids of the form `n` followed by ten Crockford base-32 characters.
 *
 * Crockford base-32 rather than `Random.nextInt().toString(radix)`, for two reasons that
 * both matter to the engine. It excludes `I`, `L`, `O` and `U`, so an id read aloud or
 * copied off a screen cannot be mistranscribed into a *different but valid* id; and the
 * alphabet is fixed, so an id generated on one target is a valid id on every other one.
 *
 * The `Random` is injected. `commonMain` has no secure random source, and pretending
 * otherwise would mean either reaching for `expect`/`actual` in a module that is supposed to
 * stay pure or shipping a generator that looks random and is not. A production caller
 * passes a platform-secure source; [random] defaults to `Random.Default`, which is a
 * general-purpose generator and is documented as such. Node ids need to be unique, not
 * unguessable — the document is not a secret — so the default is honest, and a caller that
 * needs more injects more.
 */
public class RandomIdGenerator(private val random: Random = Random.Default) : IdGenerator {

    override fun nextNodeId(): NodeId {
        val builder = StringBuilder(ID_LENGTH + PREFIX.length)
        builder.append(PREFIX)
        repeat(ID_LENGTH) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return NodeId(builder.toString())
    }

    private companion object {
        /** Digits and upper-case letters, minus the four that are ambiguous when read. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        const val ID_LENGTH = 10
        const val PREFIX = "n"
    }
}
