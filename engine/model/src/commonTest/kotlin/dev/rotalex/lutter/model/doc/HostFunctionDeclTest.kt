package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `HostFunctionDecl` contract: §11.6's escape hatch, declared on one line and transcribed
 * field for field.
 *
 * The test that matters is the negative one. `returns` is the only field in this package with
 * no default, and the reason is a format decision rather than an oversight: a host function
 * that returns nothing has to *say* so, so a document that omits the key is refused instead of
 * being read as a unit function. A declaration that "helpfully" defaulted it to `null` would
 * let a whole class of typo decode silently, and nothing downstream would notice until the
 * generated code failed to compile.
 */
class HostFunctionDeclTest {

    @Test
    fun `a unit host function writes its null return`() {
        val decl = HostFunctionDecl(
            name = "submitOrder",
            params = emptyList(),
            returns = null,
        )

        val text = Json.encodeToString<HostFunctionDecl>(decl)

        // `params` and `returns` have no defaults, so both are always written; `suspend`
        // defaults to false and §6.2 encodes no defaults, so it is not.
        assertEquals("""{"name":"submitOrder","params":[],"returns":null}""", text)
        assertEquals(decl, Json.decodeFromString<HostFunctionDecl>(text))
        assertEquals(
            listOf("name", "params", "returns"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
        )
    }

    @Test
    fun `an omitted return is refused rather than read as null`() {
        // The invariant `returns` having no default exists to protect. Both fragments are
        // plausible typos, and both are the same mistake: leaving out the one field that says
        // what the function gives back.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>("""{"name":"submitOrder","params":[]}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>(
                """{"name":"submitOrder","params":[],"suspend":true}""",
            )
        }

        // The other two fields are equally required — non-nullable *and* defaultless, which is
        // the same declaration and the same refusal.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>("""{"params":[],"returns":null}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>("""{"name":"submitOrder","returns":null}""")
        }
    }

    @Test
    fun `suspend is the flag codegen reads and not the shape of the return`() {
        // §11.5: a `host.call` suspends only if the declaration says `suspend = true`. A
        // non-null return type does not imply it and a null one does not forbid it, which is
        // why the flag is a field rather than something inferred from the rest of the record.
        val suspending = HostFunctionDecl(
            name = "fetchProfile",
            params = listOf(ParamDecl(ParamName("userId"), TypeRef.Str)),
            returns = TypeRef.Object(TypeId("Profile")),
            suspend = true,
        )

        val text = Json.encodeToString<HostFunctionDecl>(suspending)

        assertEquals(
            """{"name":"fetchProfile","params":[{"name":"userId","type":{"type":"str"}}],""" +
                """"returns":{"type":"object","id":"Profile"},"suspend":true}""",
            text,
        )
        assertEquals(suspending, Json.decodeFromString<HostFunctionDecl>(text))

        // And the converse — a suspending function with nothing to return, which is the case
        // that would be lost if suspension were derived from the return type.
        val suspendingUnit = HostFunctionDecl("logEvent", emptyList(), null, suspend = true)

        assertEquals(
            """{"name":"logEvent","params":[],"returns":null,"suspend":true}""",
            Json.encodeToString<HostFunctionDecl>(suspendingUnit),
        )
        assertTrue(
            Json.decodeFromString<HostFunctionDecl>(
                """{"name":"logEvent","params":[],"returns":null,"suspend":true}""",
            ).suspend,
        )
    }

    @Test
    fun `a return type is a full type and an unknown tag is refused`() {
        // A return type is a `TypeRef` union rather than a name, for the same reason a param's
        // is: a document written against a newer engine still decodes, and a return type the
        // schema no longer has is a diagnostic rather than a file that will not open.
        val text = """{"name":"render","params":[],"returns":{"type":"map",""" +
            """"value":{"type":"str"}}}"""

        val decoded = Json.decodeFromString<HostFunctionDecl>(text)

        assertEquals(TypeRef.MapOf(TypeRef.Str), decoded.returns)
        assertEquals(text, Json.encodeToString<HostFunctionDecl>(decoded), "the round trip rewrote it")

        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>(
                """{"name":"render","params":[],"returns":{"type":"future"}}""",
            )
        }

        // An explicit `null` return is a fact a document may state, and it is not the same
        // statement as an absent one — which is the distinction the test above refuses to let
        // go of.
        assertNull(
            Json.decodeFromString<HostFunctionDecl>(
                """{"name":"render","params":[],"returns":null}""",
            ).returns,
        )
    }

    @Test
    fun `params keep their order and their declared types`() {
        // A `List`, not a map, because §16.4's generated interface has the parameters in
        // declaration order and a call site has to name them the same way. Reordering two params
        // of different types is a different function, not a cosmetic change.
        val decl = HostFunctionDecl(
            name = "move",
            params = listOf(
                ParamDecl(ParamName("from"), TypeRef.Int32),
                ParamDecl(ParamName("to"), TypeRef.Int32, required = false),
            ),
            returns = TypeRef.Bool,
        )

        val text = Json.encodeToString<HostFunctionDecl>(decl)

        assertEquals(
            """{"name":"move","params":[{"name":"from","type":{"type":"i32"}},""" +
                """{"name":"to","type":{"type":"i32"},"required":false}],""" +
                """"returns":{"type":"bool"}}""",
            text,
        )
        assertEquals(listOf("from", "to"), Json.parseToJsonElement(text).paramNames())

        assertNotEquals(
            text,
            Json.encodeToString<HostFunctionDecl>(decl.copy(params = decl.params.reversed())),
            "reordering the params made no difference",
        )
    }

    /** The param names in this element, in the order the wire form puts them. */
    private fun JsonElement.paramNames(): List<String> =
        jsonObject.getValue("params").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
}
