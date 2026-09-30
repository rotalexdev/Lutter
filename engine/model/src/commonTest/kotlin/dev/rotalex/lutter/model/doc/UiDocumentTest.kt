package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `UiDocument` contract: the root record, with the two fields the amendment added to it.
 *
 * **A document with nothing optional declared is five fields of JSON.** Nine of the fourteen
 * have defaults and §6.2's canonical writer encodes no defaults, so the smallest document a
 * person writes is `meta`, `app`, `pages`, `components` and `nodes` and nothing else. That
 * deserves an assertion in its own right: a field that acquired a value silently — a written
 * out `NodeTable.EMPTY`, a written out empty enum map — would turn every document in every
 * repository into a diff, and §18.2 freezes defaults precisely so it cannot.
 *
 * The two additive fields are asserted separately because they are the amendment's: `enums` is
 * the second half of one value space with `dataModels`, and `theme` answers which of `themes`
 * is selected. Both are nullable-or-empty with a default, which is what makes them additive
 * rather than a change to an existing declaration's type — the alternative for `theme` would
 * break every stored document.
 */
class UiDocumentTest {

    private val label = Node(
        id = NodeId("n_label"),
        type = ComponentType("m3.Text"),
        props = mapOf(PropertyKey("text") to PropertyValue.Const(Value.Str("Welcome"))),
    )

    private val root = Node(
        id = NodeId("n_root"),
        type = ComponentType("core.Column"),
        slots = mapOf(SlotName("children") to listOf(NodeId("n_label"))),
    )

    private val nodes = NodeTable.EMPTY.with(root).with(label)

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root")),
        ),
        components = emptyMap(),
        nodes = nodes,
    )

    @Test
    fun `a minimal document writes only its five required fields`() {
        val text = Json.encodeToString<UiDocument>(document)

        assertEquals(
            """{"meta":{"name":"Demo"},"app":{"packageName":"com.example.demo",""" +
                """"startPage":"p_home"},"pages":{"p_home":{"id":"p_home","name":"HomeScreen",""" +
                """"route":"/home","root":"n_root"}},"components":{},"nodes":{""" +
                """"n_label":{"id":"n_label","type":"m3.Text","props":{"text":""" +
                """{"type":"const","value":{"type":"str","v":"Welcome"}}}},""" +
                """"n_root":{"id":"n_root","type":"core.Column","slots":{"children":["n_label"]}}}}""",
            text,
        )
        assertEquals(
            listOf("meta", "app", "pages", "components", "nodes"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "a default leaked onto the wire, or a field moved",
        )
        assertEquals(document, Json.decodeFromString<UiDocument>(text))
    }

    @Test
    fun `the node table is written into the document, sorted, with its id in the body`() {
        val nodes = Json.parseToJsonElement(Json.encodeToString<UiDocument>(document))
            .jsonObject.getValue("nodes").jsonObject

        // §18.2's "nodes sorted by NodeId" is the whole reason `NodeTable.ids()` is a contract
        // rather than `map.keys`, and an unrelated edit has to leave every other node's line
        // alone for Git to merge it.
        assertEquals(listOf("n_label", "n_root"), nodes.keys.toList())
        assertEquals("n_root", nodes.getValue("n_root").jsonObject.getValue("id").jsonPrimitive.content)

        // And the table is a first-class field with no default: a document cannot be built
        // without one, which is the reason `NodeTable.EMPTY` exists.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<UiDocument>(
                """{"meta":{"name":"D"},"app":{"packageName":"c","startPage":"p"},""" +
                    """"pages":{},"components":{}}""",
            )
        }
    }

    @Test
    fun `enums and dataModels are two halves of one value space`() {
        // §5.5:366 — `DataModelId` and `TypeId` are the same value class over the same id
        // syntax, and §9.1 references an enum type and an object type through the same `TypeId`.
        // So a key present in both maps is the duplicate the structural pass reports, and the
        // model adds no alias field and does not retype `dataModels` to fix a distinction
        // nothing can observe.
        val full = document.copy(
            dataModels = mapOf(
                DataModelId("User") to DataModelDecl(
                    id = DataModelId("User"),
                    name = "User",
                    fields = listOf(FieldDecl(PropertyKey("name"), TypeRef.Str)),
                ),
            ),
            enums = mapOf(
                TypeId("Role") to EnumTypeDecl(
                    id = TypeId("Role"),
                    name = "Role",
                    entries = listOf(EnumEntryDecl("Admin")),
                ),
            ),
        )

        val text = Json.encodeToString<UiDocument>(full)
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals("User", root.getValue("dataModels").jsonObject.keys.single())
        assertEquals("Role", root.getValue("enums").jsonObject.keys.single())
        assertEquals(
            """{"id":"User","name":"User","fields":[{"name":"name","type":{"type":"str"}}]}""",
            Json.encodeToString<DataModelDecl>(full.dataModels.getValue(DataModelId("User"))),
        )
        assertEquals(
            """{"id":"Role","name":"Role","entries":[{"name":"Admin"}]}""",
            Json.encodeToString<EnumTypeDecl>(full.enums.getValue(TypeId("Role"))),
        )
        assertEquals(full, Json.decodeFromString<UiDocument>(text))
    }

    @Test
    fun `theme selects one of the themes and null is a rule rather than a gap`() {
        val base = ThemeDecl(ThemeId("t_base"), "Base")
        val dark = ThemeDecl(ThemeId("t_dark"), "Dark")
        val both = document.copy(
            themes = mapOf(ThemeId("t_base") to base, ThemeId("t_dark") to dark),
        )

        // Nothing selected is the shape §30.2's excerpt implies, and §14.2 resolves it to "the
        // only theme" when there is exactly one. An omitted field and an explicit `null` are
        // the same document, which is what makes the field additive.
        assertNull(document.theme)
        assertEquals(document, Json.decodeFromString<UiDocument>(Json.encodeToString(document)))
        assertNull(
            Json.decodeFromString<UiDocument>(
                """{"meta":{"name":"Demo"},"app":{"packageName":"com.example.demo",""" +
                    """"startPage":"p_home"},"pages":{},"components":{},"nodes":{},"theme":null}""",
            ).theme,
        )

        val selected = both.copy(theme = ThemeId("t_dark"))
        val text = Json.encodeToString<UiDocument>(selected)

        assertEquals("t_dark", Json.parseToJsonElement(text).jsonObject.getValue("theme").jsonPrimitive.content)
        assertEquals(selected, Json.decodeFromString<UiDocument>(text))
        assertEquals(ThemeId("t_dark"), Json.decodeFromString<UiDocument>(text).theme)

        // An id absent from `themes` decodes. §14.2's second row resolves it to nothing and
        // every `Value.Token` becomes `token.unknown` — a diagnostic, not a document that will
        // not open — and dangling references are §17's to report.
        val dangling = both.copy(theme = ThemeId("t_absent"))
        assertEquals(dangling, Json.decodeFromString<UiDocument>(Json.encodeToString(dangling)))
    }

    @Test
    fun `app state, host functions and resources round trip alongside everything else`() {
        val full = document.copy(
            appState = listOf(StateDecl(StateId("s_theme"), "theme", TypeRef.Str)),
            hostFunctions = listOf(
                HostFunctionDecl(name = "host.toast", params = emptyList(), returns = null),
            ),
            resources = mapOf(
                ResourceId("r_submit") to ResourceDecl(
                    id = ResourceId("r_submit"),
                    name = "Submit",
                    kind = ResourceKind.String,
                    variants = listOf(ResourceVariant(emptySet(), ResourceSource.Text("Submit"))),
                ),
            ),
        )

        val text = Json.encodeToString<UiDocument>(full)

        assertEquals(
            """{"id":"s_theme","name":"theme","type":{"type":"str"}}""",
            Json.encodeToString<StateDecl>(full.appState.single()),
        )
        assertEquals(
            """{"name":"host.toast","params":[],"returns":null}""",
            Json.encodeToString<HostFunctionDecl>(full.hostFunctions.single()),
        )
        assertEquals(full, Json.decodeFromString<UiDocument>(text))
        assertEquals(
            """{"id":"r_submit","name":"Submit","kind":"string","variants":[""" +
                """{"qualifiers":[],"source":{"type":"text","value":"Submit"}}]}""",
            Json.encodeToString<ResourceDecl>(full.resources.getValue(ResourceId("r_submit"))),
        )

        // `HostFunctionDecl.returns` has no default, so a function that returns nothing writes
        // an explicit `null` rather than being read as one that does. §11.6 takes that position
        // on purpose and the negative case is what pins it.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<HostFunctionDecl>("""{"name":"host.toast","params":[]}""")
        }
    }

    @Test
    fun `a document is a value, and the node table's equality is what makes it one`() {
        val one = document
        val other = document.copy(app = document.app.copy(startPage = PageId("p_other")))

        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertNotEquals(one, other, "two documents with different start pages compared equal")

        // Rebuilt from the nodes in the opposite order. A document is comparable by value only
        // because `nodes` is, and a `NodeTable` built by hand would be a second document with
        // the same meaning.
        val rebuilt = one.copy(
            nodes = NodeTable.EMPTY.with(label).with(root),
        )
        assertEquals(one, rebuilt, "the node table's equality is not doing its job")
        assertEquals(Json.encodeToString<UiDocument>(one), Json.encodeToString<UiDocument>(rebuilt))
    }
}
