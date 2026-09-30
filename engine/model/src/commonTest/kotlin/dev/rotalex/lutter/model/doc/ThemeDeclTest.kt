package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The theme contract: `ThemeDecl`, the one closed type in §14.1, and the four role value
 * classes that are deliberately *not* closed.
 *
 * The claim worth testing is the one that looks like a hole. The role vocabulary is open
 * strings, so `ColorRole("primry")` is a legal value and only §17's `token.unknown` reports
 * it. That is the price, stated rather than hidden, and the alternative was writing Material
 * 3's role names down as the variants — which would make every rename a `FORMAT_VERSION`
 * event before anyone has built a theme. §14.2 already mandates the validation the open
 * vocabulary gives up, so nothing needs it closed for the document to be rejected.
 *
 * The numbers below are canonical, which means `16.0` is written `16` and not `16.0`
 * (`CanonicalNumbers.textOf` trims the fractional part to its last non-zero digit). Every
 * expectation here was read off that rule, because a whole-number `dp` is the common case in
 * a dimension scale and `16.0` in an assertion would be the kind of near-miss that reads as a
 * production bug.
 */
class ThemeDeclTest {

    @Test
    fun `a theme with nothing declared is two fields of json`() {
        // Every other field has a default, and §6.2's canonical writer encodes no defaults, so
        // the base defaults are the one shape a document never writes.
        val theme = ThemeDecl(ThemeId("t_base"), "Base")

        val text = Json.encodeToString<ThemeDecl>(theme)

        assertEquals("""{"id":"t_base","name":"Base"}""", text)
        assertEquals(
            listOf("id", "name"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
        )
        assertEquals(theme, Json.decodeFromString<ThemeDecl>(text))
        // The base defaults rather than being absent, which is what makes `Material3` the base
        // for a document that never says so.
        assertEquals(ThemeBase.Material3, theme.base)
    }

    @Test
    fun `the five token maps are keyed by open role strings`() {
        val theme = ThemeDecl(
            id = ThemeId("t_dark"),
            name = "Dark",
            colors = mapOf(ColorRole("primary") to ColorSpec(light = ColorArgb.parse("#FF6200EE"))),
            typography = mapOf(
                TextRole("headlineMedium") to mapOf(PropertyKey("fontSize") to Value.Sp(24f)),
            ),
            shapes = mapOf(ShapeRole("small") to mapOf(PropertyKey("radius") to Value.Dp(4f))),
            dimensions = mapOf(TokenName("md.spacing.medium") to Value.Dp(16f)),
            custom = mapOf(TokenName("brand.accent") to Value.Color(ColorArgb.parse("#FFFFBB33"))),
        )

        val text = Json.encodeToString<ThemeDecl>(theme)

        // A role is a bare string on the wire, which is what "open vocabulary" means; a
        // `TokenName` is a dotted one; and the colour is eight hex digits because `ColorSpec`
        // puts the serializer on its properties.
        assertEquals(
            """{"id":"t_dark","name":"Dark","colors":{"primary":{"light":"#FF6200EE"}},""" +
                """"typography":{"headlineMedium":{"fontSize":{"type":"sp","v":24}}},""" +
                """"shapes":{"small":{"radius":{"type":"dp","v":4}}},""" +
                """"dimensions":{"md.spacing.medium":{"type":"dp","v":16}},""" +
                """"custom":{"brand.accent":{"type":"color","argb":"#FFFFBB33"}}}""",
            text,
        )
        assertEquals(theme, Json.decodeFromString<ThemeDecl>(text))

        // A role is a map *key*, and a value class is a legal one on every target because
        // `Node.props` already is.
        assertEquals(
            "primary",
            Json.parseToJsonElement(text).jsonObject.getValue("colors")
                .jsonObject.keys.single(),
        )
    }

    @Test
    fun `a base is spelled, and an unknown one does not decode`() {
        // `ThemeBase` is an `enum class` with one entry and it is the one closed type in §14.1:
        // a *name* can be validated by lookup, which §14.2 already does, while a *base* has to
        // be dispatched on by both backends. A second entry is a `FORMAT_VERSION` event because
        // an unknown tag fails to decode — asserted here rather than assumed.
        assertEquals("\"material3\"", Json.encodeToString<ThemeBase>(ThemeBase.Material3))
        assertEquals(ThemeBase.Material3, Json.decodeFromString<ThemeBase>("\"material3\""))
        assertEquals(1, ThemeBase.entries.size, "a second base is a FORMAT_VERSION event")

        assertFailsWith<SerializationException> { Json.decodeFromString<ThemeBase>("\"material2\"") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ThemeDecl>("""{"id":"t","name":"B","base":"material2"}""")
        }
    }

    @Test
    fun `a role refuses a blank name and accepts a name in no vocabulary`() {
        // The check is `isNotBlank()` and not a grammar, and the rest is `token.unknown`'s to
        // report. A token is *dotted* — `md.color.primary` — which is why these four do not
        // use `IdSyntax`: that grammar is `[A-Za-z0-9_]{1,64}` and has no room for a dot.
        assertFailsWith<IllegalArgumentException> { ColorRole("") }
        assertFailsWith<IllegalArgumentException> { TextRole("   ") }
        assertFailsWith<IllegalArgumentException> { ShapeRole("\t") }
        assertFailsWith<IllegalArgumentException> { TokenName(" ") }

        // Misspelled, unknown, and nothing this module can reject. §14.2's rule is what reports
        // them, as `token.unknown` and not as a document that will not open.
        assertEquals("primry", ColorRole("primry").value)
        assertEquals("md.color.primary", TokenName("md.color.primary").value)
        assertEquals("brand.accent", TokenName("brand.accent").value)
        // A dotted name would be refused by every id in `ids/Ids.kt`; these are not ids.
        assertEquals("has space", ColorRole("has space").value)

        // A role reads as itself, the way every other identifier here does, because these names
        // are the vocabulary diagnostics are written in.
        assertEquals("primary", ColorRole("primary").toString())
        assertEquals("md.color.primary", TokenName("md.color.primary").toString())
    }

    @Test
    fun `the two spec aliases are open maps of a typed key to a typed value`() {
        // Typealiases and not wrappers, because `PropertyKey` and `Value` are already
        // serializable and a wrapper would be a record whose only field is its own payload.
        // Both expand to the same map type, which is what §14.1 says they are.
        val style: TextStyleSpec = mapOf(PropertyKey("lineHeight") to Value.Sp(20f))
        val shape: ShapeSpec = mapOf(PropertyKey("radius") to Value.Dp(8f))
        val nothing: TextStyleSpec = emptyMap()

        assertEquals(emptyMap<PropertyKey, Value>(), nothing)
        assertEquals(Value.Sp(20f), style.getValue(PropertyKey("lineHeight")))
        assertEquals(Value.Dp(8f), shape.getValue(PropertyKey("radius")))

        // The key is checked, so a typography property that could never be emitted in generated
        // code cannot be written.
        assertFailsWith<IllegalArgumentException> { mapOf(PropertyKey("has space") to Value.Dp(1f)) }
    }

    @Test
    fun `component defaults are a nested typed map, and an unknown key is refused`() {
        // A value class as an outer key and as an inner one: `ComponentType` and
        // `PropertyKey`, both of which `Node.props` and `DocumentMeta` already rely on.
        val theme = ThemeDecl(
            id = ThemeId("t_dark"),
            name = "Dark",
            componentDefaults = mapOf(
                ComponentType("m3.Button") to mapOf(
                    PropertyKey("shape") to PropertyValue.Const(Value.Str("rounded")),
                ),
            ),
        )

        val text = Json.encodeToString<ThemeDecl>(theme)

        assertEquals(
            """{"id":"t_dark","name":"Dark","componentDefaults":{"m3.Button":{"shape":""" +
                """{"type":"const","value":{"type":"str","v":"rounded"}}}}}""",
            text,
        )
        assertEquals(theme, Json.decodeFromString<ThemeDecl>(text))

        // `ignoreUnknownKeys` is false in the canonical `Json` (§18.2), so a typo in a document
        // is a decode error rather than a silently dropped key.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ThemeDecl>("""{"id":"t","name":"B","colourz":{}}""")
        }
    }

    @Test
    fun `a theme is a value, so a document diff can tell two apart`() {
        val one = ThemeDecl(
            ThemeId("t"),
            "T",
            colors = mapOf(ColorRole("primary") to ColorSpec(light = ColorArgb.parse("#FF6200EE"))),
        )
        val other = one.copy(
            colors = mapOf(ColorRole("primary") to ColorSpec(light = ColorArgb.parse("#FF000000"))),
        )

        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertNotEquals(one, other, "two themes with different primaries compared equal")
        assertTrue(Json.encodeToString<ThemeDecl>(one).contains("#FF6200EE"))
        assertNotEquals(
            Json.encodeToString<ThemeDecl>(one),
            Json.encodeToString<ThemeDecl>(other),
            "two different themes produced the same bytes",
        )
    }
}
