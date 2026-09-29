package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `Value` contract: seventeen variants that all have to survive a document, a rename, a
 * JDK upgrade and a reader that has never heard of what the document names.
 *
 * The tests are grouped by the promise each one defends:
 *
 *  * **The tag is the wire form.** A rename must not be able to change what a document says.
 *  * **Numbers have one spelling** (D1), including the widening artifact that a naive JSON
 *    encoder would put in a document.
 *  * **Decoding consults nothing** (D4), which is the property the whole format is built for.
 *  * **A colour is eight hex digits** and its channels cannot be out of range.
 *  * **A kind accepts its own value and refuses the other sixteen.**
 */
class ValueTest {

    // -----------------------------------------------------------------------------------
    // The variants
    // -----------------------------------------------------------------------------------

    /** A value and the tag it must write. */
    private data class Case(val value: Value, val tag: String)

    /**
     * All seventeen of PLAN §5.4's variants, each with the tag it must write.
     *
     * One list carrying both, because a list of values and a separate list of tags would
     * drift: a variant added to one and not the other still compiles, still passes the round
     * trip, and is quietly untested for its tag.
     */
    private val cases: List<Case> = listOf(
        Case(Value.Null, "null"),
        Case(Value.Bool(true), "bool"),
        Case(Value.Int32(42), "i32"),
        Case(Value.Int64(-9_000_000_000L), "i64"),
        Case(Value.Float32(1.5f), "f32"),
        Case(Value.Float64(0.1), "f64"),
        Case(Value.Str("hello"), "str"),
        Case(Value.Color(ColorArgb.parse("#FF6200EE")), "color"),
        Case(Value.Dp(16.5f), "dp"),
        Case(Value.Sp(14f), "sp"),
        Case(Value.Enum("Start"), "enum"),
        Case(Value.Url("https://example.com/a"), "url"),
        Case(Value.Ref(RefKind.DataModel, "user"), "ref"),
        Case(Value.Icon("material", "Home"), "icon"),
        Case(Value.Token(TokenKind.Typography, "md.typography.headlineMedium"), "token"),
        Case(Value.ListOf(listOf(Value.Dp(8f), Value.Str("gap"))), "list"),
        Case(Value.Obj(TypeId("Thing"), mapOf(PropertyKey("label") to Value.Str("hi"))), "obj"),
    )

    @Test
    fun `the union has seventeen variants and no two share a tag`() {
        // Seventeen, transcribed from §5.4. The count is asserted because the union is closed
        // and a variant that quietly went missing would still compile and still pass every
        // other test in this file, while failing to read a document that used it.
        assertEquals(17, cases.size, "the list of variants is not §5.4's")

        // A duplicate tag is silent corruption: both variants are legal Kotlin, the encoder
        // writes the same discriminator for both, and the decoder picks one. Nothing errors.
        val tags = cases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two variants share a tag: $tags")
    }

    @Test
    fun `every variant round trips through json and comes back equal`() {
        for (case in cases) {
            val text = Json.encodeToString(case.value)
            assertEquals(
                case.value,
                Json.decodeFromString<Value>(text),
                "lost '${case.tag}' via '$text'",
            )
        }
    }

    @Test
    fun `the serial name is the wire form and not the class name`() {
        for (case in cases) {
            val text = Json.encodeToString(case.value)

            // What a document actually stores. Read back out of the JSON rather than off the
            // annotation, because reading the annotation would be the test agreeing with
            // itself.
            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")

            // And the rename guard. All seventeen differ from their class name today, which
            // is what lets the assertion be made without exceptions: a variant whose tag
            // started following its class name would read no document written before the
            // rename, and this is the line that catches it.
            assertNotEquals(
                case.value::class.simpleName,
                case.tag,
                "'${case.value::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `a dp is written as a canonical number`() {
        // The exact bytes, because "contains the tag" is not the claim. PLAN §5.4 gives
        // `165000 / 10000` → `16.5` as the worked example, and this is that example on the
        // wire: a bare number, no exponent, no widened double, no trailing zeros.
        assertEquals("""{"type":"dp","v":16.5}""", Json.encodeToString<Value>(Value.Dp(16.5f)))
    }

    @Test
    fun `a float is never written as a widened double`() {
        // 0.1f is 0.100000001490116119384765625, and widening it to a Double and printing
        // *that* is the artifact D1 exists to prevent. Every expectation below is what
        // Float.toString() would have said, next to what the document says.
        assertEquals("""{"type":"dp","v":0.1}""", Json.encodeToString<Value>(Value.Dp(0.1f)))
        assertEquals("""{"type":"f32","v":0.1}""", Json.encodeToString<Value>(Value.Float32(0.1f)))
        assertEquals("""{"type":"f64","v":0.1}""", Json.encodeToString<Value>(Value.Float64(0.1)))
        assertEquals("""{"type":"sp","v":14}""", Json.encodeToString<Value>(Value.Sp(14f)))

        // 1.0e8f is exactly 100000000, and Float.toString() would have written "1.0E8" — an
        // exponent, and a value the document never held.
        assertEquals("""{"type":"f32","v":100000000}""", Json.encodeToString<Value>(Value.Float32(1.0e8f)))

        // 16.0f is `16`, because a document has one spelling for a number and it is not the
        // one with the decimal point and nothing after it.
        assertEquals("""{"type":"dp","v":16}""", Json.encodeToString<Value>(Value.Dp(16.0f)))
    }

    @Test
    fun `decoding canonicalizes the number it is given`() {
        // Both ends, deliberately. A document written by an older or buggier writer still
        // lands in the model as a value this engine can write back unchanged, and a document
        // written by a *newer* one with more digits than we keep is rounded rather than
        // refused — refusing would make the engine unable to read its own format.
        val decoded = Json.decodeFromString<Value>("""{"type":"dp","v":0.123456}""")

        assertEquals(Value.Dp(0.1235f), decoded)
        assertEquals("""{"type":"dp","v":0.1235}""", Json.encodeToString(decoded))
    }

    // -----------------------------------------------------------------------------------
    // Schema-free decoding (D4)
    // -----------------------------------------------------------------------------------

    @Test
    fun `a value decodes with nothing but the json text`() {
        // The document below names five things this engine has no schema for: a data model
        // called `mysteryThing`, a page called `p_added_later`, an enum entry no
        // `EnumTypeSpec` declares, an icon from a set nobody registered, and a token name from
        // a theme that does not exist. It is written as text rather than produced by the
        // encoder above, because the claim is about *reading* something this engine did not
        // write.
        val text = """
            [
              {"type":"obj","typeId":"mysteryThing","fields":{"label":{"type":"str","v":"hi"}}},
              {"type":"ref","kind":"page","id":"p_added_later"},
              {"type":"enum","entry":"AnEntryNoSpecDeclares"},
              {"type":"icon","set":"aSetNobodyRegistered","name":"SomethingNew"},
              {"type":"token","kind":"typography","name":"brand.accent"}
            ]
        """.trimIndent()

        // The only inputs are the text and the assertion. There is no `Schema`, no
        // `PropertySpec`, no `TypeRegistry` and no `ReferenceIndex` to pass, and there is
        // nothing in this module that could be passed: `:engine:model` may depend on no other
        // module, `ModuleGraphRules.ALLOWED` records that as an empty set, and
        // `verifyModuleGraph` fails the build on a `project(...)` that disagrees. So the
        // honest form of this assertion is what it is: if a decode ever needed the schema, this
        // test would have to construct one, and the compiler would have to be told to allow
        // the dependency. Both are visible; neither is here.
        val decoded = Json.decodeFromString<List<Value>>(text)

        assertEquals(5, decoded.size, "lost a value: $decoded")

        // What survives is the payload, byte for byte — including the type ids, the page id
        // and the entry name that nothing here can resolve.
        assertEquals(
            Value.Obj(
                TypeId("mysteryThing"),
                mapOf(PropertyKey("label") to Value.Str("hi")),
            ),
            decoded[0],
        )
        assertEquals(Value.Ref(RefKind.Page, "p_added_later"), decoded[1])
        assertEquals(Value.Enum("AnEntryNoSpecDeclares"), decoded[2])
        assertEquals(Value.Icon("aSetNobodyRegistered", "SomethingNew"), decoded[3])
        assertEquals(Value.Token(TokenKind.Typography, "brand.accent"), decoded[4])

        // And lossless, which is the point of D4 rather than a separate promise: an engine
        // that cannot understand a document must still be able to hand it back unchanged, or
        // a plugin's work is destroyed by opening the file.
        //
        // The comparison is between the two *parsed* documents rather than between two
        // strings, because the fixture above is written one element per line for reading and
        // the encoder writes it on one line. Whitespace is not something the encoder promises
        // to reproduce, and key order is not something JSON promises either; the data is, and
        // that is what has to survive.
        assertEquals(
            Json.parseToJsonElement(text),
            Json.parseToJsonElement(Json.encodeToString(decoded)),
            "the round trip rewrote the document",
        )
    }

    @Test
    fun `a value decodes with no document around it`() {
        // The stronger form of the same claim. A value nested in a document is surrounded by
        // ids and types that *could* be consulted; this one is a bare element with no context
        // of any kind, and it still decodes.
        val decoded = Json.decodeFromString<Value>(
            """{"type":"enum","entry":"AnEntryNoSpecDeclares"}""",
        )

        assertEquals(Value.Enum("AnEntryNoSpecDeclares"), decoded)
    }

    @Test
    fun `a tag the union does not have is refused`() {
        // The honest limit of a closed union, pinned so it stays a limit rather than becoming
        // a surprise. D4 is about unknown *payload*; an eighteenth variant is a
        // schema-version event by design (D3), and the union is not going to quietly grow one
        // entry to be accommodating.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Value>("""{"type":"date","v":"2026-01-01"}""")
        }
    }

    // -----------------------------------------------------------------------------------
    // ColourArgb
    // -----------------------------------------------------------------------------------

    @Test
    fun `a channel out of range is refused`() {
        // The packed representation has no invalid `Int` — every one of them is a colour — so
        // the check lives where channels are taken. All four are checked, in both directions,
        // because a factory that validated only the first argument would pass a test that
        // only tried the first argument.
        for (outOfRange in listOf(-1, 256, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("alpha=$outOfRange was accepted") {
                ColorArgb.of(outOfRange, 0, 0, 0)
            }
            assertFailsWith<IllegalArgumentException>("red=$outOfRange was accepted") {
                ColorArgb.of(0, outOfRange, 0, 0)
            }
            assertFailsWith<IllegalArgumentException>("green=$outOfRange was accepted") {
                ColorArgb.of(0, 0, outOfRange, 0)
            }
            assertFailsWith<IllegalArgumentException>("blue=$outOfRange was accepted") {
                ColorArgb.of(0, 0, 0, outOfRange)
            }
        }

        // And the message names the channel, because "channel out of range" with four
        // arguments in the call is a diagnostic that costs more than the check saved.
        val failure = assertFailsWith<IllegalArgumentException> { ColorArgb.rgb(0, 0, 300) }
        assertTrue("blue" in failure.message.orEmpty(), "unhelpful message: ${failure.message}")
    }

    @Test
    fun `the four channels are read back out of the packed value`() {
        val color = ColorArgb.of(0x80, 0x11, 0x22, 0x33)

        assertEquals(0x80, color.alpha)
        assertEquals(0x11, color.red)
        assertEquals(0x22, color.green)
        assertEquals(0x33, color.blue)

        // The packing is ARGB with the alpha in the top bit, which means an opaque colour is
        // a *negative* Int. Asserted because it is the detail that makes the representation
        // worth arguing about: an implementation that read the top bit as a sign flag, or that
        // assumed the packed value was a positive number, would be wrong about every opaque
        // colour in a document. §9.2's own `Color(0xFF6200EE)` is such a negative Int.
        assertTrue(
            ColorArgb.parse("#FF6200EE").argb < 0,
            "an opaque colour packed as a positive Int",
        )
        assertEquals(ColorArgb.of(255, 0x62, 0x00, 0xEE), ColorArgb.parse("#FF6200EE"))
    }

    @Test
    fun `a colour round trips as eight hex digits`() {
        // The form PLAN §9.2 gives, exactly. `#` and eight upper-case hex digits, and nothing
        // else — a document that cannot write a colour readably is a document nobody reviews.
        assertEquals("#FF6200EE", ColorArgb.parse("#FF6200EE").toHexString())
        assertEquals("#00000000", ColorArgb.of(0, 0, 0, 0).toHexString())
        assertEquals("#0000000F", ColorArgb.of(0, 0, 0, 15).toHexString())
        assertEquals("#FFFFFFFF", ColorArgb.of(255, 255, 255, 255).toHexString())

        // Input is case-insensitive, output is upper case: a document written by a tool and
        // edited by a person should not come back with the case flipped.
        assertEquals(ColorArgb.parse("#FF6200EE"), ColorArgb.parse("#ff6200ee"))
        assertEquals("#FF6200EE", ColorArgb.parse("#ff6200ee").toHexString())

        // `toString` is the same spelling, for the same reason an id reads as itself in a log.
        assertEquals("#FF6200EE", ColorArgb.parse("#FF6200EE").toString())
    }

    @Test
    fun `anything that is not a colour is refused`() {
        // Strict, because §5.4 spends its whole budget on one spelling per value. A parser
        // that tolerated these would accept documents that mean the same thing three ways.
        for (text in listOf(
            "",
            "FF6200EE",      // no '#'
            "#",             // '#' alone
            "#FF6200EEA",    // nine digits
            "#FF6200E",      // seven digits
            "#FF6200EG",     // G is not a hex digit
            "0xFF6200EE",    // C-style prefix, not the wire form
            "red",           // a name; the wire form is never symbolic
        )) {
            assertFailsWith<IllegalArgumentException>("'$text' was accepted") {
                ColorArgb.parse(text)
            }
        }
    }

    @Test
    fun `a colour is judged on its digit count and nothing else`() {
        // Recorded because it cost a red CI run: `#FFF6200E` was listed among the rejects on
        // the belief that it had one digit too few. It has eight — F F F 6 2 0 0 E — so it is
        // a perfectly good #AARRGGBB, and it parses. What makes a string a colour here is
        // exactly "'#' then eight hex digits", and a substring of the wrong length is a
        // different string, not a malformed version of the same one.
        assertEquals("#FFF6200E", ColorArgb.parse("#FFF6200E").toHexString())
    }

    @Test
    fun `a colour is written as a string and not as a packed integer`() {
        // The one thing a colour must never be on the wire. `@Serializable` on a value class
        // would write the underlying Int, and a document that round-trips a colour through a
        // negative integer is a document a person cannot edit.
        //
        // Every assertion goes through `Value.Color` rather than a bare `ColorArgb`, because
        // that is the only place the model writes a colour. The serializer is declared on the
        // property for a reason: `@Serializable(with = ...)` on a `@JvmInline value class`
        // compiles, works on the JVM and on Android, and throws on Wasm. A ColorArgb therefore
        // has no standalone serializer, and this test does not pretend that it does.
        assertEquals(
            """{"type":"color","argb":"#FF6200EE"}""",
            Json.encodeToString<Value>(Value.Color(ColorArgb.parse("#FF6200EE"))),
        )
        assertEquals(
            Value.Color(ColorArgb.parse("#FF6200EE")),
            Json.decodeFromString<Value>("""{"type":"color","argb":"#FF6200EE"}"""),
        )

        // Case in, case out, on every target: a document written by a tool and edited by a
        // person must not come back with the hex case flipped.
        assertEquals(
            """{"type":"color","argb":"#FF6200EE"}""",
            Json.encodeToString<Value>(Value.Color(ColorArgb.parse("#ff6200ee"))),
        )
    }

    // -----------------------------------------------------------------------------------
    // ValueKind
    // -----------------------------------------------------------------------------------

    /**
     * A kind and one value of the seventeen that belongs to it.
     *
     * The type is stated rather than left to inference because the eleven pairs have
     * eleven different types, and a `listOf` over them infers a least upper bound that a
     * star projection makes hard to predict.
     */
    private val kinds: List<Pair<ValueKind<*>, Value>> = listOf(
        BoolValueKind to Value.Bool(true),
        Int32ValueKind to Value.Int32(1),
        Int64ValueKind to Value.Int64(1L),
        Float32ValueKind to Value.Float32(1.5f),
        Float64ValueKind to Value.Float64(1.5),
        StringValueKind to Value.Str("a"),
        UrlValueKind to Value.Url("https://example.com"),
        ColorValueKind to Value.Color(ColorArgb.parse("#FF6200EE")),
        DpValueKind to Value.Dp(16.5f),
        SpValueKind to Value.Sp(14f),
        EnumEntryValueKind(TypeRef.Enum(TypeId("Alignment"))) to Value.Enum("Start"),
    )

    @Test
    fun `a kind accepts its own value and refuses the other sixteen`() {
        // The matrix. A kind that accepted two variants would be a type check that can be
        // fooled by a document, and the variant that fools it would be the one it was not
        // written for. Seventeen by eleven, and every cell is asserted.
        for ((kind, own) in kinds) {
            for (value in cases.map { it.value }) {
                // The oracle is the *variant*, not the value. `accepts` is a type check, so
                // `UrlValueKind` accepts every `Value.Url` including one whose text differs
                // from the sample here; comparing values would assert that a kind only
                // accepts the one value it was shown, which is a weaker and wrong contract.
                assertEquals(
                    value::class == own::class,
                    kind.accepts(value),
                    "${kind.type.serialTag} on ${value::class.simpleName}",
                )
            }
        }
    }

    @Test
    fun `a kind answers with the payload of its own value`() {
        assertEquals(true, BoolValueKind.decode(Value.Bool(true)))
        assertEquals(42, Int32ValueKind.decode(Value.Int32(42)))
        assertEquals(42L, Int64ValueKind.decode(Value.Int64(42L)))
        assertEquals(1.5f, Float32ValueKind.decode(Value.Float32(1.5f)))
        assertEquals(1.5, Float64ValueKind.decode(Value.Float64(1.5)))
        assertEquals("hello", StringValueKind.decode(Value.Str("hello")))
        assertEquals("https://example.com", UrlValueKind.decode(Value.Url("https://example.com")))
        assertEquals(
            ColorArgb.parse("#FF6200EE"),
            ColorValueKind.decode(Value.Color(ColorArgb.parse("#FF6200EE"))),
        )

        // A `dp` and an `sp` both decode to a number, and the unit is in the kind's type
        // rather than in the answer. That is why they are two kinds and not one.
        assertEquals(16.5f, DpValueKind.decode(Value.Dp(16.5f)))
        assertEquals(14f, SpValueKind.decode(Value.Sp(14f)))
        assertEquals(TypeRef.Dp, DpValueKind.type)

        // The enum kind decodes to the name the document wrote, which is all a model can do
        // without the `EnumTypeSpec` that would turn a name into a value.
        assertEquals(
            "Start",
            EnumEntryValueKind(TypeRef.Enum(TypeId("Alignment"))).decode(Value.Enum("Start")),
        )
    }

    @Test
    fun `a kind refuses a value of another type`() {
        // Refusing rather than defaulting. A kind that answered `0f` for a `Str` would turn a
        // document error into a wrong rendering with no diagnostic anywhere, and that is the
        // failure mode the split between `accepts` and `decode` exists to prevent.
        assertFailsWith<IllegalArgumentException> { DpValueKind.decode(Value.Sp(14f)) }
        assertFailsWith<IllegalArgumentException> { DpValueKind.decode(Value.Str("14")) }
        assertFailsWith<IllegalArgumentException> { DpValueKind.decode(Value.Null) }
        assertFailsWith<IllegalArgumentException> { StringValueKind.decode(Value.Url("x")) }
        assertFailsWith<IllegalArgumentException> {
            EnumEntryValueKind(TypeRef.Enum(TypeId("Alignment"))).decode(Value.Str("Start"))
        }

        // The message speaks in the wire vocabulary, because that is the spelling the reader
        // has in front of them.
        val failure = assertFailsWith<IllegalArgumentException> {
            DpValueKind.decode(Value.Sp(14f))
        }
        assertTrue("dp" in failure.message.orEmpty(), "unhelpful message: ${failure.message}")
    }

    @Test
    fun `a value no kind claims is refused by all of them`() {
        // Six of the seventeen have no model kind: Null, Ref, Icon, Token, ListOf and Obj.
        // Each needs a schema, a theme, an analysis pass or a declared shape to become
        // anything a renderer can use, and a kind here that pretended otherwise would have to
        // invent the answer.
        val unclaimed = listOf(
            Value.Null,
            Value.Ref(RefKind.Page, "p_home"),
            Value.Icon("material", "Home"),
            Value.Token(TokenKind.Color, "md.color.primary"),
            Value.ListOf(listOf(Value.Dp(8f))),
            Value.Obj(TypeId("Thing"), mapOf(PropertyKey("label") to Value.Str("hi"))),
        )

        for ((kind, _) in kinds) {
            for (value in unclaimed) {
                assertFalse(
                    kind.accepts(value),
                    "${kind.type.serialTag} claimed ${value::class.simpleName}",
                )
            }
        }
    }

    /**
     * The discriminator, read out of encoded JSON rather than off the annotation.
     *
     * Parsing the text back is the point. Reading the tag from the serializer descriptor would
     * be the test agreeing with itself.
     */
    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
