package dev.rotalex.lutter.editing.patch

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire form of the patch vocabulary: §26.2's "serializable, deterministic".
 *
 * ### Why this is a separate file
 *
 * `NoImplicitSerialNameTest` in `:tools:architecture-tests` is a *text* scanner, and a scanner
 * cannot tell whether a `@SerialName` was ever read by anything. What it can do is require the
 * annotation to be there; what only a test can do is require the annotation to mean something.
 * `Value`'s own KDoc makes that argument for itself — "`ValueTest` encodes every variant and
 * reads the discriminator back out of the JSON, so a rename that changes the wire form fails a
 * test rather than a review" — and this file is the same test for `PatchOp`.
 *
 * The second thing it pins is that a `Patch` writes exactly one field. `touchedNodes` is a
 * computed property with no backing field, and a member *with* a backing field inside a
 * `@Serializable` class is serialized: adding one would put a derived, redundant copy of
 * something every reader can compute on the wire, where two engines could then disagree about
 * it. The key list is asserted rather than trusted.
 *
 * ### The `Json` here, and the one line that must not be shortened
 *
 * `:engine:serialization`'s single configured instance is `internal` to that module and §23.3
 * puts it out of this module's reach, so the default `Json` is used — the same one `ValueTest`,
 * `TypeRefTest` and `NodeTest` use. The project's other spelling, `ForgeJson`'s
 * `classDiscriminator = "k"`, applies only to a hierarchy that does not pin its own key, and
 * every persisted hierarchy in `:engine:model` pins `"type"` through `@JsonClassDiscriminator`.
 * This module pins `"type"` the same way, so the default `Json` is the right one.
 *
 * **Every [tagOf] call below names its hierarchy: `tagOf<PatchOp>(…)`, not `tagOf(…)`.** That
 * is not ceremony, and getting it wrong fails the way this file first failed: with the type
 * argument left to inference, `tagOf(PatchOp.InsertNodes(…))` makes `T` the *concrete class*,
 * `serializer<T>()` resolves that class's own serializer instead of the sealed hierarchy's,
 * and the encoder writes `{"parent":…,"slot":…}` with no discriminator at all. Nothing throws —
 * the bytes are valid JSON and simply have no `type` key, so the failure surfaces as a
 * `NoSuchElementException` from the lookup rather than as anything about serialization.
 *
 * `NodeTest`'s KDoc states the rule this file is following, and it is worth repeating because
 * it is the single easiest mistake to make in this repository: *"every encode that asserts
 * bytes pins its base type — `Json.encodeToString<Node>(node)`. `encodeToString` infers its
 * type argument from the value, so an unannotated call resolves the concrete serializer and,
 * for a sealed union, writes no discriminator at all."* `ValueTest` gets the same effect
 * without the annotation because its fixture declares `val value: Value`, so inference lands on
 * the hierarchy by accident of the fixture rather than by decision.
 */
class PatchWireFormatTest {

    private val root = NodeId("n_root")
    private val child = NodeId("n_c")
    private val children = SlotName("children")
    private val content = SlotName("content")
    private val childRow = Node(id = child, type = ComponentType("m3.Text"))

    /**
     * Every tag, spelled out, one per op.
     *
     * Asserted individually rather than as a set, because a set assertion would still pass if
     * two ops swapped tags — and two ops with the same tag is a patch log that decodes into the
     * wrong edits, which is the failure the annotation exists to prevent.
     */
    @Test
    fun `every op spells its own tag on the wire`() {
        assertEquals("insertNodes", tagOf<PatchOp>(PatchOp.InsertNodes(root, children, 0, listOf(childRow))))
        assertEquals("putNodes", tagOf<PatchOp>(PatchOp.PutNodes(listOf(childRow))))
        assertEquals(
            "removeSubtree",
            tagOf<PatchOp>(PatchOp.RemoveSubtree(child, RemovedSubtree(root, children, 0, listOf(childRow)))),
        )
        assertEquals(
            "moveNode",
            tagOf<PatchOp>(PatchOp.MoveNode(child, Placement(root, children, 0), Placement(root, content, 0))),
        )
        assertEquals(
            "setProp",
            tagOf<PatchOp>(
                PatchOp.SetProp(
                    child,
                    PropertyKey("text"),
                    PropertyValue.Const(Value.Str("a")),
                    PropertyValue.Const(Value.Str("b")),
                ),
            ),
        )
        assertEquals(
            "setModifiers",
            tagOf<PatchOp>(
                PatchOp.SetModifiers(child, listOf(ModifierEntry(ModifierType("layout.padding"))), emptyList()),
            ),
        )
        assertEquals(
            "setHandler",
            tagOf<PatchOp>(
                PatchOp.SetHandler(
                    child,
                    EventKey("onClick"),
                    ActionSequence(listOf(ActionStep(ActionId("nav.back")))),
                    null,
                ),
            ),
        )
        assertEquals("setNodeName", tagOf<PatchOp>(PatchOp.SetNodeName(child, null, "a")))
        assertEquals(
            "putRecord",
            tagOf<PatchOp>(PatchOp.PutRecord(RecordRef.Theme(ThemeId("t_base")), null, null)),
        )

        // The two inner hierarchies are the same argument one level down, and they guard the
        // same failure: a `PutRecord` whose `ref` and whose record disagree about what they are
        // would decode as a reference to one thing carrying another. These two already named
        // their hierarchy, which is what the eight above had to be taught to do.
        assertEquals("theme", tagOf<RecordRef>(RecordRef.Theme(ThemeId("t_base"))))
        assertEquals("page", tagOf<Record>(Record.Page(Page(PageId("p_home"), "Home", "/home", root = root))))
    }

    /**
     * A patch holding every op, round-tripped.
     *
     * `Patch` rather than a bare `PatchOp`, because the interesting failure for the container is
     * an extra field and an extra field is only visible from outside the class. `touchedNodes` is
     * the one that would appear: it is a member, it is derived, and a member with a backing field
     * is serialized. It has none, so the object on the wire is `{"ops":[…]}` and nothing else —
     * asserted as a key list rather than as a substring, so that a *renamed* field fails too.
     *
     * The nine are the eight of §26.2 plus D30's `PutNodes`, and `PutNodes` is here rather than
     * in a second test because the count is the assertion: nine ops, nine entries, and a tenth
     * variant added without this list failing is the failure.
     */
    @Test
    fun `a patch holding every op round trips and writes only its ops`() {
        val patch = Patch(
            listOf(
                PatchOp.InsertNodes(root, children, 0, listOf(childRow)),
                PatchOp.PutNodes(listOf(childRow)),
                PatchOp.RemoveSubtree(child, RemovedSubtree(root, children, 0, listOf(childRow))),
                PatchOp.MoveNode(child, Placement(root, children, 0), Placement(root, content, 0)),
                PatchOp.SetProp(child, PropertyKey("text"), null, PropertyValue.Const(Value.Str("hello"))),
                PatchOp.SetModifiers(child, emptyList(), listOf(ModifierEntry(ModifierType("layout.padding")))),
                PatchOp.SetHandler(
                    child,
                    EventKey("onClick"),
                    null,
                    ActionSequence(listOf(ActionStep(ActionId("nav.back")))),
                ),
                // Null on *both* sides, which is the case a serializer that dropped nulls would
                // quietly turn into a record that says nothing.
                PatchOp.SetNodeName(child, null, null),
                PatchOp.PutRecord(
                    RecordRef.State(StateId("s_count"), 0),
                    Record.State(StateDecl(id = StateId("s_count"), name = "count", type = TypeRef.Str)),
                    null,
                ),
            ),
        )

        val text = Json.encodeToString<Patch>(patch)
        val parsed = Json.parseToJsonElement(text)
        val ops = parsed.jsonObject.getValue("ops").jsonArray

        assertEquals(listOf("ops"), parsed.jsonObject.keys.toList(), "the patch grew a wire field")
        assertEquals(9, ops.size)
        assertEquals(patch, Json.decodeFromString<Patch>(text), "the round trip changed the patch")

        // And the two levels of union nesting survive, which is `NodeTest`'s argument applied to
        // this vocabulary: `PutRecord.ref` and `PutRecord.old` are two *different* sealed
        // hierarchies at two depths, and only the nesting says which is which.
        val putRecord = ops[8].jsonObject
        assertEquals("putRecord", putRecord.tag(), "the outer union lost its own tag")
        assertEquals("state", putRecord.getValue("ref").jsonObject.tag(), "the reference lost its tag")
        assertEquals("state", putRecord.getValue("old").jsonObject.tag(), "the record lost its tag")
        assertEquals("null", putRecord.getValue("new").jsonPrimitive.content, "an explicit null was dropped")
    }

    /**
     * The set really is closed.
     *
     * A ninth op that some plugin contributed would decode as this one, and §26.2's whole
     * argument — that an inverse can be written for every op without a document — rests on
     * knowing the whole list. So the refusal is asserted rather than assumed; it is the same
     * refusal `Value` gets for an unknown tag, and for the same reason.
     */
    @Test
    fun `an op tag no engine has heard of does not decode`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PatchOp>("""{"type":"reorderEverything"}""")
        }
    }

    /**
     * The unplaced capture writes three explicit nulls, and reads back as one.
     *
     * D30 made [RemovedSubtree]'s three position fields nullable so that [PatchOp.PutNodes]
     * could have an inverse, and this pins what that costs on the wire. The keys are exactly
     * where they were — `parent`, `slot`, `index`, `nodes` — so a capture written before the
     * change still decodes; what is new is that the position may be *absent*, and "absent" has
     * to be a real JSON null rather than a missing key, or a reader could not tell it from a
     * capture of a different shape.
     *
     * The round trip is the assertion that matters: `decode(encode(x)) == x` for a value whose
     * fields are all null is the case a serializer that dropped nulls would turn into an
     * unplaced-placed removal, which applies as "delete these rows" against the wrong document.
     */
    @Test
    fun `an unplaced capture writes its position as three nulls and reads back as one`() {
        val unplaced = RemovedSubtree.unplaced(listOf(childRow))

        assertNull(unplaced.placement)
        assertEquals(RemovedSubtree(null, null, null, listOf(childRow)), unplaced)

        val encoded = Json.encodeToString<PatchOp>(
            PatchOp.RemoveSubtree(child, unplaced),
        )
        val decoded = Json.decodeFromString<PatchOp>(encoded)

        assertEquals(PatchOp.RemoveSubtree(child, unplaced), decoded)

        val captured = Json.parseToJsonElement(encoded).jsonObject.getValue("captured").jsonObject
        assertEquals("null", captured.getValue("parent").jsonPrimitive.content)
        assertEquals("null", captured.getValue("slot").jsonPrimitive.content)
        assertEquals("null", captured.getValue("index").jsonPrimitive.content)

        // And the placed form is unaffected: the same three keys, holding values.
        val placed = RemovedSubtree(root, children, 0, listOf(childRow))
        assertEquals(Placement(root, children, 0), placed.placement)
        assertEquals(
            PatchOp.RemoveSubtree(child, placed),
            Json.decodeFromString<PatchOp>(Json.encodeToString<PatchOp>(PatchOp.RemoveSubtree(child, placed))),
        )
    }

    /**
     * The discriminator of [value], read back out of its encoded form.
     *
     * `encodeToString<T>` rather than `encodeToString`, always — see the class KDoc. With `T`
     * pinned to the hierarchy, `serializer<T>()` is the sealed serializer and the tag is
     * written; with `T` inferred from the argument, it is the concrete class's serializer and
     * there is no tag to read.
     */
    private inline fun <reified T> tagOf(value: T): String =
        Json.parseToJsonElement(Json.encodeToString<T>(value)).jsonObject.tag()

    private fun JsonObject.tag(): String = getValue("type").jsonPrimitive.content
}