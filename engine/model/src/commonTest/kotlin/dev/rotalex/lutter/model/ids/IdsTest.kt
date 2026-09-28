package dev.rotalex.lutter.model.ids

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The identifier contract: what is accepted, what is rejected, and what an id looks like
 * once it is in a log or in a document.
 */
class IdsTest {

    // -----------------------------------------------------------------------------------
    // Syntax
    // -----------------------------------------------------------------------------------

    @Test
    fun `a simple id accepts letters digits and underscore`() {
        val accepted = listOf(
            "n_1",
            "p_home",
            "A",
            "_leading",
            "trailing_",
            "0",
            "a".repeat(IdSyntax.MAX_LENGTH),
        )

        for (value in accepted) {
            NodeId(value)
            PageId(value)
            PropertyKey(value)
        }
    }

    @Test
    fun `a simple id rejects anything else`() {
        val rejected = listOf(
            "",                             // empty
            "a".repeat(IdSyntax.MAX_LENGTH + 1), // one over the bound
            "has space",                     // space
            "has.dot",                       // a dot makes it namespaced
            "has-dash",                      // a dash would need quoting in Kotlin
            "emoji-\u2764",              // non-ASCII
            "quote\"",                  // would break a JSON string if it ever got there
            "\n",                           // control character
        )

        for (value in rejected) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                NodeId(value)
            }
        }
    }

    @Test
    fun `a namespaced id needs at least two dot separated segments`() {
        val accepted = listOf(
            "core.Column",
            "m3.Text",
            "layout.padding",
            "nav.navigate",
            "list.isNotEmpty",
            "forge.material3",
            "a.b.c", // depth is not capped; a plugin may need it
        )

        for (value in accepted) {
            ComponentType(value)
            ModifierType(value)
            ActionId(value)
            FunctionId(value)
            PluginId(value)
        }
    }

    @Test
    fun `a namespaced id rejects a bare segment and an empty one`() {
        val overBound = (1..30).joinToString(".") { "segment$it" } // 30 segments, well over 64

        val rejected = listOf(
            "Column",        // one segment: a simple id wearing a dot
            "core.",         // empty second segment
            ".Column",       // empty first segment
            "core..Column",  // empty segment between
            "core.Column.",  // trailing dot
            overBound,       // over the length bound
            "core.Column Extra", // space
            "core.Column-Extra", // dash
        )

        for (value in rejected) {
            assertFailsWith<IllegalArgumentException>("expected '$value' to be rejected") {
                ComponentType(value)
            }
        }
    }

    @Test
    fun `the same string is not both a simple and a namespaced id`() {
        // The two shapes are decided by the dot, and the choice of which id type to use is
        // what carries the meaning. Getting this wrong is a compile error, not a runtime one,
        // so the assertion here is that both reject the other's syntax.
        assertFailsWith<IllegalArgumentException> { NodeId("core.Column") }
        assertFailsWith<IllegalArgumentException> { ComponentType("Column") }
    }

    // -----------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------

    @Test
    fun `an id prints as its own value`() {
        // Ids are the vocabulary diagnostics are written in. `NodeId(value=n_1)` in a stack
        // trace costs a reader a parse on every line.
        assertEquals("n_1", NodeId("n_1").toString())
        assertEquals("core.Column", ComponentType("core.Column").toString())
        assertEquals("p_home", PageId("p_home").toString())
    }

    // -----------------------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------------------

    @Test
    fun `an id round trips as a bare json string`() {
        // Bare, not an object. A document is read by people and by other tools, and
        // `{"value":"n_1"}` for every key in a node table is a document nobody wants to
        // read or diff.
        val encoded = Json.encodeToString(NodeId.serializer(), NodeId("n_1"))
        assertEquals("\"n_1\"", encoded)
        assertEquals(NodeId("n_1"), Json.decodeFromString(NodeId.serializer(), encoded))
    }

    @Test
    fun `a namespaced id round trips as a bare json string`() {
        val encoded = Json.encodeToString(ComponentType.serializer(), ComponentType("m3.Text"))
        assertEquals("\"m3.Text\"", encoded)
        assertEquals(
            ComponentType("m3.Text"),
            Json.decodeFromString(ComponentType.serializer(), encoded),
        )
    }

    @Test
    fun `decoding validates the syntax`() {
        // The constructor runs during deserialization, so a malformed id in a stored
        // document fails where it is read rather than somewhere downstream of it.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(NodeId.serializer(), "\"has space\"")
        }
    }

    // -----------------------------------------------------------------------------------
    // Generators
    // -----------------------------------------------------------------------------------

    @Test
    fun `the sequential generator counts`() {
        val generator = SequentialIdGenerator()

        assertEquals("n_1", generator.nextNodeId().toString())
        assertEquals("n_2", generator.nextNodeId().toString())
        assertEquals("n_3", generator.nextNodeId().toString())
    }

    @Test
    fun `the sequential generator honours its prefix`() {
        val generator = SequentialIdGenerator(prefix = "node_")

        assertEquals("node_1", generator.nextNodeId().toString())
        assertEquals(NodeId("node_2"), generator.peek())
    }

    @Test
    fun `the random generator produces ids this model accepts`() {
        val generator = RandomIdGenerator()

        repeat(200) {
            val id = generator.nextNodeId()
            // Constructing it already proved the syntax; the shape is asserted because a
            // generator that quietly changed length would be a wire-format change.
            assertEquals(11, id.value.length, "expected 'n' plus ten characters, got '$id'")
            assertTrue(
                id.value.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" },
                "unexpected character in '$id'",
            )
        }
    }

    @Test
    fun `the random generator does not use the four ambiguous characters`() {
        // Crockford base-32 exists to stop an id being misread into a different valid id, so
        // the alphabet is the feature. Draw enough ids that a regression would be seen.
        val generator = RandomIdGenerator()
        val seen = buildSet { repeat(500) { add(generator.nextNodeId().value) } }

        val ambiguous = setOf('I', 'L', 'O', 'U')
        assertTrue(
            seen.none { id -> id.any { it in ambiguous } },
            "an ambiguous character appeared in $seen",
        )
    }
}
