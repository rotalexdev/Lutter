package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `NodeTable` contract: the four members PLAN §5.5 adds to a class the table would not
 * work without, plus the wire form.
 *
 * `EMPTY`, `equals`, `ids()` and `with`-replaces are each a *consequence* the plan had to
 * state, and each would compile without being written — an identity `equals`, a
 * `map.keys`-as-`ids()`, a `with` that refuses an existing id. So the assertions below are
 * about the thing that was chosen over the thing that merely compiles, which is why
 * `ids()` is checked against an insertion order that is deliberately *not* ascending and
 * against a second table built in a different order.
 *
 * Every encode pins its base type. `encodeToString` infers the type argument from the value,
 * and a custom-serialized class inferred that way would resolve through a different path than
 * the one a document goes through.
 */
class NodeTableTest {

    private fun column(id: String) = Node(id = NodeId(id), type = ComponentType("core.Column"))

    // -----------------------------------------------------------------------------------
    // EMPTY
    // -----------------------------------------------------------------------------------

    @Test
    fun `EMPTY is a constructible zero-length table`() {
        // `nodes` on `UiDocument` carries no default, so without this there is no way to build
        // a document at all — and the builder DSL needs somewhere to start before it has a
        // first node. The assertion that matters is that it *is* constructible: a private
        // constructor with no zero-length companion would make the record unusable.
        assertEquals(0, NodeTable.EMPTY.size)
        assertEquals(emptyList(), NodeTable.EMPTY.ids().toList())
        assertNull(NodeTable.EMPTY[NodeId("n_1")])
        assertFalse(NodeId("n_1") in NodeTable.EMPTY)
        assertEquals("{}", Json.encodeToString<NodeTable>(NodeTable.EMPTY))
        assertEquals(NodeTable.EMPTY, Json.decodeFromString<NodeTable>("{}"))

        // And it is immutable in the way a table is for: `with` returns a new one.
        val grown = NodeTable.EMPTY.with(column("n_1"))
        assertEquals(0, NodeTable.EMPTY.size, "EMPTY grew")
        assertEquals(1, grown.size)
    }

    // -----------------------------------------------------------------------------------
    // ids()
    // -----------------------------------------------------------------------------------

    @Test
    fun `ids are ascending by the id string and not in insertion order`() {
        // Inserted out of order on purpose, and `n_10` before `n_2` is the load-bearing pair:
        // the order is the string's, so a reader who expects `n_2` first is expecting a number
        // where the format has a string. §18.2 states the order as UTF-16 code-unit
        // lexicographic and `IdSyntax` restricts an id to `[A-Za-z0-9_]`, so the code-point
        // reading cannot differ here and `sortedBy { it.value }` is that order.
        val table = NodeTable.EMPTY
            .with(column("n_9"))
            .with(column("n_10"))
            .with(column("n_2"))

        val ids = table.ids().toList()

        assertEquals(listOf(NodeId("n_10"), NodeId("n_2"), NodeId("n_9")), ids)
        assertNotEquals(
            listOf(NodeId("n_9"), NodeId("n_10"), NodeId("n_2")),
            ids,
            "ids() returned the order the nodes were added in",
        )
    }

    @Test
    fun `ids is a function of the contents and not of how the table was built`() {
        // The stronger half of the same claim. If `ids()` delegated to the backing map's own
        // keys it would be reporting `PersistentHashMap`'s unspecified iteration order, and
        // two tables holding the same nodes would enumerate differently for no reason a reader
        // could see. Sorting once in each table is the only way to tell the two apart.
        val oneWay = NodeTable.EMPTY
            .with(column("n_c"))
            .with(column("n_a"))
            .with(column("n_b"))
        val other = NodeTable.EMPTY
            .with(column("n_b"))
            .with(column("n_c"))
            .with(column("n_a"))

        assertEquals(oneWay.ids().toList(), other.ids().toList())
        assertEquals(listOf(NodeId("n_a"), NodeId("n_b"), NodeId("n_c")), oneWay.ids().toList())
    }

    // -----------------------------------------------------------------------------------
    // equals / hashCode
    // -----------------------------------------------------------------------------------

    @Test
    fun `two tables holding the same nodes are equal however they were built`() {
        // §6.2 requires `NodeTable.equals` to be order-independent. Delegating to the wrapped
        // map satisfies it for free — `PersistentMap` *is* a map, and two maps holding the
        // same pairs are equal however they were built.
        val oneWay = NodeTable.EMPTY.with(column("n_1")).with(column("n_2"))
        val other = NodeTable.EMPTY.with(column("n_2")).with(column("n_1"))

        assertEquals(oneWay, other)
        assertEquals(oneWay.hashCode(), other.hashCode())
        assertEquals("""{"n_1":{"id":"n_1","type":"core.Column"},"n_2":{"id":"n_2","type":"core.Column"}}""",
            Json.encodeToString<NodeTable>(oneWay))
        assertEquals("""{"n_1":{"id":"n_1","type":"core.Column"},"n_2":{"id":"n_2","type":"core.Column"}}""",
            Json.encodeToString<NodeTable>(other))

        // And a different node is a different table, which is what makes the equality above
        // worth having: an identity comparison would have passed it too.
        assertNotEquals(oneWay, oneWay.without(NodeId("n_2")))

        // `toString` is written rather than generated. A `data class` would put the backing
        // library's type in it, which is the second of the two reasons §5.5 gives for not
        // being one.
        assertEquals("NodeTable(size=0)", NodeTable.EMPTY.toString())
        assertEquals("NodeTable(size=1)", NodeTable.EMPTY.with(column("n_1")).toString())
    }

    // -----------------------------------------------------------------------------------
    // with / without
    // -----------------------------------------------------------------------------------

    @Test
    fun `with replaces on an id the table already holds`() {
        // §26.2's `PatchOp.SetProp` carries a whole replacement `Node` and a property edit has
        // no other way to reach the table, so `with` replaces rather than refusing. Replacement
        // also keeps the key set identical, so `ids()` and its order are unchanged.
        val original = Node(
            id = NodeId("n_1"),
            type = ComponentType("core.Column"),
            name = "before",
        )
        val replacement = original.copy(name = "after")

        val table = NodeTable.EMPTY.with(original)
        val updated = table.with(replacement)

        assertEquals(1, updated.size, "replacing added a node instead of replacing one")
        assertEquals(replacement, updated[NodeId("n_1")])
        assertEquals("after", updated.require(NodeId("n_1")).name)
        assertEquals("before", table.require(NodeId("n_1")).name, "the original table changed")
        assertEquals(listOf(NodeId("n_1")), updated.ids().toList())
    }

    @Test
    fun `without removes a node and ignores an id that is not there`() {
        val table = NodeTable.EMPTY.with(column("n_1")).with(column("n_2"))

        val reduced = table.without(NodeId("n_1"))

        assertEquals(1, reduced.size)
        assertNull(reduced[NodeId("n_1")])
        assertEquals(listOf(NodeId("n_2")), reduced.ids().toList())
        assertEquals(table, table.without(NodeId("n_absent")), "removing an absent id changed the table")
        assertEquals(NodeTable.EMPTY, reduced.without(NodeId("n_2")))
    }

    // -----------------------------------------------------------------------------------
    // The wire form
    // -----------------------------------------------------------------------------------

    @Test
    fun `the table is an object of nodes keyed by id, sorted by id`() {
        // §18.2: the nodes table is sorted by `NodeId`, each node keyed by id, so an unrelated
        // edit does not touch another node's lines and Git merges cleanly. Inserted out of
        // order so the sort is doing something.
        val table = NodeTable.EMPTY
            .with(column("n_c"))
            .with(column("n_a"))
            .with(column("n_b"))

        val text = Json.encodeToString<NodeTable>(table)

        assertEquals(
            """{"n_a":{"id":"n_a","type":"core.Column"},"n_b":{"id":"n_b","type":"core.Column"},""" +
                """"n_c":{"id":"n_c","type":"core.Column"}}""",
            text,
        )
        assertEquals(
            listOf("n_a", "n_b", "n_c"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "the table is not written in ascending id order",
        )

        // The id is in the body as well as the key. §5.5's comment used to say the encoding
        // was a map of bodies that carried no id, and §5.3:218 requires one: an id reachable
        // from the value is what `without`, `DocumentIndex.parentOf` and a diagnostic with a
        // nodeId want.
        assertEquals(
            "n_a",
            Json.parseToJsonElement(text).jsonObject.getValue("n_a").jsonObject
                .getValue("id").jsonPrimitive.content,
        )

        assertEquals(table, Json.decodeFromString<NodeTable>(text))
    }

    @Test
    fun `the body's id is the key, so a document that disagrees with itself loads by the body`() {
        // The key is derived from `Node.id` and this is what that means. §5.3 makes the id the
        // identity and the section that reports a wrong key is §17.1's, not this one, so a
        // hand-written object whose key and body disagree keeps the body's id rather than
        // being refused: an index that reads a document nobody can open helps nobody.
        val text = """{"n_other":{"id":"n_a","type":"core.Column"}}"""

        val table = Json.decodeFromString<NodeTable>(text)

        assertEquals(1, table.size)
        assertEquals(NodeId("n_a"), table.ids().single())
        assertNull(table[NodeId("n_other")])
        assertEquals("""{"n_a":{"id":"n_a","type":"core.Column"}}""", Json.encodeToString<NodeTable>(table))
    }

    @Test
    fun `require names the id it could not find`() {
        val table = NodeTable.EMPTY.with(column("n_1"))

        val failure = assertFailsWith<IllegalStateException> { table.require(NodeId("n_absent")) }

        assertTrue(failure.message.orEmpty().contains("n_absent"), "unhelpful message: ${failure.message}")
        assertTrue(failure.message.orEmpty().contains("1 node"), "unhelpful message: ${failure.message}")
        // The same id is a hit one node later, so the failure is about the table and not a
        // malformed id.
        assertEquals(NodeId("n_1"), table.require(NodeId("n_1")).id)
    }

    @Test
    fun `a malformed node id is refused at the id, and a malformed table is a serialization error`() {
        // Two different failures, and the difference is the exception type a caller has to
        // catch. The id's `init` refuses before the table sees anything, and kotlinx does not
        // wrap what a value's constructor throws — so this arrives as
        // `IllegalArgumentException`, the same rule `ColorSpecTest` pins for a bad colour.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<NodeTable>("""{"has space":{"id":"has space","type":"core.Column"}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<NodeTable>("""{"n_1":{"id":"n_1","type":"no dot"}}""")
        }

        // A structural problem is the serializer's, not the id's: the body is not a node at
        // all, and that is a `SerializationException`.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NodeTable>("""{"n_1":"core.Column"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NodeTable>("""{"n_1":{"type":"core.Column"}}""")
        }
        // A node is one shape with no alternatives, so there is nothing to discriminate.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NodeTable>("""{"n_1":{"id":"n_1","type":"core.Column","kind":"x"}}""")
        }
    }
}
