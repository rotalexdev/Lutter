package dev.rotalex.lutter.model.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `ResourceSource` contract: §20's closed union, four arms, transcribed from the plan.
 *
 * This is the only part of the resource system that could be built without inventing a wire
 * contract, so the tests are shaped around what makes it a *union* rather than around a record:
 * every tag is spelled, no two arms share one, and each arm is read back out of encoded JSON
 * rather than off its own annotation — the same shape `ValueTest` and `TypeRefTest` use, and
 * the only way a `@SerialName` that drifts from the wire form gets caught.
 */
class ResourceSourceTest {

    /** An arm and the tag §20 spells for it. */
    private data class Case(val source: ResourceSource, val tag: String, val field: String)

    /** PLAN §20's four arms, in the order the plan lists them. */
    private val cases: List<Case> = listOf(
        Case(ResourceSource.Text("Submit"), "text", "value"),
        Case(ResourceSource.Bundled("assets/logo.png"), "bundled", "path"),
        Case(ResourceSource.Remote("https://forge.test/a.png"), "remote", "url"),
        Case(ResourceSource.Embedded("9f86d081"), "embedded", "hash"),
    )

    @Test
    fun `the union has four arms and no two share a tag`() {
        // §20's list, transcribed. Asserted because a fifth arm is a `FORMAT_VERSION` event for
        // every resource in every document, and because a count nobody checks is a count nobody
        // notices changing. The duplicate check is the sharper half: two arms sharing a tag both
        // compile, both round-trip, and silently corrupt a document written with the other one.
        assertEquals(4, cases.size, "the list of arms is not §20's")
        val tags = cases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two arms share a tag: $tags")
    }

    @Test
    fun `every arm round trips and writes the tag it is named with`() {
        for (case in cases) {
            // Pinned to the sealed base. `encodeToString` infers its type argument from the
            // value, so a concrete arm resolves that arm's own serializer and writes no
            // discriminator at all — `{"value":"Submit"}` — and the helper below would throw.
            val text = Json.encodeToString<ResourceSource>(case.source)

            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")
            assertEquals(case.source, Json.decodeFromString<ResourceSource>(text), "lost '${case.tag}'")
            assertNotEquals(
                case.source::class.simpleName,
                case.tag,
                "'${case.source::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `each arm names its own payload and no arm borrows another's field`() {
        // The four payloads are deliberately *not* the same field: `path` is project-relative
        // and `url` is absolute, and a document that wrote one where the other belongs would
        // decode into a resource provider pointed at nothing. One field per arm keeps that a
        // decode error instead of a runtime one.
        assertEquals(
            """{"type":"text","value":"Submit"}""",
            Json.encodeToString<ResourceSource>(ResourceSource.Text("Submit")),
        )
        assertEquals(
            """{"type":"bundled","path":"assets/logo.png"}""",
            Json.encodeToString<ResourceSource>(ResourceSource.Bundled("assets/logo.png")),
        )
        assertEquals(
            """{"type":"remote","url":"https://forge.test/a.png"}""",
            Json.encodeToString<ResourceSource>(ResourceSource.Remote("https://forge.test/a.png")),
        )
        assertEquals(
            """{"type":"embedded","hash":"9f86d081"}""",
            Json.encodeToString<ResourceSource>(ResourceSource.Embedded("9f86d081")),
        )

        for (case in cases) {
            val keys = Json.parseToJsonElement(Json.encodeToString<ResourceSource>(case.source))
                .jsonObject.keys.toList()
            assertEquals(listOf("type", case.field), keys, "'${case.tag}' is writing the wrong field")
        }
    }

    @Test
    fun `an arm reads back as the arm it was written as`() {
        // The discriminator is what separates four `String`-payload objects that would
        // otherwise be indistinguishable, so this asserts the *type* of the result and not
        // only its field values. A decoder that picked `Text` for a `remote` fragment would
        // satisfy a value-equality check and still point at the wrong thing.
        val remote = assertIs<ResourceSource.Remote>(
            Json.decodeFromString<ResourceSource>(
                """{"type":"remote","url":"https://forge.test/a.png"}""",
            ),
        )
        assertEquals("https://forge.test/a.png", remote.url)

        val bundled = assertIs<ResourceSource.Bundled>(
            Json.decodeFromString<ResourceSource>("""{"type":"bundled","path":"a/b.png"}"""),
        )
        assertEquals("a/b.png", bundled.path)

        // An empty payload is a real value, not a missing one: an empty string resource is a
        // thing a document can legitimately say, and it must not be confused with an absent key.
        assertEquals(
            ResourceSource.Text(""),
            Json.decodeFromString<ResourceSource>("""{"type":"text","value":""}"""),
        )
    }

    @Test
    fun `a tag the union does not have is refused`() {
        // The honest limit of D4 on this record. A *payload* the engine does not understand
        // survives, because it is a string; an *arm* it does not have does not, because the
        // union is closed and a fifth is a `FORMAT_VERSION` event.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceSource>("""{"type":"generated","value":"x"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceSource>("""{"path":"a/b.png"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceSource>("""{"type":"remote","path":"a/b.png"}""")
        }
    }

    /**
     * The discriminator, read out of encoded JSON rather than off the annotation.
     *
     * Parsing the text back is the point: reading the tag off the serializer descriptor would
     * be reading the same declaration the test is supposed to be checking.
     */
    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
