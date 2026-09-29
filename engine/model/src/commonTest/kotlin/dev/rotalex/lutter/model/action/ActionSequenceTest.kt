package dev.rotalex.lutter.model.action

import dev.rotalex.lutter.model.expr.BinaryOp
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `ActionSequence` contract: an event handler as data, and the promise that it is.
 *
 * §11.2's model is two types, so most of what is worth testing here is not a field but a
 * *shape*: that a handler is JSON and not a closure, that its arguments may be computed, that
 * its branches nest, and that all three survive a round trip in the form a document stores
 * them.
 *
 * The tests are grouped by the promise each one defends:
 *
 *  * **Actions are data.** A handler round-trips through JSON, and an action id this engine has
 *    never heard of still decodes — the D4 property an action needs more than any other
 *    record, because a handler is the one part of a document a plugin extends freely.
 *  * **Arguments may be computed.** This is the field that makes an action able to read the
 *    document at all, and it is the one a future refactor to "just literals" would silently
 *    take away.
 *  * **Branches nest**, and the depth is a document's business, not the model's.
 *  * **The defaults are the wire form**, because a handler that does one thing should be one
 *    object on the page rather than three.
 */
class ActionSequenceTest {

    // -----------------------------------------------------------------------------------
    // The shape of a handler
    // -----------------------------------------------------------------------------------

    @Test
    fun `a sequence of plain steps round trips`() {
        val sequence = ActionSequence(
            listOf(
                ActionStep(ActionId("state.set")),
                ActionStep(ActionId("nav.navigate")),
                ActionStep(ActionId("nav.back")),
            ),
        )

        val text = Json.encodeToString<ActionSequence>(sequence)

        assertEquals(sequence, Json.decodeFromString<ActionSequence>(text), "lost via '$text'")

        // The exact bytes, because the defaults are part of the claim. `args` and `branches`
        // default to empty and a default is not written, so a handler that does one thing is
        // `{"action":"nav.back"}` and nothing else — a person reading a document should not
        // have to skip past three empty objects to find out what a step does.
        assertEquals(
            """{"steps":[{"action":"state.set"},{"action":"nav.navigate"},{"action":"nav.back"}]}""",
            text,
        )
        // The *first* step. The document holds three, and the claim is that the field is
        // named `action` on the wire - which the first step is as good a witness as the last,
        // and the one the helper actually reads.
        assertEquals("state.set", actionOfFirstStep(text), "the action key moved on the wire")
    }

    @Test
    fun `an empty sequence is a sequence`() {
        // Not a special case worth a type: a handler that does nothing is a legitimate
        // document, and refusing it would push an author to write a step that does nothing at
        // all just to have something to put in the list.
        val empty = ActionSequence(emptyList())

        assertEquals("""{"steps":[]}""", Json.encodeToString<ActionSequence>(empty))
        assertEquals(empty, Json.decodeFromString<ActionSequence>("""{"steps":[]}"""))
    }

    @Test
    fun `an action this engine has never heard of still decodes`() {
        // §11.2 says an action is a registry key, and §23.3 says the model may depend on no
        // other module — so the registry is not visible here and could not be consulted if it
        // were. D4 is what makes that acceptable: a document written against a plugin that
        // ships `analytics.log` loads on an engine that has never heard of it, and validation
        // reports the unknown id as a diagnostic rather than the file failing to open.
        val text = "{\"steps\":[{\"action\":\"vendor.track\",\"args\":{\"event\":{\"type\":" +
            "\"const\",\"value\":{\"type\":\"str\",\"v\":\"checkout\"}}}}]}"

        val decoded = Json.decodeFromString<ActionSequence>(text)

        assertEquals(1, decoded.steps.size)
        assertEquals(ActionId("vendor.track"), decoded.steps[0].action)
        assertEquals(
            PropertyValue.Const(Value.Str("checkout")),
            decoded.steps[0].args[PropertyKey("event")],
        )
        assertEquals(text, Json.encodeToString<ActionSequence>(decoded), "the round trip rewrote it")
    }

    // -----------------------------------------------------------------------------------
    // Arguments, and the computed ones
    // -----------------------------------------------------------------------------------

    @Test
    fun `an argument may be a computed expression and not only a constant`() {
        // §11.2's comment on `args` is *"typed, may contain Computed exprs"*, and the second
        // half is the load-bearing one. It is what makes an action able to read the document:
        // a branch on state, a host call whose argument depends on the event, a value that is
        // the sum of two things. An action whose arguments had to be constants could not
        // branch, could not transform, and could not talk to a host function.
        //
        // Both arms in one step, because a handler that only ever held constants would pass a
        // test that only ever wrote constants.
        val step = ActionStep(
            action = ActionId("state.set"),
            args = mapOf(
                PropertyKey("key") to PropertyValue.Const(Value.Str("s_count")),
                PropertyKey("value") to PropertyValue.Computed(
                    Expr.Binary(
                        op = BinaryOp.Add,
                        left = Expr.Ref(RefTarget.State(StateId("s_count"))),
                        right = Expr.Const(Value.Int32(1)),
                    ),
                ),
            ),
        )

        val text = Json.encodeToString<ActionSequence>(ActionSequence(listOf(step)))

        assertEquals(
            step,
            Json.decodeFromString<ActionSequence>(text).steps[0],
            "the computed argument came back as something else: '$text'",
        )
        assertTrue("\"type\":\"const\"" in text, "the constant arm lost its tag: '$text'")
        assertTrue("\"type\":\"expr\"" in text, "the computed arm lost its tag: '$text'")
        // Under `op`, not `type`: `type` is the discriminator of the sealed `Expr` hierarchy
        // and `add` is the `@SerialName` of a `BinaryOp` entry inside the `op` field. An
        // operator is a value in a field, not a variant of the union, so looking for it under
        // the discriminator finds nothing.
        assertTrue("\"op\":\"add\"" in text, "the operator is not a bare word: '$text'")
    }

    @Test
    fun `an argument map is keyed by a checked name on both sides`() {
        // PLAN §5.3's rule, and the reason `NoUntypedStringMapTest` has nothing to find in
        // this module. Both halves of the map are closed domain types: the key validates its
        // own syntax on construction, the value is a two-arm union. A map keyed by a bare
        // string would put a document's arguments past the point where the type system can
        // help, and the check that a key *is* an argument name belongs to `ActionSpec`.
        assertEquals(
            "{\"steps\":[{\"action\":\"state.set\",\"args\":{\"value\":{\"type\":\"const\"," +
                "\"value\":{\"type\":\"i32\",\"v\":7}}}}]}",
            Json.encodeToString<ActionSequence>(
                ActionSequence(
                    listOf(
                        ActionStep(
                            action = ActionId("state.set"),
                            args = mapOf(
                                PropertyKey("value") to PropertyValue.Const(Value.Int32(7)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // The key half, on both sides of the map. A `PropertyKey` is a *simple* name — an
        // argument is a parameter of an action, not a registry key — so a dot is refused and so
        // is anything that is not an identifier. The value half is refused by the union, and
        // an unknown tag is a refused document rather than a half-read one.
        assertFailsWith<IllegalArgumentException> { PropertyKey("has space") }
        assertFailsWith<IllegalArgumentException> { PropertyKey("has.dot") }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ActionSequence>(
                """{"steps":[{"action":"state.set","args":{"value":{"type":"bind","expr":"s_count"}}}]}""",
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // Branches
    // -----------------------------------------------------------------------------------

    @Test
    fun `a flow if nests a sequence inside a step inside a sequence`() {
        // `onClick = flow.if { s_count > 0 -> nav.back }`, which is the shape §11.2 exists
        // for: a branch is a whole `ActionSequence`, so a branch can be more than one step,
        // and a step can branch.
        val sequence = ActionSequence(
            listOf(
                ActionStep(
                    action = ActionId("flow.if"),
                    args = mapOf(
                        PropertyKey("cond") to PropertyValue.Computed(
                            Expr.Binary(
                                op = BinaryOp.Gt,
                                left = Expr.Ref(RefTarget.State(StateId("s_count"))),
                                right = Expr.Const(Value.Int32(0)),
                            ),
                        ),
                    ),
                    branches = mapOf(
                        BranchName("then") to ActionSequence(
                            listOf(ActionStep(ActionId("nav.back"))),
                        ),
                        BranchName("else") to ActionSequence(emptyList()),
                    ),
                ),
            ),
        )

        val text = Json.encodeToString<ActionSequence>(sequence)
        val decoded = Json.decodeFromString<ActionSequence>(text)

        assertEquals(sequence, decoded, "the branch nesting did not survive: '$text'")

        // Read the nesting back the way a document reader would. Equality alone would pass a
        // decoder that flattened the branches and rebuilt them symmetrically, which is exactly
        // the bug that would be invisible until a branch ran the wrong steps.
        val step = decoded.steps[0]
        assertEquals(ActionId("flow.if"), step.action)
        assertEquals(2, step.branches.size, "a branch was lost: ${step.branches}")
        assertEquals(
            ActionId("nav.back"),
            step.branches.getValue(BranchName("then")).steps[0].action,
            "the 'then' branch lost its step",
        )
        assertEquals(
            0,
            step.branches.getValue(BranchName("else")).steps.size,
            "the 'else' branch is not empty",
        )
    }

    @Test
    fun `a branch holds a computed argument of its own`() {
        // The two features together, which is the shape §11.2's two comments add up to: a
        // branch is a whole `ActionSequence`, so it carries the same argument currency as the
        // step that owns it — computed expressions included — and a branch nested in a branch
        // is the same construction one level down.
        val inner = ActionStep(
            action = ActionId("state.set"),
            args = mapOf(
                PropertyKey("value") to PropertyValue.Computed(
                    Expr.Call(
                        function = FunctionId("core.coalesce"),
                        args = listOf(Expr.Const(Value.Null), Expr.Const(Value.Int32(0))),
                    ),
                ),
            ),
        )
        val nested = ActionStep(
            action = ActionId("flow.if"),
            branches = mapOf(BranchName("then") to ActionSequence(listOf(inner))),
        )
        val step = ActionStep(
            action = ActionId("flow.if"),
            branches = mapOf(BranchName("then") to ActionSequence(listOf(nested))),
        )

        val text = Json.encodeToString<ActionSequence>(ActionSequence(listOf(step)))
        val decoded = Json.decodeFromString<ActionSequence>(text)

        assertEquals(step, decoded.steps[0], "the nested branch did not survive: '$text'")
        assertTrue("\"type\":\"expr\"" in text, "the nested computed arm lost its tag: '$text'")
        assertTrue("\"core.coalesce\"" in text, "the function id is not a bare string: '$text'")
    }

    @Test
    fun `the branch names in the plan are the ones a document may use`() {
        // §11.2 writes `"then"/"else"` in the comment on `branches`, and `BranchName` is one
        // of §5.2's typed identifiers, so it validates its own syntax and these two are
        // ordinary simple names. Asserted because a document that spelled a branch differently
        // would be a document no engine agrees on, and the two words are the entire vocabulary.
        val names = listOf(BranchName("then"), BranchName("else"))

        assertEquals(listOf("then", "else"), names.map { it.value })
        assertEquals(listOf("then", "else"), names.map { it.toString() })
    }

    // -----------------------------------------------------------------------------------
    // Defaults, refusals, and the design constraint itself
    // -----------------------------------------------------------------------------------

    @Test
    fun `a step that omits its arguments and branches gets them back`() {
        // The round trip in the shape a hand-written document has it. Both fields are
        // defaulted, so the encoder leaves them out and the decoder has to put them back —
        // which is the only thing that makes `{"action":"nav.back"}` a complete step rather
        // than a fragment.
        val decoded = Json.decodeFromString<ActionSequence>("""{"steps":[{"action":"nav.back"}]}""")

        val step = decoded.steps[0]
        assertEquals(ActionId("nav.back"), step.action)
        assertEquals(emptyMap<PropertyKey, PropertyValue>(), step.args, "args did not come back empty")
        assertEquals(
            emptyMap<BranchName, ActionSequence>(),
            step.branches,
            "branches did not come back empty",
        )

        // And re-encoding produces the same fragment, so a document that is read and written
        // again is not rewritten by the round trip. Whitespace is not part of the promise;
        // the data is.
        assertEquals("""{"steps":[{"action":"nav.back"}]}""", Json.encodeToString(decoded))
    }

    @Test
    fun `an action id is a namespaced registry key`() {
        // §11.2 lists the actions by id in a comment and §5.2 requires a registry key to be
        // dotted, so a plugin can contribute `flow.if` without colliding with anything. The
        // named actions are asserted because they are the ones a document written today will
        // contain, and because a typo in a registry key is a document that silently does
        // nothing at runtime.
        for (id in listOf("nav.navigate", "nav.back", "state.set", "flow.if", "host.call")) {
            assertEquals(id, ActionId(id).value, "the id did not read back as itself")
            assertEquals(id, ActionId(id).toString(), "the id does not read as itself in a log")
        }

        // A single segment is refused: an action with no owner is a global namespace, which is
        // the collision §5.2's namespaced shape exists to prevent.
        assertFailsWith<IllegalArgumentException> { ActionId("navigate") }
    }

    @Test
    fun `a malformed handler is refused rather than half read`() {
        // Two shapes of wrong. A single object where a list belongs is a structural mismatch
        // and raises the format's own exception. An unnamespaced action id fails inside the
        // `ActionId` constructor, which runs during deserialization — T1 pins the exception
        // type for a top-level id, and this assertion deliberately does not, because a nested
        // failure inside a list inside a data class is the library's business rather than the
        // model's. What the model guarantees is the same either way: a document that is wrong
        // here fails where it is read, and not somewhere downstream of it.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ActionSequence>("""{"action":"nav.back"}""")
        }

        assertFails {
            Json.decodeFromString<ActionSequence>("""{"steps":[{"action":"navigate"}]}""")
        }
    }

    @Test
    fun `an action is data and a document diff can tell two of them apart`() {
        // The design constraint, made executable. §11.2 says "Actions are **data**, never
        // lambdas" and ADR-009 gives the reason: a handler has to be serializable,
        // translatable to generated Kotlin, and comparable in a document diff, and a lambda is
        // none of the three. The only way to *test* that inside a model module is to show the
        // thing is a value with equality and a stable textual form — which is what the round
        // trips above establish, restated here so the claim and its evidence sit together.
        val left = ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
        val right = ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
        val different = ActionSequence(listOf(ActionStep(ActionId("nav.navigate"))))

        // Structural equality is what a document diff compares, and it has to distinguish
        // "the same handler written twice" from "another handler".
        assertEquals(left, right, "two equal handlers did not compare equal")
        assertNotEquals(left, different, "two different handlers compared equal")
        assertEquals(
            Json.encodeToString<ActionSequence>(left),
            Json.encodeToString<ActionSequence>(right),
            "two equal handlers did not produce the same bytes",
        )
    }

    /**
     * The `action` field of the first step, read out of encoded JSON.
     *
     * `ActionStep` is a plain `@Serializable` data class and not a sealed union, so it has no
     * discriminator: `action` is a field of an element of a list, not a tag on a variant. The
     * helper is here for the one claim that needs it — that the field is *named* `action` on
     * the wire, which is as much a format contract as a `@SerialName` is, because a document
     * written by another tool depends on the spelling and nothing in this module would notice
     * a rename.
     */
    private fun actionOfFirstStep(text: String): String =
        // `ActionSequence` is a `data class` holding `steps`, so the root is an object and
        // the first step is one level in. The bare-array shape this used to read is what a
        // `@JvmInline value class` would have produced, and it is not what §11.2 declares.
        Json.parseToJsonElement(text)
            .jsonObject.getValue("steps")
            .jsonArray[0]
            .jsonObject
            .getValue("action")
            .jsonPrimitive
            .content
}
