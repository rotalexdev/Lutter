package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §23.4: a number that reaches a document is written through the canonical form, not by
 * printing the machine's idea of it.
 *
 * Narrower than "no raw `Float`", because §5.4's canonical numerics, the two serializers that
 * use them and the `Value` variants that carry one all hold raw floating point on purpose, and a
 * rule that reported those would be reporting the machinery. The boundary drawn here is the place
 * a number is *stored*: a floating-point field on a type the serialization plugin was pointed at
 * must carry the canonical serializer.
 *
 * That boundary is the type's own annotation and not a list of files, so a module added later is
 * covered by construction rather than by somebody remembering to extend the list.
 */
class NoUncanonicalFloatTest {

    private val rawFloatPersisted = """
        package sample

        import kotlinx.serialization.Serializable

        @Serializable
        public data class Padding(public val v: Float)
        """

    private val floatsInCollections = """
        package sample

        import kotlinx.serialization.Serializable

        @Serializable
        public data class Series(public val xs: List<Float>, public val ys: Map<String, Double>)
        """

    private val floatBehindTheCanonicalSerializer = """
        package sample

        import kotlinx.serialization.Serializable

        @Serializable
        public data class Padding(
            @Serializable(CanonicalFloat::class) public val v: Float,
        )

        @Serializable
        public data class Angle(
            @Serializable(CanonicalDouble::class) public val v: Double,
        )
        """

    /** Decoders, evaluators and the canonicalizer itself: floats on the way *out* of a document. */
    private val floatsThatNeverReachOne = """
        package sample

        public object Float32ValueKind : ValueKind<Float> {
            override fun decode(value: Value): Float = (value as? Value.Float32)?.v ?: wrong()
        }

        public fun canonicalize(value: Float): Float = value

        public class CanonicalFloat : KSerializer<Float>
        """

    private fun engineSources(): List<Pair<String, String>> =
        RepositoryRoot.engineSourceDirectories().flatMap { directory ->
            RepositoryRoot.kotlinFilesIn(directory).map { file -> file.name to file.text }
        }

    @Test
    fun `no persisted floating-point field skips the canonical serializer`() {
        val violations = engineSources().flatMap { (name, text) ->
            SourceRules.uncanonicalFloatOccurrences(text).map { "$name: $it" }
        }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    @Test
    fun `a persisted float is rejected`() {
        assertEquals(1, SourceRules.uncanonicalFloatOccurrences(rawFloatPersisted).size)
    }

    @Test
    fun `a persisted float inside a collection is rejected`() {
        assertEquals(2, SourceRules.uncanonicalFloatOccurrences(floatsInCollections).size)
    }

    @Test
    fun `a persisted float behind the canonical serializer is accepted`() {
        assertTrue(SourceRules.uncanonicalFloatOccurrences(floatBehindTheCanonicalSerializer).isEmpty())
    }

    @Test
    fun `a float that never reaches a document is not the rule's business`() {
        assertTrue(SourceRules.uncanonicalFloatOccurrences(floatsThatNeverReachOne).isEmpty())
    }
}
