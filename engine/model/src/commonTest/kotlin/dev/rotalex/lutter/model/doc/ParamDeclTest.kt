package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The `ParamDecl` contract: a typed input, and the three fields PLAN §13.1 names for it.
 *
 * Two things are worth pinning beyond a round trip, and both are the reason this record can
 * be built at all. PLAN never declares the type, so its shape was read off one sentence —
 * *"provided args match the target's `ParamDecl`s (name, type, required)"* — and a shape
 * reconstructed from prose is worth asserting field by field rather than only in aggregate.
 */
class ParamDeclTest {

    @Test
    fun `a required param is the three fields and nothing else`() {
        // The exact bytes. `required` defaults to true and §6.2's canonical writer encodes no
        // defaults, so the common case is the one that is not written — which is the whole
        // reason the declaration is allowed to default it.
        val param = ParamDecl(ParamName("userId"), TypeRef.Str)

        val text = Json.encodeToString<ParamDecl>(param)

        assertEquals("""{"name":"userId","type":{"type":"str"}}""", text)
        assertEquals(param, Json.decodeFromString<ParamDecl>(text))
        assertEquals(
            listOf("name", "type"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "a field moved on the wire, or a default leaked out",
        )
    }

    @Test
    fun `an optional param says so and the flag is not a fact about the type`() {
        // `required` is a property of the *declaration*, not of the type, so `Str?` and
        // `TypeRef.Nullable(TypeRef.Str)` are not the answer: a required `String` and an
        // optional one are the same type and different facts, and only the flag separates them.
        val optional = ParamDecl(ParamName("page"), TypeRef.Int32, required = false)

        val text = Json.encodeToString<ParamDecl>(optional)

        assertEquals("""{"name":"page","type":{"type":"i32"},"required":false}""", text)
        assertEquals(optional, Json.decodeFromString<ParamDecl>(text))

        // Both spellings of the same declaration round trip, and the explicit `true` is not a
        // different param from the omitted one.
        assertEquals(
            ParamDecl(ParamName("page"), TypeRef.Int32),
            Json.decodeFromString<ParamDecl>(
                """{"name":"page","type":{"type":"i32"},"required":true}""",
            ),
        )
    }

    @Test
    fun `the declared type is a full type and decodes without a schema`() {
        // A nested `TypeRef` union, not a name. This is D4 one level down: a document that
        // types a param as a data model this engine has never heard of still decodes, because
        // the shape is written out rather than named.
        val param = ParamDecl(ParamName("user"), TypeRef.Object(TypeId("User")))

        val text = Json.encodeToString<ParamDecl>(param)

        assertEquals(
            """{"name":"user","type":{"type":"object","id":"User"}}""",
            text,
        )
        assertEquals(param, Json.decodeFromString<ParamDecl>(text))

        // A composite, so the nesting is visible rather than assumed.
        val optional = ParamDecl(
            ParamName("query"),
            TypeRef.Nullable(TypeRef.ListOf(TypeRef.Str)),
        )
        assertEquals(
            """{"name":"query","type":{"type":"nullable","inner":{"type":"list",""" +
                """"element":{"type":"str"}}}}""",
            Json.encodeToString<ParamDecl>(optional),
        )
    }

    @Test
    fun `both halves are required and both validate themselves`() {
        // `name` and `type` have no defaults, because a param with no name cannot be bound and
        // a param with no type cannot be checked. A document that omits either is refused at
        // the field, not defaulted into something that passes validation later.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ParamDecl>("""{"type":{"type":"str"}}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ParamDecl>("""{"name":"userId"}""")
        }

        // `ParamName` is one of §5.2's simple identifiers and validates its own syntax, so a
        // param cannot be declared with a name that could never be bound in generated code.
        assertFailsWith<IllegalArgumentException> { ParamName("has space") }
        assertFailsWith<IllegalArgumentException> { ParamName("has.dot") }

        // And a type tag the union does not have is a refused document rather than a half-read
        // one, which is the limit that keeps the schema-free promise honest.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ParamDecl>("""{"name":"userId","type":{"type":"money"}}""")
        }
    }

    @Test
    fun `a param is a value, so a document diff can tell two apart`() {
        // §6.2's `data class` equality, which is what `DocumentDiff` compares. Two params with
        // the same name and different types are different declarations, and the type is part of
        // the identity rather than a detail carried alongside it.
        val one = ParamDecl(ParamName("userId"), TypeRef.Str)
        val other = ParamDecl(ParamName("userId"), TypeRef.Int32)

        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertNotEquals(one, other, "two params with different types compared equal")
        assertNotEquals(
            Json.encodeToString<ParamDecl>(one),
            Json.encodeToString<ParamDecl>(other),
            "two different params produced the same bytes",
        )
    }
}
