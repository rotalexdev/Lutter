package dev.rotalex.lutter.model.expr

import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `Expr` contract: nine shapes that have to survive a document, a rename, and an engine
 * that has never heard of the function an expression calls.
 *
 * The tests are grouped by the promise each one defends:
 *
 *  * **The tag is the wire form.** A `@SerialName` that drifts, or a tag that starts following
 *    the class name, breaks every document already written and breaks silently.
 *  * **The operator sets are closed and spelled.** D10 is asserted, not assumed — an operator
 *    added without a parity test is exactly the regression the omission exists to prevent.
 *  * **Nesting round-trips.** The AST is recursive, so a serializer that handles one level
 *    would pass a flat test and fail on `a + b * (c - d)`.
 *  * **A safe member is safe by omission.** The default is the claim under test.
 *
 * Every encode in this file goes through the sealed base type. That is not a habit; see
 * [discriminatorOf].
 */
class ExprTest {

    // -----------------------------------------------------------------------------------
    // The variants
    // -----------------------------------------------------------------------------------

    /** An expression and the tag it must write. */
    private data class Case(val expr: Expr, val tag: String)

    /**
     * All nine of PLAN §10.1's variants, each with the tag it must write.
     *
     * One list carrying both, because a list of expressions and a separate list of tags would
     * drift: a variant added to one and not the other still compiles, still round-trips, and
     * is quietly untested for its tag.
     */
    private val cases: List<Case> = listOf(
        Case(Expr.Const(Value.Int32(1)), "const"),
        Case(Expr.Ref(RefTarget.State(StateId("s_count"))), "ref"),
        Case(Expr.Member(Expr.Ref(RefTarget.Param(ParamName("user"))), "name"), "member"),
        Case(Expr.Call(FunctionId("list.isNotEmpty"), listOf(Expr.Const(Value.Bool(true)))), "call"),
        Case(Expr.Unary(UnaryOp.Not, Expr.Const(Value.Bool(false))), "unary"),
        Case(
            Expr.Binary(BinaryOp.Add, Expr.Const(Value.Int32(1)), Expr.Const(Value.Int32(2))),
            "binary",
        ),
        Case(
            Expr.If(
                cond = Expr.Const(Value.Bool(true)),
                then = Expr.Const(Value.Int32(1)),
                otherwise = Expr.Const(Value.Int32(0)),
            ),
            "if",
        ),
        Case(Expr.ListLiteral(listOf(Expr.Const(Value.Int32(1)))), "list"),
        Case(Expr.Template(listOf(Expr.Const(Value.Str("Hi ")), Expr.Const(Value.Int32(42)))), "template"),
    )

    @Test
    fun `the ast has nine variants and no two share a tag`() {
        // Nine, transcribed from §10.1. Asserted because §10.2 is a closed feature list and a
        // variant that went missing would still compile and still pass every round trip below,
        // while failing to read a document that used it.
        assertEquals(9, cases.size, "the list of variants is not §10.1's")

        // A duplicate tag is silent corruption: both variants are legal Kotlin, the encoder
        // writes the same discriminator for both, and the decoder picks one. Nothing errors.
        val tags = cases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two variants share a tag: $tags")
    }

    @Test
    fun `every variant round trips through json and comes back equal`() {
        for (case in cases) {
            val text = Json.encodeToString<Expr>(case.expr)
            assertEquals(
                case.expr,
                Json.decodeFromString<Expr>(text),
                "lost '${case.tag}' via '$text'",
            )
        }
    }

    @Test
    fun `the serial name is the wire form and not the class name`() {
        for (case in cases) {
            val text = Json.encodeToString<Expr>(case.expr)

            // What a document actually stores, read back out of the JSON rather than off the
            // annotation — reading the annotation would be the test agreeing with itself.
            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")

            // The rename guard. All nine differ from their class name today, which is what
            // lets the assertion be made without exceptions: a variant whose tag started
            // following its class name would read no document written before the rename.
            assertNotEquals(
                case.expr::class.simpleName,
                case.tag,
                "'${case.expr::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `a call is written with its discriminator and a name in front of it`() {
        // The exact prefix, because "contains a tag somewhere" is a weaker claim than the one
        // that matters. A document stores `{"type":"call",…}` — the discriminator first, then
        // the fields in declaration order — and a reader that has to search for either is a
        // reader that will eventually find the wrong one.
        val call = Expr.Call(
            function = FunctionId("list.isEmpty"),
            args = listOf(Expr.Ref(RefTarget.State(StateId("s_items")))),
        )

        val text = Json.encodeToString<Expr>(call)

        assertTrue(
            text.startsWith("""{"type":"call","""),
            "not a discriminated call: '$text'",
        )
        assertTrue("\"function\":\"list.isEmpty\"" in text, "the id is not a bare string: '$text'")
        assertEquals(call, Json.decodeFromString<Expr>(text))
    }

    @Test
    fun `encoding through a concrete type is the one that drops the discriminator`() {
        // Recorded because it cost a red CI run, twice, and because the fix is invisible in
        // the diff: `Json.encodeToString(case.expr)` compiles, passes a round trip, and
        // produces a document no other reader can read. `encodeToString` infers its type
        // argument from the value, so a concrete subtype resolves that subtype's own
        // serializer and a concrete serializer writes no discriminator at all.
        //
        // A real document always holds an `Expr` behind a `PropertyValue.Computed` field, so
        // the polymorphic path is the one that runs. This test exists so that the reason every
        // encode in this file says `encodeToString<Expr>(…)` is written down where the next
        // person adding a test will read it.
        val call = Expr.Call(FunctionId("list.isEmpty"), emptyList())

        assertFalse(
            "\"type\":\"call\"" in Json.encodeToString(call),
            "the concrete serializer started writing a discriminator; re-check this test",
        )
        assertTrue(
            "\"type\":\"call\"" in Json.encodeToString<Expr>(call),
            "the base type stopped writing a discriminator",
        )
    }

    // -----------------------------------------------------------------------------------
    // Nesting
    // -----------------------------------------------------------------------------------

    @Test
    fun `a nested expression round trips at every level`() {
        // `(a + b) * (c - d)`, with a member access, a call and a safe access on top. A
        // serializer that handled one level would pass every flat case above and fail on this
        // one, which is the whole reason the AST is tested as a tree rather than as nine
        // independent shapes.
        val expression: Expr = Expr.Binary(
            op = BinaryOp.Mul,
            left = Expr.Binary(
                op = BinaryOp.Add,
                left = Expr.Ref(RefTarget.State(StateId("s_a"))),
                right = Expr.Const(Value.Int32(1)),
            ),
            right = Expr.Call(
                function = FunctionId("core.coalesce"),
                args = listOf(
                    Expr.Member(
                        receiver = Expr.Ref(RefTarget.Param(ParamName("user"))),
                        name = "nickname",
                        safe = true,
                    ),
                    Expr.Const(Value.Str("anon")),
                ),
            ),
        )

        val text = Json.encodeToString<Expr>(expression)

        assertEquals("binary", discriminatorOf(text), "the root is not a binary in '$text'")
        assertEquals(expression, Json.decodeFromString<Expr>(text))
    }

    @Test
    fun `a template keeps its parts and its canonical numbers`() {
        // §10.5 emits `"Hello ${user.name}"` for this tree, and the number inside it is
        // written by `CanonicalFloat` — D1 has to hold through an expression and not only
        // through a property, because the same `Value` is what the evaluator produces.
        val template = Expr.Template(
            listOf(
                Expr.Const(Value.Str("Hello ")),
                Expr.Member(Expr.Ref(RefTarget.Param(ParamName("user"))), "name"),
                Expr.Const(Value.Dp(16.5f)),
            ),
        )

        val text = Json.encodeToString<Expr>(template)

        assertEquals(
            "{\"type\":\"template\",\"parts\":[" +
                "{\"type\":\"str\",\"v\":\"Hello \"}," +
                "{\"type\":\"member\",\"receiver\":{\"type\":\"ref\"," +
                "\"target\":{\"type\":\"param\",\"name\":\"user\"}},\"name\":\"name\"}," +
                "{\"type\":\"dp\",\"v\":16.5}]}",
            text,
        )
        assertEquals(template, Json.decodeFromString<Expr>(text))
    }

    // -----------------------------------------------------------------------------------
    // Member.safe
    // -----------------------------------------------------------------------------------

    @Test
    fun `a member is unsafe unless it says otherwise`() {
        // The default is the claim, so it is written as a document rather than produced by
        // the encoder: a hand-written fragment with no `safe` key is what every document that
        // omits it contains, and reading one proves the default rather than the encoder.
        val decoded = Json.decodeFromString<Expr>(
            "{\"type\":\"member\",\"receiver\":{\"type\":\"ref\"," +
                "\"target\":{\"type\":\"state\",\"id\":\"s_user\"}},\"name\":\"name\"}",
        )

        val member = assertIs<Expr.Member>(decoded, "the default made it something else")
        assertFalse(member.safe, "a member with no 'safe' key defaulted to the safe form")
        assertEquals("name", member.name)
    }

    @Test
    fun `a safe member says so on the wire and comes back saying so`() {
        val safe = Expr.Member(
            receiver = Expr.Ref(RefTarget.State(StateId("s_user"))),
            name = "name",
            safe = true,
        )
        val unsafe = safe.copy(safe = false)

        val safeText = Json.encodeToString<Expr>(safe)
        val unsafeText = Json.encodeToString<Expr>(unsafe)

        assertTrue("\"safe\":true" in safeText, "safe access is not on the wire: '$safeText'")
        assertEquals(safe, Json.decodeFromString<Expr>(safeText))
        assertEquals(unsafe, Json.decodeFromString<Expr>(unsafeText))
    }

    // -----------------------------------------------------------------------------------
    // The operators
    // -----------------------------------------------------------------------------------

    @Test
    fun `a unary operator is written as its serial name`() {
        val expected = mapOf(UnaryOp.Not to "not", UnaryOp.Neg to "neg")

        assertEquals(2, UnaryOp.entries.size, "UnaryOp is not §10.1's two entries")

        for ((op, name) in expected) {
            assertEquals("\"$name\"", Json.encodeToString<UnaryOp>(op), "wrong name for $op")
            assertEquals(op, Json.decodeFromString<UnaryOp>("\"$name\""))
            assertNotEquals(op.name, name, "'$name' is the entry name, not the wire form")
        }
    }

    @Test
    fun `a binary operator is written as its serial name`() {
        // All eleven, one entry each, and the two pairs that a reader is most likely to swap.
        // `Eq`/`Neq` are named after the operation because neither has a word a person would
        // recognise as a symbol, and `Le`/`Ge` follow `Lt`/`Gt` exactly as §10.1 orders them.
        val expected = mapOf(
            BinaryOp.Add to "add",
            BinaryOp.Sub to "sub",
            BinaryOp.Mul to "mul",
            BinaryOp.Eq to "eq",
            BinaryOp.Neq to "neq",
            BinaryOp.Lt to "lt",
            BinaryOp.Le to "le",
            BinaryOp.Gt to "gt",
            BinaryOp.Ge to "ge",
            BinaryOp.And to "and",
            BinaryOp.Or to "or",
        )

        assertEquals(11, BinaryOp.entries.size, "BinaryOp is not §10.1's eleven entries")

        for ((op, name) in expected) {
            assertEquals("\"$name\"", Json.encodeToString<BinaryOp>(op), "wrong name for $op")
            assertEquals(op, Json.decodeFromString<BinaryOp>("\"$name\""))
            assertNotEquals(op.name, name, "'$name' is the entry name, not the wire form")
        }
    }

    @Test
    fun `binary op has no div and no mod and that is the decision not an oversight`() {
        // D10, asserted rather than documented. The whole list, in §10.1's order, so that
        // *adding* an entry is what fails — which is the moment a reader needs the reason.
        //
        // Integer division and modulo are refused because they cannot be made to mean the same
        // thing in the interpreter and in the generated Kotlin without more care than the
        // language is worth: `a / 0` throws in generated code, an interpreter has to answer
        // something, and whatever it answers is a value the generated code cannot produce.
        // §10.2 lists `/` and `%` under Excluded, §10.6's hazard table gives the row, and D10
        // in the decision table is the sentence that says it on purpose.
        //
        // The extension point is `FunctionRegistry` — §10.6 requires every function to be
        // total, and a total function has already made the division-by-zero decision somewhere
        // a document cannot see. If you are here to add `Div`, read `BinaryOp`'s KDoc first.
        assertEquals(
            listOf("Add", "Sub", "Mul", "Eq", "Neq", "Lt", "Le", "Gt", "Ge", "And", "Or"),
            BinaryOp.entries.map { it.name },
            "BinaryOp changed; D10 is the reason it must not gain Div or Mod",
        )

        assertFalse(
            BinaryOp.entries.any { it.name == "Div" || it.name == "Mod" },
            "D10: `/` and `%` are excluded because integer division cannot be made to agree " +
                "between the interpreter and the generated Kotlin",
        )
    }

    @Test
    fun `an operator is written as a name inside the node that carries it`() {
        // The enums round-trip on their own, but a document holds them inside a `Binary` or a
        // `Unary`, and a spelling that is right alone and wrong in place is still wrong.
        val text = Json.encodeToString<Expr>(
            Expr.Binary(
                op = BinaryOp.Neq,
                left = Expr.Const(Value.Null),
                right = Expr.Const(Value.Str("")),
            ),
        )

        assertTrue("\"op\":\"neq\"" in text, "the operator is not a bare word: '$text'")
        assertTrue("\"type\":\"null\"" in text, "the null value lost its tag: '$text'")
    }

    // -----------------------------------------------------------------------------------
    // RefTarget
    // -----------------------------------------------------------------------------------

    /** A reference target and the tag it must write. */
    private data class TargetCase(val target: RefTarget, val tag: String)

    /** All four of §10.1's targets, each with the tag it must write. */
    private val targetCases: List<TargetCase> = listOf(
        TargetCase(RefTarget.State(StateId("s_count")), "state"),
        TargetCase(RefTarget.Param(ParamName("title")), "param"),
        TargetCase(RefTarget.EventArg("count"), "event"),
        TargetCase(RefTarget.Item("item"), "item"),
    )

    @Test
    fun `the four ref targets round trip and carry their tags`() {
        assertEquals(4, targetCases.size, "the list of targets is not §10.1's")

        val tags = targetCases.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "two targets share a tag: $tags")

        for (case in targetCases) {
            val text = Json.encodeToString<RefTarget>(case.target)

            assertEquals(case.tag, discriminatorOf(text), "wrong discriminator in '$text'")
            assertEquals(case.target, Json.decodeFromString<RefTarget>(text))

            // The same rename guard as the outer union. All four differ from their class name
            // today, which is what lets this be asserted without exceptions.
            assertNotEquals(
                case.target::class.simpleName,
                case.tag,
                "'${case.target::class.simpleName}' is writing itself as the tag",
            )
        }
    }

    @Test
    fun `a ref nests a ref target without the two discriminators colliding`() {
        // Both hierarchies are polymorphic and both use `type`, so one document word carries
        // the key at two depths. It looks like a mistake and is not: the objects are separate
        // and the inner one is unambiguously a `RefTarget`. Asserted because a future change
        // to either key would produce something that still decodes and reads differently.
        val text = Json.encodeToString<Expr>(Expr.Ref(RefTarget.State(StateId("s_count"))))

        assertEquals(
            """{"type":"ref","target":{"type":"state","id":"s_count"}}""",
            text,
        )
        assertEquals(Expr.Ref(RefTarget.State(StateId("s_count"))), Json.decodeFromString<Expr>(text))
    }

    @Test
    fun `a param reference is a checked name and an event arg is not`() {
        // The asymmetry in §10.1, made visible. `Param` carries a `ParamName`, so a name that
        // is not a legal identifier cannot be built; `EventArg` and `Item` carry a bare
        // `String` because they name a binding in scope, which nothing in the document
        // declares. Both halves are asserted, because a one-sided test would pass whichever
        // way the asymmetry was accidentally resolved.
        assertFailsWith<IllegalArgumentException> { RefTarget.Param(ParamName("not a name")) }

        // An event argument is bounded by nothing in this module, on purpose: a scope name is
        // looked up by analysis, and refusing it at construction would refuse a legal name
        // before anything had a chance to resolve it.
        assertEquals(
            RefTarget.EventArg("an argument with spaces"),
            Json.decodeFromString<RefTarget>(
                Json.encodeToString<RefTarget>(RefTarget.EventArg("an argument with spaces")),
            ),
        )
    }

    // -----------------------------------------------------------------------------------
    // Closed unions
    // -----------------------------------------------------------------------------------

    @Test
    fun `a tag the ast does not have is refused`() {
        // The honest limit of a closed union, pinned so it stays a limit. §10.2's postponed
        // list — lambdas, assignment, loops — is why there is no tenth variant, and a document
        // that names one has to fail rather than be quietly read as something it is not.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Expr>("""{"type":"assign","target":"s_count"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<RefTarget>("""{"type":"derived","id":"s_derived"}""")
        }
    }

    @Test
    fun `a call into a function this engine has never heard of still decodes`() {
        // D4 again, in the shape an expression actually takes. §10.1's `Call` carries a
        // `FunctionId`, and a document written against a plugin that shipped a function this
        // engine does not have must still load — otherwise opening the file destroys the
        // plugin's work. The id is a string the model checks for *syntax* and nothing else;
        // whether the registry has it is §10.2's `FunctionRegistry` and analysis's business.
        val text = """{"type":"call","function":"vendor.trackEvent","args":[]}"""

        assertEquals(
            Expr.Call(FunctionId("vendor.trackEvent"), emptyList()),
            Json.decodeFromString<Expr>(text),
        )
        assertEquals(
            text,
            Json.encodeToString<Expr>(Expr.Call(FunctionId("vendor.trackEvent"), emptyList())),
        )
    }

    /**
     * The discriminator, read out of encoded JSON rather than off the annotation.
     *
     * Parsing the text back is the point: reading the tag off the serializer descriptor would
     * be reading the same declaration the test is supposed to be checking.
     *
     * Every caller encodes through the sealed base — `encodeToString<Expr>(…)` — and that is
     * not decoration. `encodeToString` infers its type argument from the value, so a concrete
     * subtype resolves that subtype's own serializer, and a concrete serializer writes no
     * discriminator at all: `encodeToString(Expr.Call(…))` produces `{"function":…,"args":[…]}`
     * and this helper throws on it. See
     * [encoding through a concrete type is the one that drops the discriminator].
     */
    private fun discriminatorOf(text: String): String =
        Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
}
