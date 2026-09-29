package dev.rotalex.lutter.model.value

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `PropertyValue` contract: two arms, the currency every property map in the document is
 * denominated in.
 *
 * The tests are grouped by the promise each one defends:
 *
 *  * **The tag is the wire form**, and both arms carry one. `Node.props` (§5.3),
 *    `ModifierEntry.args` and `ActionStep.args` (§11.2) are all this shape, so a tag that
 *    moved would move in three places at once.
 *  * **A computed property carries a real expression**, and the numbers inside it keep D1's
 *    canonical spelling — the wrapper is where an expression stops being syntax and starts
 *    being a document.
 *  * **Decoding consults nothing** (D4), one level deeper than `ValueTest` goes.
 *  * **Two arms, and no third.** A property is a constant or a computation, and the invalid
 *    third state — both or neither — is not representable.
 */
class PropertyValueTest {

    /** A property value and the tag it must write. */
    private data class Case(val property: PropertyValue, val tag: String)

    /** PLAN §5.4's two arms, each with the tag it must write. */
    private val cases: List<Case> = listOf(
        Case(PropertyValue.Const(Value.Dp(16.5f)), "const"),
        Case(
            PropertyValue.Computed(
                Expr.Binary(
                    op = BinaryOp.Add,
                    left = Expr.Ref(RefTarget.State(StateId("s_count"))),
                    right = Expr.Const(Value.Int32(1)),
                ),
            ),
            "expr",
        ),
    )

    @Test
    fun `the union has two arms and no two share a tag`() {
        // Two, transcribed from §5.4. Asserted because a third arm is a `FORMAT_VERSION`
        // event for every property map in the document, and because a count nobody checks is
        // a count nobody notices changing.
        assertEquals(2, cases.size, "the list of arms is not §5.4's")

        val tags = cases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two arms share a tag: $tags")
    }

    @Test
    fun `both arms round trip through json and come back equal`() {
        for (case in cases) {
            val text = Json.encodeToString<PropertyValue>(case.property)
            assertEquals(
                case.property,
                Json.decodeFromString<PropertyValue>(text),
                "lost '${case.tag}' via '$text'",
            )
        }
    }

    @Test
    fun `the serial name is the wire form and not the class name`() {
        for (case in cases) {
            val text = Json.encodeToString<PropertyValue>(case.property)

            // Read back out of the encoded JSON rather than off the annotation, so a tag that
            // drifts from the `@SerialName` fails a test instead of a review.
            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")

            // The rename guard. Both arms differ from their class name today, which is what
            // lets this be asserted without exceptions: `Const` renamed to `Constant` would
            // read no document written before the rename.
            assertNotEquals(
                case.property::class.simpleName,
                case.tag,
                "'${case.property::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `a constant property is the value with a tag around it`() {
        // The exact bytes, because "contains the tag" is the weaker claim. A constant property
        // is an object wrapping a `Value` that is itself an object with a `type` key — two
        // levels of nesting for a `16.5`, and that verbosity is what §33.1's compact
        // `PropertyValueSerializer` exists to collapse. It is not written yet, and until it is
        // this is the wire form.
        assertEquals(
            """{"type":"dp","v":16.5}""",
            Json.encodeToString<Value>(Value.Dp(16.5f)),
        )
        assertEquals(
            "{\"type\":\"const\",\"value\":{\"type\":\"dp\",\"v\":16.5}}",
            Json.encodeToString<PropertyValue>(PropertyValue.Const(Value.Dp(16.5f))),
        )
    }

    @Test
    fun `a computed property carries the expression and not a string of it`() {
        // The whole reason this union exists. The payload is a tree, so the analyzer can walk
        // it, the interpreter can evaluate it and codegen can print it — three consumers that
        // would each have to parse a string otherwise, and none of them could agree on how.
        val expression = Expr.If(
            cond = Expr.Member(Expr.Ref(RefTarget.Param(ParamName("user"))), "isAdmin", safe = true),
            then = Expr.Const(Value.Str("admin")),
            otherwise = Expr.Const(Value.Str("user")),
        )

        val text = Json.encodeToString<PropertyValue>(PropertyValue.Computed(expression))
        val decoded = assertIs<PropertyValue.Computed>(Json.decodeFromString<PropertyValue>(text))

        assertEquals(expression, decoded.expr, "the expression came back as something else")
        assertTrue(
            "\"type\":\"if\"" in text,
            "the nested expression lost its own discriminator",
        )
    }

    @Test
    fun `a document round trips through value to property value to expr`() {
        // The real shape a property takes, end to end, and the reason the three pieces are in
        // one test: a number is canonicalized by `Value`, wrapped by `PropertyValue`, carried
        // inside an `Expr`, and read back out as the same number. A defect in any of the three
        // — a wrapper that re-formats, a `Const` that stringifies, a `Json` that does not
        // thread the discriminator through two levels of nesting — breaks here and nowhere
        // else.
        val document: PropertyValue = PropertyValue.Computed(
            Expr.Template(
                listOf(
                    Expr.Const(Value.Str("greeting: ")),
                    Expr.Binary(
                        op = BinaryOp.Mul,
                        left = Expr.Const(Value.Dp(2.5f)),
                        right = Expr.Ref(RefTarget.State(StateId("s_scale"))),
                    ),
                ),
            ),
        )

        val text = Json.encodeToString<PropertyValue>(document)
        val decoded = Json.decodeFromString<PropertyValue>(text)

        assertEquals(document, decoded, "the chain did not close: '$text'")

        // And re-encoding is byte-identical, which is the property the corpus and the
        // `RoundTripPropertyTest` of §36.1 are built on: `encode(decode(encode(x))) == encode(x)`.
        assertEquals(text, Json.encodeToString<PropertyValue>(decoded))

        // The number itself is intact and canonical — 2.5 written as 2.5, not as 2.5f widened
        // to 2.5 in a double and not as 2.5000001.
        assertTrue("\"v\":2.5" in text, "the dp lost its canonical spelling: '$text'")
    }

    @Test
    fun `a computed property decodes without knowing which component owns it`() {
        // D4, one level deeper than `ValueTest` goes. The fragment below is written as text
        // rather than produced by the encoder, because the claim is about *reading* something
        // this engine did not write: a template over a member of a parameter, calling a plugin
        // function, reading a state declaration — on a component that may not exist here at
        // all. There is no `PropertySpec`, no `Schema` and no `TypeRef` in this module to
        // consult, and §23.3's rule that `:engine:model` depends on no other module means
        // there is nothing that could be.
        //
        // `Const` is a variant of `Expr` and not a transparent wrapper, so inside `parts` it
        // carries its own tag and names its payload. A fragment that put a bare `Value` there
        // decodes as "serializer for subclass 'str' is not found in the polymorphic scope of
        // 'Expr'" - a confusing way of saying the fragment was written against the wrong
        // shape, which is why the comment sits next to the literal.
        val text = "{\"type\":\"expr\",\"expr\":{\"type\":\"template\",\"parts\":[" +
            "{\"type\":\"const\",\"value\":{\"type\":\"str\",\"v\":\"Hi \"}}," +
            "{\"type\":\"member\",\"receiver\":{\"type\":\"ref\"," +
            "\"target\":{\"type\":\"param\",\"name\":\"user\"}},\"name\":\"displayName\"}," +
            "{\"type\":\"call\",\"function\":\"vendor.redact\"," +
            "\"args\":[{\"type\":\"ref\",\"target\":{\"type\":\"state\",\"id\":\"s_secret\"}}]}" +
            "]}}"

        assertEquals("expr", discriminatorOf(text), "the wrapper lost its own tag")

        val computed = assertIs<PropertyValue.Computed>(Json.decodeFromString<PropertyValue>(text))
        val template = assertIs<Expr.Template>(computed.expr)

        assertEquals(3, template.parts.size, "a part was lost: ${template.parts}")

        // Lossless, which is the point of D4 rather than a separate promise: an engine that
        // cannot understand a document must still hand it back unchanged, or a plugin's work
        // is destroyed by opening the file. Compared as parsed documents, because whitespace
        // and key order are not things the encoder promises and the data is.
        assertEquals(
            Json.parseToJsonElement(text),
            Json.parseToJsonElement(Json.encodeToString<PropertyValue>(computed)),
            "the round trip rewrote the document",
        )
    }

    @Test
    fun `a tag the union does not have is refused`() {
        // The honest limit, pinned so it stays one. A third arm is a `FORMAT_VERSION` event,
        // and the invalid state this design exists to make unrepresentable is the one this
        // union already refuses to express: neither a value nor an expression.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PropertyValue>("""{"type":"value","v":1}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PropertyValue>("""{"type":"bind","expr":"s_count"}""")
        }
    }

    /**
     * The discriminator, read out of encoded JSON rather than off the annotation.
     *
     * Parsing the text back is the point: reading the tag off the serializer descriptor would
     * be reading the same declaration the test is supposed to be checking.
     *
     * Every caller encodes through the sealed base — `encodeToString<PropertyValue>(…)` — and
     * that is not decoration. `encodeToString` infers its type argument from the value, so a
     * concrete subtype resolves that subtype's own serializer, and a concrete serializer
     * writes no discriminator at all: `encodeToString(PropertyValue.Const(…))` produces
     * `{"value":…}` and this helper throws on it.
     */
    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
