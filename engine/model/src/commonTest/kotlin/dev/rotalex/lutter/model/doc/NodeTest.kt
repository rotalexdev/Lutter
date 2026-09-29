package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.PropertyValue
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
 * The `Node` contract: the record every other part of the engine reads.
 *
 * Seven fields is a short list, so almost nothing worth testing here is a field. What is worth
 * testing is the three *shapes* §5.3 and §5.6 argue for, because a nested tree, an untyped map
 * and an unordered modifier chain would each still compile, still round-trip and still pass a
 * test that only checked equality:
 *
 *  * **Children are ids.** `slots` holds `NodeId`s. A nested tree writes
 *    `"children":[{"id":…}]` where this writes `"children":["n_1"]`, so the wire form is the
 *    only place the two can be told apart — and that is what §5.6's entire trade rests on.
 *  * **The two orderings disagree on purpose.** `props` is a map whose canonical key order is
 *    a writer's job; `modifiers` is a list whose order *is* the meaning. Asserting both, in
 *    both directions, is the only way the asymmetry stays deliberate rather than accidental.
 *  * **A default that is not written is part of the format.** §6.2's canonical JSON encodes no
 *    defaults, so a node with no name, props, modifiers, slots or events is two fields of JSON
 *    and nothing else.
 *
 * On that last point one habit is not decoration: **every encode that asserts bytes pins its
 * base type** — `Json.encodeToString<Node>(node)`. `encodeToString` infers its type argument
 * from the value, so an unannotated call resolves the concrete serializer and, for a sealed
 * union, writes no discriminator at all. `Node` is not a union, so the inference would happen
 * to be harmless here; it is written out anyway, because a habit that only breaks for unions
 * is a habit that breaks for the next person who writes one.
 */
class NodeTest {

    // -----------------------------------------------------------------------------------
    // The shape of a node: required fields, defaults, and what is not on the wire
    // -----------------------------------------------------------------------------------

    @Test
    fun `a node with only its required fields is two fields of json`() {
        // The exact bytes, because "round trips" is the weaker claim and the weaker claim is
        // the one a reader of a document depends on. `name`, `props`, `modifiers`, `slots` and
        // `events` all default, and kotlinx.serialization's `encodeDefaults` is false, so a
        // node that does nothing else is `{"id":…,"type":…}`. §30.2's example document is
        // written this way: `n_btn_label` carries a `type` and a `props` and nothing else.
        val node = Node(id = NodeId("n_1"), type = ComponentType("core.Column"))

        val text = Json.encodeToString<Node>(node)

        assertEquals("""{"id":"n_1","type":"core.Column"}""", text)
        assertEquals(node, Json.decodeFromString<Node>(text))

        // The field names *are* the wire contract. §5.3 writes no `@SerialName` on any of them
        // and no annotation could help: the names are the Kotlin names, a document written by
        // another tool depends on the spelling, and nothing in this module would notice a
        // rename. So they are read back out of the JSON rather than off the declaration.
        assertEquals(
            listOf("id", "type"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "the node's field names moved on the wire",
        )

        // And `type` is an ordinary string field, not a tag. This class is not a union, so
        // there is no discriminator here for it to collide with — which is the one thing that
        // makes a field called `type` safe on `Node` and illegal on `Expr` and `Value`.
        assertEquals("core.Column", Json.parseToJsonElement(text).text("type"))
    }

    @Test
    fun `a decoded node gets its five defaults back and writes none of them`() {
        // The round trip in the shape a hand-written document has it: the encoder leaves the
        // defaults out, the decoder has to put them back, and the fragment that came in is the
        // fragment that goes out. A round trip that *rewrites* a document is how a format
        // starts growing fields nobody chose.
        val text = """{"id":"n_1","type":"core.Column"}"""

        val decoded = Json.decodeFromString<Node>(text)

        assertEquals(NodeId("n_1"), decoded.id)
        assertEquals(ComponentType("core.Column"), decoded.type)
        assertNull(decoded.name, "name did not come back null")
        assertEquals(emptyMap(), decoded.props, "props did not come back empty")
        assertEquals(emptyList(), decoded.modifiers, "modifiers did not come back empty")
        assertEquals(emptyMap(), decoded.slots, "slots did not come back empty")
        assertEquals(emptyMap(), decoded.events, "events did not come back empty")

        assertEquals(text, Json.encodeToString<Node>(decoded), "the round trip rewrote the node")

        // The omission is stable rather than incidental. A node built entirely out of defaults
        // and re-encoded is still those two fields, which is what §6.2's "no defaults encoded"
        // row depends on for a document to be byte-comparable.
        assertEquals(text, Json.encodeToString<Node>(decoded.copy()))
    }

    @Test
    fun `only id and type are required`() {
        // Two fields have no default and five do, and the difference is load-bearing in both
        // directions. A node missing its type is a node that cannot be rendered; a node
        // missing its props is a perfectly good leaf. Asserting it guards both ends — an edit
        // that gave `type` a default would turn "unrenderable" into "invisible", and one that
        // dropped a default would make every document in existence unreadable.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Node>("""{"id":"n_1"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Node>("""{"type":"core.Column"}""")
        }

        // And the optional half really is optional, in every position at once — including an
        // explicit `null` name, which is not the same statement as an omitted one.
        val complete = Json.decodeFromString<Node>(
            """{"id":"n_1","type":"core.Column","name":null,"props":{},"modifiers":[],""" +
                """"slots":{},"events":{}}""",
        )
        assertEquals(Node(id = NodeId("n_1"), type = ComponentType("core.Column")), complete)
        assertEquals("""{"id":"n_1","type":"core.Column"}""", Json.encodeToString<Node>(complete))
    }

    @Test
    fun `a name is written when it is there and an empty one is not a missing one`() {
        // `name` is a hint and nothing more, but "hint" does not mean "never serialized": a
        // label a person typed is in the document whether or not anything reads it, and losing
        // it on a round trip would make every §30.2-style diff lie.
        val named = Node(id = NodeId("n_root"), type = ComponentType("core.Column"), name = "content")

        assertEquals("""{"id":"n_root","type":"core.Column","name":"content"}""", Json.encodeToString<Node>(named))
        assertEquals("content", Json.decodeFromString<Node>(Json.encodeToString<Node>(named)).name)

        // An empty string is a label that happens to be blank, and it is not the same fact as
        // no label at all. The default keeps the two apart and both survive the round trip.
        val blank = Json.decodeFromString<Node>("""{"id":"n_root","type":"core.Column","name":""}""")

        assertEquals("", blank.name)
        assertEquals("""{"id":"n_root","type":"core.Column","name":""}""", Json.encodeToString<Node>(blank))
    }

    // -----------------------------------------------------------------------------------
    // Children are ids, and not a tree
    // -----------------------------------------------------------------------------------

    @Test
    fun `slots hold node ids and not nested nodes`() {
        // §5.6's whole trade, asserted where it is observable. A nested tree encodes
        // `"children":[{"id":"n_title","type":"m3.Text"}]`; a normalized table encodes
        // `"children":["n_title"]`. Both round-trip, both compile, and the difference between
        // them is the difference between O(1) lookup by id and a walk to the root — so it has
        // to be pinned as bytes, not as a type annotation nobody would notice changing.
        val parent = Node(
            id = NodeId("n_root"),
            type = ComponentType("core.Column"),
            slots = mapOf(SlotName("children") to listOf(NodeId("n_title"), NodeId("n_btn"))),
        )

        val text = Json.encodeToString<Node>(parent)

        assertEquals(
            """{"id":"n_root","type":"core.Column","slots":{"children":["n_title","n_btn"]}}""",
            text,
        )

        // Structurally: the slot's value is an array of primitives, not an array of objects.
        // `isString` is the whole assertion — a nested node would be a `JsonObject` here, and
        // `jsonPrimitive` on one would throw rather than quietly pass.
        val children = Json.parseToJsonElement(text).at("slots", "children").jsonArray
        assertEquals(2, children.size, "a child was lost: '$text'")
        assertTrue(
            children.all { it.jsonPrimitive.isString },
            "a child was written as an object, so slots holds nodes: '$text'",
        )

        // And structurally in the other direction, at compile time this time. The annotation
        // *is* the assertion: if `slots` ever held `Node`s this line would not compile, and no
        // runtime check would have caught the change before it reached a stored document.
        val decodedIds: List<NodeId> = Json.decodeFromString<Node>(text).slots.getValue(SlotName("children"))
        assertEquals(listOf(NodeId("n_title"), NodeId("n_btn")), decodedIds)
    }

    @Test
    fun `a slot names ids the table does not have to contain`() {
        // Normalization means a reference, and a reference is not resolved at decode time. The
        // child may live in another page's table or in a component declaration this engine has
        // never loaded, and §17.1's structural pass is what reports "all `NodeId`s in slots
        // exist" as a diagnostic — rather than what stops the file from opening.
        val dangling = Json.decodeFromString<Node>(
            """{"id":"n_root","type":"core.Column","slots":{"children":["n_gone"]}}""",
        )

        assertEquals(listOf(NodeId("n_gone")), dangling.slots.getValue(SlotName("children")))

        // Slots are *named* — `children`, `content`, `topBar` per §5.1 — and a document with
        // two of them says which is which. Collapsing them into one list would be a smaller
        // record and a document that cannot express `m3.Button`.
        val twoSlots = Node(
            id = NodeId("n_btn"),
            type = ComponentType("m3.Button"),
            slots = mapOf(
                SlotName("content") to listOf(NodeId("n_label")),
                SlotName("topBar") to listOf(NodeId("n_close")),
            ),
        )
        val decoded = Json.decodeFromString<Node>(Json.encodeToString<Node>(twoSlots))

        assertEquals(setOf("content", "topBar"), decoded.slots.keys.map { it.value }.toSet())
        assertEquals(listOf(NodeId("n_label")), decoded.slots.getValue(SlotName("content")))

        // A `SlotName` is one of §5.2's simple identifiers, so it validates its own syntax.
        assertFailsWith<IllegalArgumentException> { SlotName("has space") }
        assertFailsWith<IllegalArgumentException> { SlotName("has.dot") }
    }

    @Test
    fun `the order inside a slot is render order and survives`() {
        // A `List<NodeId>` and not a `Set<NodeId>`, for the same reason `modifiers` is a list:
        // position is layout. `children[0]` is the first item, and §15.4's runtime walks a slot
        // in order.
        val forwards = Node(
            id = NodeId("n_root"),
            type = ComponentType("core.Column"),
            slots = mapOf(SlotName("children") to listOf(NodeId("n_title"), NodeId("n_btn"))),
        )
        val backwards = forwards.copy(
            slots = mapOf(SlotName("children") to listOf(NodeId("n_btn"), NodeId("n_title"))),
        )

        val forwardsText = Json.encodeToString<Node>(forwards)
        val backwardsText = Json.encodeToString<Node>(backwards)

        assertNotEquals(forwardsText, backwardsText, "two slot orders produced the same bytes")
        assertTrue(
            forwardsText.indexOf("n_title") < forwardsText.indexOf("n_btn"),
            "the slot order is not the list order: '$forwardsText'",
        )
        assertEquals(
            listOf(NodeId("n_title"), NodeId("n_btn")),
            Json.decodeFromString<Node>(forwardsText).slots.getValue(SlotName("children")),
        )
    }

    // -----------------------------------------------------------------------------------
    // Modifiers: a list whose order is the meaning
    // -----------------------------------------------------------------------------------

    @Test
    fun `modifiers are applied in the order they are written`() {
        // `padding` then `background` and `background` then `padding` are different layouts —
        // the first pads inside the background, the second paints it and then pads outside.
        // §7.3 says the runtime folds `node.modifiers` in order through its appliers, so this
        // is the field where "sorted" would be a semantic bug rather than a canonicalization.
        val padding = ModifierEntry(
            type = ModifierType("layout.padding"),
            args = mapOf(PropertyKey("all") to PropertyValue.Const(Value.Dp(8f))),
        )
        val background = ModifierEntry(
            type = ModifierType("layout.background"),
            args = mapOf(
                PropertyKey("color") to PropertyValue.Const(Value.Color(ColorArgb.parse("#FF6200EE"))),
            ),
        )
        val inner = Node(
            id = NodeId("n_root"),
            type = ComponentType("core.Column"),
            modifiers = listOf(padding, background),
        )
        val outer = inner.copy(modifiers = listOf(background, padding))

        val innerText = Json.encodeToString<Node>(inner)
        val outerText = Json.encodeToString<Node>(outer)

        assertNotEquals(innerText, outerText, "two modifier orders produced the same bytes")
        assertTrue(
            innerText.indexOf("layout.padding") < innerText.indexOf("layout.background"),
            "the encoded order is not the list order: '$innerText'",
        )
        assertEquals(
            listOf("layout.padding", "layout.background"),
            Json.decodeFromString<Node>(innerText).modifiers.map { it.type.value },
        )
        assertEquals(
            listOf("layout.background", "layout.padding"),
            Json.decodeFromString<Node>(outerText).modifiers.map { it.type.value },
        )

        // A set would also lose duplicates, and a duplicate modifier is not noise: it is two
        // applications of the same thing, which is how `layout.padding` pads twice.
        val twice = inner.copy(modifiers = listOf(padding, padding))
        assertEquals(2, Json.decodeFromString<Node>(Json.encodeToString<Node>(twice)).modifiers.size)
    }

    @Test
    fun `a modifier with no arguments is one object and not two`() {
        // The exact bytes, for both shapes a modifier takes. `args` defaults to empty and a
        // default is not written, which is why §30.2's example document can say
        // `{ "type": "layout.fillMaxSize" }` and mean it.
        assertEquals(
            """{"type":"layout.fillMaxSize"}""",
            Json.encodeToString<ModifierEntry>(ModifierEntry(ModifierType("layout.fillMaxSize"))),
        )
        assertEquals(
            """{"type":"layout.padding","args":{"all":{"type":"const","value":{"type":"dp","v":8}}}}""",
            Json.encodeToString<ModifierEntry>(
                ModifierEntry(
                    type = ModifierType("layout.padding"),
                    args = mapOf(PropertyKey("all") to PropertyValue.Const(Value.Dp(8f))),
                ),
            ),
        )

        // `type` has no default, because a modifier entry without a modifier is not a modifier
        // entry — there would be nothing to fill in later.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ModifierEntry>("""{"args":{}}""")
        }
    }

    @Test
    fun `a modifier type this engine has never heard of still decodes`() {
        // The same D4 argument as an unknown component, on the same record. §23.3 puts the
        // `ModifierRegistry` out of this module's reach and §7.3's `ModifierApplierRegistry`
        // with it, so an unknown modifier type is preserved and reported rather than refused.
        // A plugin that ships `vendor.animate` gets its work back intact on the round trip.
        val text = """{"type":"vendor.animate","args":{"duration":{"type":"const",""" +
            """"value":{"type":"dp","v":250}}}}"""

        val decoded = Json.decodeFromString<ModifierEntry>(text)

        assertEquals(ModifierType("vendor.animate"), decoded.type)
        assertEquals(
            PropertyValue.Const(Value.Dp(250f)),
            decoded.args.getValue(PropertyKey("duration")),
        )
        assertEquals(text, Json.encodeToString<ModifierEntry>(decoded), "the round trip rewrote it")

        // A `ModifierType` is a registry key and §5.2 requires it to be namespaced, so a
        // modifier with no owner — a bare `padding` — is refused at construction.
        assertFailsWith<IllegalArgumentException> { ModifierType("padding") }
    }

    // -----------------------------------------------------------------------------------
    // Properties: this record is faithful, and sorting is somebody else's job
    // -----------------------------------------------------------------------------------

    @Test
    fun `props are written in the map's own order and this record does not sort them`() {
        // §5.3 annotates this field *sorted by key when written*, and that promise has to be
        // split in two, because the halves live in different places.
        //
        // What this record can deliver — and does, which is what the first assertion pins — is
        // fidelity: the map's iteration order is written, unchanged. The generated serializer
        // walks the entries in the order the map yields them, and this file has nothing that
        // could reorder them.
        //
        // What this record cannot deliver is the sort, and the reason is not a missing
        // annotation: `PropertyKey` is a `@JvmInline value class` over `String` with no natural
        // ordering, so a sorted map of them is not constructible here at all. The canonical
        // order belongs to §18's canonical writer, which is also where `FORMAT_VERSION`'s own
        // KDoc puts "key ordering". A writer that forgets to sort fails where it lives rather
        // than being assumed correct by a record that never promised it.
        //
        // The map below is built in *descending* key order on purpose, so a record that sorted
        // behind the author's back would be caught by a key-order assertion rather than by a
        // reader noticing three months later.
        val node = Node(
            id = NodeId("n_title"),
            type = ComponentType("m3.Text"),
            props = mapOf(
                PropertyKey("width") to PropertyValue.Const(Value.Dp(320f)),
                PropertyKey("text") to PropertyValue.Const(Value.Str("Welcome")),
            ),
        )

        val text = Json.encodeToString<Node>(node)

        assertEquals(
            listOf("id", "type", "props"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
        )
        assertEquals(
            listOf("width", "text"),
            Json.parseToJsonElement(text).at("props").jsonObject.keys.toList(),
            "the property map was reordered on the way out",
        )
        assertEquals(node, Json.decodeFromString<Node>(text), "lost via '$text'")

        // The exact bytes as well, because a key-order assertion is only as good as the parse
        // it came from — and these are the values, spelled the way D1 spells them.
        assertEquals(
            """{"id":"n_title","type":"m3.Text","props":{"width":{"type":"const",""" +
                """"value":{"type":"dp","v":320}},"text":{"type":"const",""" +
                """"value":{"type":"str","v":"Welcome"}}}}""",
            text,
        )

        // Swapping the insertion order swaps the bytes, which is the other half of "nothing
        // here sorts" and the reason a writer that forgets cannot be assumed correct.
        val swapped = node.copy(
            props = mapOf(
                PropertyKey("text") to PropertyValue.Const(Value.Str("Welcome")),
                PropertyKey("width") to PropertyValue.Const(Value.Dp(320f)),
            ),
        )
        assertNotEquals(text, Json.encodeToString<Node>(swapped), "insertion order made no difference")
    }

    @Test
    fun `the canonical key order is reachable and is the opposite of this one`() {
        // The obligation §5.3's comment describes, made executable so the next reader can see
        // what "sorted by key when written" actually asks for: the same node, with its entries
        // put in sorted order first. `sortedBy { it.key.value }` rather than `sortedMapOf`
        // because `PropertyKey` has no natural ordering — which is the same fact that says
        // this record cannot sort for itself.
        val unsorted = mapOf(
            PropertyKey("width") to PropertyValue.Const(Value.Dp(320f)),
            PropertyKey("text") to PropertyValue.Const(Value.Str("Welcome")),
        )
        val canonical = unsorted.entries
            .sortedBy { it.key.value }
            .associate { it.key to it.value }

        val sorted = Json.encodeToString<Node>(
            Node(id = NodeId("n_title"), type = ComponentType("m3.Text"), props = canonical),
        )

        assertEquals(
            listOf("text", "width"),
            Json.parseToJsonElement(sorted).at("props").jsonObject.keys.toList(),
            "the canonical order is not sorted",
        )
        assertEquals(
            """{"id":"n_title","type":"m3.Text","props":{"text":{"type":"const",""" +
                """"value":{"type":"str","v":"Welcome"}},"width":{"type":"const",""" +
                """"value":{"type":"dp","v":320}}}}""",
            sorted,
        )

        // The two encode to two different strings and decode to two *equal* nodes. That is the
        // whole problem in one sentence: the meaning is order-independent and the bytes are
        // not, and only a canonical writer closes the gap between them.
        val fromUnsorted = Json.decodeFromString<Node>(
            Json.encodeToString<Node>(
                Node(id = NodeId("n_title"), type = ComponentType("m3.Text"), props = unsorted),
            ),
        )
        assertEquals(Json.decodeFromString<Node>(sorted), fromUnsorted)
    }

    @Test
    fun `a property map is keyed by a checked name on both sides`() {
        // §23.4's rule, and the reason `NoUntypedStringMapTest` has nothing to find in this
        // record. Both halves are closed domain types: the key validates its own syntax on
        // construction, the value is a two-arm union. A map keyed by a bare string would put a
        // document's properties past the point where the type system can help, and the check
        // that a key *is* a property of this component belongs to `ComponentSpec` in
        // `:engine:schema` — which §23.3 says this module may not depend on.
        assertFailsWith<IllegalArgumentException> { PropertyKey("has space") }
        assertFailsWith<IllegalArgumentException> { PropertyKey("has.dot") }

        // And the value half is refused by the union: an unknown `PropertyValue` tag is a
        // refused document, not a half-read one.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Node>(
                """{"id":"n_1","type":"core.Column","props":{"x":{"type":"bound"}}}""",
            )
        }
    }

    // -----------------------------------------------------------------------------------
    // The nesting that two unions sharing a tag makes worth pinning
    // -----------------------------------------------------------------------------------

    @Test
    fun `a computed property keeps its nesting under a tag another arm also uses`() {
        // `PropertyValue.Computed` holds an `Expr`, and `Expr` has its own `const` variant with
        // the same tag *and* the same field name as `PropertyValue.Const`, so the two produce
        // byte-identical JSON for the same inner value. What keeps them apart is the nesting,
        // and this pins it level by level: the outer `type` says which union is being read, the
        // inner `type` says which `Expr` variant, and the one below that says which `RefTarget`.
        // A decoder that lost a level would hand a constant expression back as a constant
        // property, and a document would render a state read as a literal.
        val computed = Node(
            id = NodeId("n_title"),
            type = ComponentType("m3.Text"),
            props = mapOf(
                PropertyKey("text") to PropertyValue.Computed(
                    Expr.Ref(RefTarget.State(StateId("s_greeting"))),
                ),
            ),
        )

        val text = Json.encodeToString<Node>(computed)
        val parsed = Json.parseToJsonElement(text)

        assertEquals("expr", parsed.text("props", "text", "type"), "the wrapper lost its own tag")
        assertEquals("ref", parsed.text("props", "text", "expr", "type"), "the expression lost its tag")
        assertEquals(
            "state",
            parsed.text("props", "text", "expr", "target", "type"),
            "the reference target lost its tag",
        )
        assertEquals("s_greeting", parsed.text("props", "text", "expr", "target", "id"))

        // Four `type` keys at four depths: the node's own component type, which is an ordinary
        // field rather than a tag, and then three discriminators for three nested polymorphic
        // unions. All four say the same word at four different levels, which is legal and is
        // what §10.1's declarations produce. It is asserted as a count so that it reads as
        // intended rather than as a collision somebody will "fix" one day.
        assertEquals("m3.Text", parsed.text("type"))
        assertEquals(4, Regex("\"type\":").findAll(text).count(), "a discriminator is missing or doubled")

        val value = Json.decodeFromString<Node>(text).props.getValue(PropertyKey("text"))
        val wrapper = assertIs<PropertyValue.Computed>(value, "the computed arm came back as a constant")
        assertEquals(Expr.Ref(RefTarget.State(StateId("s_greeting"))), wrapper.expr)
    }

    @Test
    fun `a constant expression and a constant property share a fragment and not a value`() {
        // The collision, stated as an executable fact rather than as a warning. The same inner
        // JSON appears in both nodes, byte for byte, and the two decode to different things
        // because the level above it differs.
        val constant = Json.encodeToString<Node>(
            Node(
                id = NodeId("n_1"),
                type = ComponentType("m3.Text"),
                props = mapOf(PropertyKey("text") to PropertyValue.Const(Value.Int32(1))),
            ),
        )
        val constantExpression = Json.encodeToString<Node>(
            Node(
                id = NodeId("n_1"),
                type = ComponentType("m3.Text"),
                props = mapOf(
                    PropertyKey("text") to PropertyValue.Computed(Expr.Const(Value.Int32(1))),
                ),
            ),
        )

        val shared = "\"type\":\"const\",\"value\":{\"type\":\"i32\",\"v\":1}"
        assertTrue(shared in constant, "the constant arm lost its fragment: '$constant'")
        assertTrue(shared in constantExpression, "the nested expression lost its fragment")
        assertNotEquals(constant, constantExpression, "the two nestings produced the same bytes")

        // The outer level is the only thing that tells them apart, so it is read structurally
        // rather than by searching for a substring.
        assertEquals("const", Json.parseToJsonElement(constant).text("props", "text", "type"))
        assertEquals("expr", Json.parseToJsonElement(constantExpression).text("props", "text", "type"))
        assertEquals("const", Json.parseToJsonElement(constantExpression).text("props", "text", "expr", "type"))

        assertEquals(
            PropertyValue.Const(Value.Int32(1)),
            Json.decodeFromString<Node>(constant).props.getValue(PropertyKey("text")),
        )
        assertEquals(
            PropertyValue.Computed(Expr.Const(Value.Int32(1))),
            Json.decodeFromString<Node>(constantExpression).props.getValue(PropertyKey("text")),
            "a constant expression decoded as a constant property",
        )
    }

    // -----------------------------------------------------------------------------------
    // Decoding consults nothing (D4), and the limit that keeps D4 honest
    // -----------------------------------------------------------------------------------

    @Test
    fun `a node written against a component this engine has never heard of still decodes`() {
        // D4, on the record D4 needs most. §23.3 records `:engine:model` as depending on no
        // other module, so the `ComponentRegistry` is not visible here and could not be
        // consulted if it were — and the fragment below names a component, a modifier and an
        // action that no engine in this repository has ever heard of. It decodes, it round-trips
        // byte for byte, and validation reports the unknown names as diagnostics afterwards. An
        // older engine that dropped the parts it does not understand would destroy the newer
        // engine's work, which is the one outcome D4 exists to prevent.
        val text = "{\"id\":\"n_chart\",\"type\":\"vendor.Chart\"," +
            "\"props\":{\"series\":{\"type\":\"const\",\"value\":{\"type\":\"i32\",\"v\":7}}}," +
            "\"modifiers\":[{\"type\":\"vendor.animate\"}]," +
            "\"events\":{\"onSelect\":{\"steps\":[{\"action\":\"vendor.select\"}]}}}"

        val decoded = Json.decodeFromString<Node>(text)

        assertEquals(NodeId("n_chart"), decoded.id)
        assertEquals(ComponentType("vendor.Chart"), decoded.type)
        assertEquals(
            PropertyValue.Const(Value.Int32(7)),
            decoded.props.getValue(PropertyKey("series")),
        )
        assertEquals(ModifierType("vendor.animate"), decoded.modifiers.single().type)
        assertEquals(
            "vendor.select",
            decoded.events.getValue(EventKey("onSelect")).steps.single().action.value,
        )

        assertEquals(text, Json.encodeToString<Node>(decoded), "the round trip rewrote the document")

        // The component type is a checked *namespaced* string and nothing more, which is why an
        // unknown one is cheap to keep: the check is on the dot, not on a registry.
        assertFailsWith<IllegalArgumentException> { ComponentType("Chart") }
    }

    @Test
    fun `an unknown model field is an error and not something to keep`() {
        // The other half of D4, and the limit that stops it being a data-loss bug. PLAN's
        // decoder configuration sets `ignoreUnknownKeys = false`, and §22 says the same in
        // words: unknown *component types, properties, modifiers and actions* are preserved,
        // while unknown envelope or model *fields* are errors unless a tool opts into leniency.
        // A decoder that quietly dropped fields it did not recognise would throw away exactly
        // the data the test above is about, so the boundary is asserted rather than assumed.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Node>(
                """{"id":"n_1","type":"core.Column","layout":{"align":"top"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Node>("""{"id":"n_1","type":"core.Column","slotz":{}}""")
        }
    }

    // -----------------------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------------------

    @Test
    fun `events are keyed by a checked name and carry a data handler`() {
        // §5.1's `EventKey → ActionSequence`, on the wire under the names §5.3 gives them, read
        // from a hand-written fragment rather than produced by the encoder — because the claim
        // is about *reading* a document somebody else wrote.
        val text = """{"id":"n_btn","type":"m3.Button","events":{"onClick":""" +
            """{"steps":[{"action":"nav.navigate"}]}}}"""

        val decoded = Json.decodeFromString<Node>(text)

        assertEquals(
            listOf("id", "type", "events"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "the node's field names moved on the wire",
        )
        assertEquals(EventKey("onClick"), decoded.events.keys.single())
        assertEquals(
            "nav.navigate",
            decoded.events.getValue(EventKey("onClick")).steps.single().action.value,
            "the handler did not survive",
        )
        assertEquals(text, Json.encodeToString<Node>(decoded), "the round trip rewrote the document")

        // `EventKey` is one of §5.2's simple identifiers, so it validates its own syntax — and
        // an event this engine does not know decodes all the same, which is the D4 property for
        // the one part of a document a plugin extends most freely.
        assertFailsWith<IllegalArgumentException> { EventKey("on click") }
        assertEquals(
            "onValueChange",
            Json.decodeFromString<Node>(
                """{"id":"n_1","type":"core.Column","events":{"onValueChange":{"steps":[]}}}""",
            ).events.keys.single().value,
        )
    }

    // -----------------------------------------------------------------------------------
    // The record as a value, which is what a document diff compares
    // -----------------------------------------------------------------------------------

    @Test
    fun `two equal nodes are equal and a document diff can tell them apart`() {
        // §6.2's "structural equality: `data class` equality", which is what
        // `DocumentDiff.compute(old, new)` (§26) compares and what ADR-001's diff-friendly
        // normalization is for.
        val left = Node(id = NodeId("n_1"), type = ComponentType("core.Column"), name = "root")

        assertEquals(left, left.copy(), "two equal nodes did not compare equal")
        assertEquals(left.hashCode(), left.copy().hashCode(), "equal nodes have different hash codes")
        assertNotEquals(left, left.copy(name = "other"), "two different nodes compared equal")

        // Two property maps with the same entries written in different orders are the *same*
        // node — map equality is order-independent. That is exactly why a canonical writer is
        // needed before they are the same *bytes*, and it is why the `props` test above and
        // this one together describe the whole situation.
        val one = PropertyValue.Const(Value.Bool(true))
        val other = PropertyValue.Const(Value.Bool(false))
        assertEquals(
            Node(
                id = NodeId("n_1"),
                type = ComponentType("core.Column"),
                props = mapOf(PropertyKey("a") to one, PropertyKey("b") to other),
            ),
            Node(
                id = NodeId("n_1"),
                type = ComponentType("core.Column"),
                props = mapOf(PropertyKey("b") to other, PropertyKey("a") to one),
            ),
            "two property maps with the same entries did not compare equal",
        )

        // And the id is part of the identity: two nodes with the same type, name and props are
        // still two nodes, which is what makes `Map<NodeId, Node>` a table of distinct entries
        // rather than a bag.
        assertNotEquals(
            Node(id = NodeId("n_1"), type = ComponentType("core.Column")),
            Node(id = NodeId("n_2"), type = ComponentType("core.Column")),
            "two nodes with different ids compared equal",
        )
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    /**
     * Walks down to the element at [path] and reads it as a string.
     *
     * These tests are about *structure* — which key holds what, at which depth — and a chain of
     * `getValue` calls long enough to walk three nested unions hides the very shape it is
     * asserting. Reading back out of parsed JSON rather than off a serializer descriptor is also
     * the point: the descriptor would be the same declaration the test is supposed to be
     * checking.
     */
    private fun JsonElement.text(vararg path: String): String = at(*path).jsonPrimitive.content

    /** The element at [path], from the root object downwards. */
    private fun JsonElement.at(vararg path: String): JsonElement =
        path.fold(this) { element, key -> element.jsonObject.getValue(key) }
}
