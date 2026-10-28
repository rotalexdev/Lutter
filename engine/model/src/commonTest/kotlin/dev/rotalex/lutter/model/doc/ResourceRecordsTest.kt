package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ResourceId
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The resource records around `ResourceSource`: `ResourceKind`, `Qualifier`,
 * `ResourceVariant` and `ResourceDecl`.
 *
 * Two claims carry the weight. **There is no `Color` kind**, and §20's own `kind` comment used
 * to list one while the MVP bullet in the same section says *"Colors/typography are tokens
 * (§14), not resources"* — the bullet wins because it is categorical and names the owner.
 * Three things agree with it: §14.1 gives colours a home, §9.2 gives the editor a colour
 * picker rather than a resource picker, and `ResourceSource` has no arm that could carry one.
 *
 * And **a qualifier is a predicate, not a position**: `qualifiers` stays a `Set` even though a
 * set's iteration order differs per target, because §18.2's canonical writer orders a set by
 * each element's own canonical encoding and variant selection is set membership. Making it a
 * `List` would fix the bytes and invite every reader to read priority into a position that
 * selection may not honour.
 */
class ResourceRecordsTest {

    @Test
    fun `the kind enum has five entries, every tag spelled, and no Color`() {
        val kinds = listOf(
            ResourceKind.String to "string",
            ResourceKind.Image to "image",
            ResourceKind.Font to "font",
            ResourceKind.Icon to "icon",
            ResourceKind.File to "file",
        )

        assertEquals(5, kinds.size, "the list of kinds is not §20's")
        assertEquals(5, ResourceKind.entries.size, "a sixth kind is a FORMAT_VERSION event")
        val tags = kinds.map { it.second }
        assertEquals(tags.size, tags.toSet().size, "two kinds share a tag: $tags")

        for ((kind, tag) in kinds) {
            // An enum is a bare JSON string, so the tag is the whole wire form.
            val text = Json.encodeToString<ResourceKind>(kind)

            assertEquals("\"$tag\"", text)
            assertEquals(kind, Json.decodeFromString<ResourceKind>(text))
            assertNotEquals(kind.name, tag, "'${kind.name}' is writing itself as the tag")
        }

        // The negative case for the correction. `Color` was in the kind comment and out of the
        // enum, and an enum refuses a tag it does not have — so the string that comment used
        // to promise is a document that will not open rather than a colour resource.
        assertFailsWith<SerializationException> { Json.decodeFromString<ResourceKind>("\"color\"") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceDecl>(
                """{"id":"r_brand","name":"Brand","kind":"color","variants":[]}""",
            )
        }
    }

    @Test
    fun `every qualifier case spells its tag and names its own payload`() {
        val cases = listOf(
            Qualifier.Locale("en-GB") to "locale",
            Qualifier.Density(320) to "density",
            Qualifier.Theme("dark") to "theme",
        )

        assertEquals(3, cases.size, "the list of qualifiers is not §20's")
        val tags = cases.map { it.second }
        assertEquals(tags.size, tags.toSet().size, "two qualifiers share a tag: $tags")

        // Pinned to the sealed base: `encodeToString` infers the type argument from the value,
        // and a concrete case would resolve its own serializer and write no discriminator.
        for ((qualifier, tag) in cases) {
            val text = Json.encodeToString<Qualifier>(qualifier)

            assertEquals(tag, discriminatorOf(text), "wrong discriminator in '$text'")
            assertEquals(qualifier, Json.decodeFromString<Qualifier>(text), "lost '$tag'")
            assertNotEquals(
                qualifier::class.simpleName,
                tag,
                "'${qualifier::class.simpleName}' is writing itself as the tag",
            )
        }

        // The three payloads are deliberately *not* the same field. `tag` is a BCP-47 language
        // tag, `dpi` is a number and `variant` is an open string, and a document that wrote one
        // where another belongs would decode into a variant nothing selects.
        assertEquals("""{"type":"locale","tag":"en-GB"}""",
            Json.encodeToString<Qualifier>(Qualifier.Locale("en-GB")))
        assertEquals("""{"type":"density","dpi":320}""",
            Json.encodeToString<Qualifier>(Qualifier.Density(320)))
        assertEquals("""{"type":"theme","variant":"dark"}""",
            Json.encodeToString<Qualifier>(Qualifier.Theme("dark")))
    }

    @Test
    fun `a variant is a qualifier set and a source`() {
        val variant = ResourceVariant(
            qualifiers = setOf(Qualifier.Locale("en")),
            source = ResourceSource.Bundled("assets/logo.png"),
        )

        val text = Json.encodeToString<ResourceVariant>(variant)

        // A set is written as a bare JSON array — one element, so there is one order and the
        // bytes are exact. §18.2's rule sorts a *multi*-element set by each element's own
        // canonical encoding, and that rule belongs to the writer in `:engine:serialization`,
        // so nothing here may assert an order the model does not promise.
        assertEquals(
            """{"qualifiers":[{"type":"locale","tag":"en"}],""" +
                """"source":{"type":"bundled","path":"assets/logo.png"}}""",
            text,
        )
        assertEquals(variant, Json.decodeFromString<ResourceVariant>(text))
        assertEquals(
            """{"qualifiers":[],"source":{"type":"text","value":"Submit"}}""",
            Json.encodeToString<ResourceVariant>(
                ResourceVariant(emptySet(), ResourceSource.Text("Submit")),
            ),
        )
    }

    @Test
    fun `qualifiers are a set, so the order they were written in is not part of the value`() {
        // The property that makes `Set` the right type and a `List` the wrong one. A variant is
        // a predicate over the environment and its qualifiers carry no priority, so two
        // variants holding the same qualifiers are one variant — and a list would have made
        // the first-listed one read as the preferred one, which selection is not allowed to
        // honour. The bytes are deliberately not asserted: an array's order is §18.2's
        // canonical writer to decide, and it does not exist yet.
        val oneOrder = ResourceVariant(
            qualifiers = setOf(Qualifier.Locale("en"), Qualifier.Density(480)),
            source = ResourceSource.Bundled("assets/logo@2x.png"),
        )
        val otherOrder = ResourceVariant(
            qualifiers = setOf(Qualifier.Density(480), Qualifier.Locale("en")),
            source = ResourceSource.Bundled("assets/logo@2x.png"),
        )

        assertEquals(oneOrder, otherOrder)
        assertEquals(oneOrder.hashCode(), otherOrder.hashCode())
        val text = Json.encodeToString<ResourceVariant>(oneOrder)
        assertEquals(oneOrder, Json.decodeFromString<ResourceVariant>(text))
        assertEquals(2, Json.decodeFromString<ResourceVariant>(text).qualifiers.size)

        // A duplicate qualifier is one qualifier, which is the other thing a set buys.
        assertEquals(
            1,
            ResourceVariant(
                qualifiers = setOf(Qualifier.Locale("en"), Qualifier.Locale("en")),
                source = ResourceSource.Text("x"),
            ).qualifiers.size,
        )
    }

    @Test
    fun `a declaration is an id, a name, a kind and its variants`() {
        val declaration = ResourceDecl(
            id = ResourceId("r_submit"),
            name = "Submit",
            kind = ResourceKind.String,
            variants = listOf(
                ResourceVariant(
                    qualifiers = setOf(Qualifier.Locale("en")),
                    source = ResourceSource.Text("Submit"),
                ),
                ResourceVariant(emptySet(), ResourceSource.Text("Send")),
            ),
        )

        val text = Json.encodeToString<ResourceDecl>(declaration)

        assertEquals(
            """{"id":"r_submit","name":"Submit","kind":"string","variants":[""" +
                """{"qualifiers":[{"type":"locale","tag":"en"}],""" +
                """"source":{"type":"text","value":"Submit"}},""" +
                """{"qualifiers":[],"source":{"type":"text","value":"Send"}}]}""",
            text,
        )
        assertEquals(declaration, Json.decodeFromString<ResourceDecl>(text))

        // An empty variant list is legal and useless, and it is a *list* rather than a
        // defaulted empty one so that a resource nothing can load is visible in the document.
        assertEquals(
            """{"id":"r_x","name":"X","kind":"file","variants":[]}""",
            Json.encodeToString<ResourceDecl>(ResourceDecl(ResourceId("r_x"), "X", ResourceKind.File, emptyList())),
        )
    }

    @Test
    fun `a qualifier the union does not have, and a missing field, are both refused`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Qualifier>("""{"type":"orientation","value":"portrait"}""")
        }
        assertFailsWith<SerializationException> { Json.decodeFromString<Qualifier>("""{"tag":"en"}""") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceDecl>("""{"name":"X","kind":"file","variants":[]}""")
        }
        // And the id validates itself on the way in, which is the one thing the model can check
        // without a schema.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ResourceDecl>("""{"id":"has space","name":"X","kind":"file","variants":[]}""")
        }
        // A source arm the union does not have does not decode either, and that is the closed
        // union's whole cost stated once.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ResourceVariant>(
                """{"qualifiers":[],"source":{"type":"generated","value":"x"}}""",
            )
        }
    }

    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
