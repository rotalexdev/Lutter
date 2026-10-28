package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §23.4: every variant of a persisted sealed hierarchy spells the tag it is written with.
 *
 * The tag, not the class name, is what a stored document carries. A rename without an explicit
 * tag repoints every saved document at a discriminator no reader answers to, and it surfaces as
 * a document that decodes to nothing rather than as an error anybody reads.
 *
 * The self-tests are load-bearing. A rule that has only ever seen conforming code cannot be told
 * apart from one that sees nothing at all — which is how a scanner reports "no violations" for
 * months while checking nothing. The first test pins the subject set so an empty scan fails here.
 */
class NoImplicitSerialNameTest {

    private val hierarchies = """
        package sample

        import kotlinx.serialization.SerialName
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.JsonClassDiscriminator

        @Serializable
        @JsonClassDiscriminator("type")
        public sealed interface Reading {
    """

    private val oneVariantForgotten = hierarchies +
        """

            @Serializable
            @SerialName("flag")
            public data class Flag(public val v: Boolean) : Reading

            @Serializable
            public data class Gauge(public val v: Int) : Reading
        }
        """

    private val everyVariantTagged = hierarchies +
        """

            @Serializable
            @SerialName("flag")
            public data class Flag(public val v: Boolean) : Reading

            @Serializable
            @SerialName("gauge")
            public data class Gauge(public val v: Int) : Reading
        }
        """

    /** A class that holds one of the variants is not itself one, and must not be reported. */
    private val holderNotVariant = hierarchies +
        """

            @Serializable
            @SerialName("flag")
            public data class Flag(public val v: Boolean) : Reading
        }

        @Serializable
        public data class Boxed(public val inner: Reading)
        """

    private fun engineSources(): List<Pair<String, String>> =
        RepositoryRoot.engineSourceDirectories().flatMap { directory ->
            RepositoryRoot.kotlinFilesIn(directory).map { file -> file.name to file.text }
        }

    @Test
    fun `the guard knows which hierarchies it is guarding`() {
        val roots = engineSources().flatMapTo(mutableSetOf()) { (_, text) ->
            SourceRules.persistedRootsIn(text)
        }

        val guardable = setOf("Value", "TypeRef", "Expr", "PropertyValue", "RefTarget")

        assertTrue(roots.containsAll(guardable)) {
            "the scanner found no persisted sealed hierarchy to guard, so the rule below is " +
                "vacuous; it found $roots"
        }
    }

    @Test
    fun `every variant of a persisted hierarchy spells its tag`() {
        val sources = engineSources()
        val roots = sources.flatMapTo(mutableSetOf()) { (_, text) -> SourceRules.persistedRootsIn(text) }

        val violations = sources.flatMap { (name, text) ->
            SourceRules.untaggedVariantOccurrences(text, roots).map { "$name: $it" }
        }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    @Test
    fun `a variant that never spells its tag is rejected`() {
        val violations = SourceRules.untaggedVariantOccurrences(oneVariantForgotten, setOf("Reading"))

        assertFalse(violations.isEmpty()) {
            "the rule accepted a hierarchy with an untagged variant, which is the one thing it exists " +
                "to reject; if it no longer does, it is not protecting anything"
        }
        assertTrue(violations.any { it.contains("Gauge") }) { "it did not name the offending variant: $violations" }
        assertFalse(violations.any { it.contains("Flag") }) { "it blamed a variant that is tagged: $violations" }
    }

    @Test
    fun `a hierarchy in which every tag is spelled is accepted`() {
        assertTrue(SourceRules.untaggedVariantOccurrences(everyVariantTagged, setOf("Reading")).isEmpty())
    }

    @Test
    fun `a class holding a variant is not treated as one`() {
        assertTrue(SourceRules.untaggedVariantOccurrences(holderNotVariant, setOf("Reading")).isEmpty())
    }
}
