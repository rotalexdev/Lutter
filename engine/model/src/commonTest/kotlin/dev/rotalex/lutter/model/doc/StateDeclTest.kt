package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `StateDecl` contract and the `Persistence` union it defaults to.
 *
 * Two claims carry the weight. **`Persistence` is a sealed interface and not an enum**, and
 * the reason is falsifiability: nothing in this project can test whether its *variant set* is
 * right — §31.3 defers persistent state out of MVP and no engine code reads a non-`None`
 * value — so it should be the choice that is cheapest to be wrong about. Adding a variant to
 * an enum is additive; adding a **payload** to an existing entry is a wire break, and a sealed
 * interface grows one without touching the tags already written.
 *
 * And **every tag is spelled**, which is why each encode below pins `Persistence` as the base
 * type: `encodeToString` infers its type argument from the value, so an unannotated
 * `encodeToString(Persistence.None)` resolves the concrete `data object` and writes `{}` with
 * no discriminator at all — a test that passed on the inferred call would be asserting nothing
 * about the wire.
 */
class StateDeclTest {

    @Test
    fun `every arm of the union spells the tag PLAN spells`() {
        val arms = listOf(
            Persistence.None to "none",
            Persistence.Saveable to "saveable",
        )

        assertEquals(2, arms.size, "the list of arms is not §12.1's")
        val tags = arms.map { it.second }
        assertEquals(tags.size, tags.toSet().size, "two arms share a tag: $tags")

        for ((arm, tag) in arms) {
            // Pinned to the sealed base, which is the whole point: see the KDoc.
            val text = Json.encodeToString<Persistence>(arm)

            assertEquals("""{"type":"$tag"}""", text)
            assertEquals(arm, Json.decodeFromString<Persistence>(text))
            assertNotEquals(
                arm::class.simpleName,
                tag,
                "'${arm::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `the default persistence is None and a default is not written`() {
        // §6.2's canonical writer encodes no defaults, so the common case — a state that does
        // not survive a restart — is the one that is not on the wire, and the field appears
        // only when it says something.
        val plain = StateDecl(StateId("s_count"), "count", TypeRef.Int32)
        val plainText = """{"id":"s_count","name":"count","type":{"type":"i32"}}"""

        assertEquals(Persistence.None, plain.persistence)
        assertEquals(plainText, Json.encodeToString<StateDecl>(plain))
        assertEquals(
            listOf("id", "name", "type"),
            Json.parseToJsonElement(plainText).jsonObject.keys.toList(),
            "a default leaked onto the wire, or a field moved",
        )
        assertEquals(plain, Json.decodeFromString<StateDecl>(plainText))

        val saveable = plain.copy(persistence = Persistence.Saveable)
        val saveableText =
            """{"id":"s_count","name":"count","type":{"type":"i32"},"persistence":{"type":"saveable"}}"""

        assertEquals(saveableText, Json.encodeToString<StateDecl>(saveable))
        assertEquals(saveable, Json.decodeFromString<StateDecl>(saveableText))

        // An explicit `None` and an omitted one are the same declaration, which is what makes
        // the field's default part of the format rather than a convenience.
        assertEquals(
            plain,
            Json.decodeFromString<StateDecl>(
                """{"id":"s_count","name":"count","type":{"type":"i32"},"persistence":{"type":"none"}}""",
            ),
        )
    }

    @Test
    fun `initial and derived are both optional and neither is checked here`() {
        // §12.1 says "exactly one of initial/derived" and this record does not enforce it. A
        // constraint refused at construction turns a document that ought to load into one that
        // does not, and §17.1 is the pass that reports it with a `nodeId` attached.
        val derived = StateDecl(
            id = StateId("s_doubled"),
            name = "doubled",
            type = TypeRef.Int32,
            derived = Expr.Binary(
                op = BinaryOp.Mul,
                left = Expr.Ref(RefTarget.State(StateId("s_count"))),
                right = Expr.Const(Value.Int32(2)),
            ),
        )

        assertNull(derived.initial)
        val text = Json.encodeToString<StateDecl>(derived)
        assertEquals(derived, Json.decodeFromString<StateDecl>(text))
        assertEquals(
            """{"id":"s_doubled","name":"doubled","type":{"type":"i32"},"derived":{"type":"binary",""" +
                """"op":"mul","left":{"type":"ref","target":{"type":"state","id":"s_count"}},""" +
                """"right":{"type":"const","value":{"type":"i32","v":2}}}}""",
            text,
        )

        // Both set is the invalid shape, and it decodes. The model states the vocabulary, not
        // which combination of it is legal.
        val both = derived.copy(initial = Value.Int32(0))
        assertEquals(both, Json.decodeFromString<StateDecl>(Json.encodeToString<StateDecl>(both)))
    }

    @Test
    fun `a state is a value, so a document diff can tell two apart`() {
        val one = StateDecl(StateId("s_count"), "count", TypeRef.Int32)
        val other = StateDecl(StateId("s_count"), "count", TypeRef.Str)

        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertNotEquals(one, other, "two states with different types compared equal")
        assertNotEquals(
            Json.encodeToString<StateDecl>(one),
            Json.encodeToString<StateDecl>(other),
            "two different states produced the same bytes",
        )
    }

    @Test
    fun `a tag the union does not have, and a missing field, are both refused`() {
        // The honest limit of D4 on this record: a *payload* the engine does not understand
        // survives, an *arm* it does not have does not, because the union is closed.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Persistence>("""{"type":"store","name":"prefs"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Persistence>("""{"name":"prefs"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<StateDecl>("""{"name":"count","type":{"type":"i32"}}""")
        }
        // And the id validates itself on the way in, which is the one thing the model can check
        // without a schema. An `IllegalArgumentException` and not a `SerializationException`,
        // because kotlinx does not wrap what a constructor throws — the same rule
        // `ColorSpecTest` pins for a bad colour.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<StateDecl>("""{"id":"has space","name":"c","type":{"type":"i32"}}""")
        }
    }
}
