package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.value.ColorArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `ColorSpec` contract: one colour role's light and dark pair, §14.1.
 *
 * The assertion that carries the most weight is the one about the *spelling*. A `ColorArgb` is
 * packed into an `Int`, and an ordinary `@Serializable` would write the packed integer —
 * `{"light":-10203410}` — which is unreadable in a document nobody can review. `#AARRGGBB` is
 * §9.2's Serialization column, and it only reaches the wire because the serializer sits on the
 * properties. A reader who strips those two annotations sees every other test here fail at
 * once, which is the intended alarm.
 */
class ColorSpecTest {

    @Test
    fun `a colour is written as eight hex digits and not as a packed int`() {
        val spec = ColorSpec(light = ColorArgb.parse("#FF6200EE"))

        val text = Json.encodeToString<ColorSpec>(spec)

        assertEquals("""{"light":"#FF6200EE"}""", text)
        assertEquals(spec, Json.decodeFromString<ColorSpec>(text))
        assertEquals(
            "#FF6200EE",
            Json.parseToJsonElement(text).jsonObject.getValue("light").jsonPrimitive.content,
        )

        // The packed form is what the encoder was *not* given, asserted from the other side so
        // the property-level serializer is the only thing that can explain the bytes above: an
        // opaque colour is a negative `Int`, and a negative number is not in the fragment.
        assertTrue(
            ColorArgb.parse("#FF6200EE").argb < 0,
            "an opaque colour packed as a positive Int",
        )
        assertFalse(
            ColorArgb.parse("#FF6200EE").argb.toString() in text,
            "the packed Int reached the wire alongside the hex",
        )
    }

    @Test
    fun `a role with no dark value omits it and a role with one writes it`() {
        // The distinction `ColorSpec` exists for: "no dark value" is an absent field, while
        // "not in the theme" is an absent map entry. Both are expressible, and neither is
        // mistaken for the other.
        val lightOnly = ColorSpec(light = ColorArgb.parse("#FF6200EE"))
        val pair = ColorSpec(
            light = ColorArgb.parse("#FF6200EE"),
            dark = ColorArgb.parse("#FFBB33"),
        )

        assertEquals("""{"light":"#FF6200EE"}""", Json.encodeToString<ColorSpec>(lightOnly))
        assertEquals(
            listOf("light"),
            Json.parseToJsonElement(Json.encodeToString<ColorSpec>(lightOnly)).jsonObject.keys.toList(),
        )
        assertEquals(
            """{"light":"#FF6200EE","dark":"#FFBB33"}""",
            Json.encodeToString<ColorSpec>(pair),
        )

        // An absent `dark` and an explicit `null` are different fragments, and only the second
        // says the role exists without a dark value.
        assertNull(Json.decodeFromString<ColorSpec>("""{"light":"#FF6200EE"}""").dark)
        assertNull(Json.decodeFromString<ColorSpec>("""{"light":"#FF6200EE","dark":null}""").dark)
        assertEquals(
            pair,
            Json.decodeFromString<ColorSpec>("""{"light":"#FF6200EE","dark":"#FFBB33"}"""),
        )
    }

    @Test
    fun `a hex string is read in any case and written in one`() {
        // `ColorArgb` accepts either case on the way in and emits upper case on the way out, so
        // a hand-written document is normalised rather than rejected. The consequence is stated
        // rather than hidden: a lowercase fragment is *not* byte-preserved on the round trip,
        // and that is the intended trade — two spellings of one colour in one file is a diff
        // nobody asked for.
        val lower = Json.decodeFromString<ColorSpec>("""{"light":"#ff6200ee"}""")

        assertEquals(ColorArgb.parse("#FF6200EE"), lower.light)
        assertEquals("""{"light":"#FF6200EE"}""", Json.encodeToString<ColorSpec>(lower))

        // A fragment that mixes the two cases is *normalised*, not preserved: it decodes and
        // re-encodes to the upper-case spelling, so it is not byte-identical. A fragment already
        // in upper case — the canonical form, and what the first test writes — is preserved
        // exactly, which is the property a stored document depends on.
        val mixed = """{"light":"#FF6200EE","dark":"#ffbb33"}"""
        assertNotEquals(
            mixed,
            Json.encodeToString<ColorSpec>(Json.decodeFromString<ColorSpec>(mixed)),
            "a mixed-case fragment came back unchanged",
        )
        assertEquals(
            """{"light":"#FF6200EE","dark":"#FFBB33"}""",
            Json.encodeToString<ColorSpec>(Json.decodeFromString<ColorSpec>(mixed)),
        )

        val canonical = """{"light":"#FF6200EE","dark":"#FFBB33"}"""
        assertEquals(
            canonical,
            Json.encodeToString<ColorSpec>(Json.decodeFromString<ColorSpec>(canonical)),
            "the canonical spelling did not round trip byte for byte",
        )
    }

    @Test
    fun `a malformed colour is refused by the parser it belongs to`() {
        // A bad hex string fails where it is read rather than producing a packed `Int` that no
        // later pass could tell was wrong, and it does so for `dark` exactly as it does for
        // `light` — the serializer is on both properties, which is what the property-level
        // decision buys.
        //
        // **An `IllegalArgumentException` and not a `SerializationException`,** which is worth
        // stating because it is the exception type a caller has to catch. `ColorArgb.parse`
        // refuses with `require`, kotlinx.serialization does not wrap what a custom serializer
        // throws, and so the failure arrives as the parser's own type. A caller writing
        // `catch (e: SerializationException)` around a document load would not catch a bad
        // colour, and this line is here so that fact is asserted rather than discovered.
        for (text in listOf("6200EE", "#ZZ0033", "#FF6200EEA", "red")) {
            assertFailsWith<IllegalArgumentException>("'$text' was accepted") {
                Json.decodeFromString<ColorSpec>("""{"light":"$text"}""")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ColorSpec>("""{"light":"#FF6200EE","dark":"#ZZ0033"}""")
        }

        // A *structural* problem is still the serializer's, because the shape of the record is
        // kotlinx's business rather than the colour's: a missing required field is a
        // `MissingFieldException`, which is a `SerializationException`.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ColorSpec>("""{"dark":"#FF6200EE"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ColorSpec>("""{"light":"#FF6200EE","alpha":128}""")
        }
    }

    @Test
    fun `a spec is a value, so a document diff can tell two apart`() {
        // §6.2's `data class` equality. Two roles whose light values differ are different
        // declarations even though both hold a colour, and swapping a light for its dark
        // changes the bytes as well as the value.
        val a = ColorSpec(ColorArgb.rgb(0x62, 0x00, 0xEE), ColorArgb.rgb(0xBB, 0x33, 0x11))
        val b = ColorSpec(ColorArgb.rgb(0x62, 0x00, 0xEE), ColorArgb.rgb(0xBB, 0x33, 0x22))

        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertNotEquals(a, b, "two specs with different dark values compared equal")
        assertNotEquals(
            Json.encodeToString<ColorSpec>(a),
            Json.encodeToString<ColorSpec>(b),
            "two different specs produced the same bytes",
        )

        // A fully transparent colour is a colour, and nothing on this path refuses it.
        val transparent = ColorSpec(ColorArgb(0x00FF6200))

        assertEquals("""{"light":"#00FF6200"}""", Json.encodeToString<ColorSpec>(transparent))
    }
}
