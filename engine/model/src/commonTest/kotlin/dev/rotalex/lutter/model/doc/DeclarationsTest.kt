package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The document's own declarations: `Page`, `ComponentDecl`, `SlotDecl`, `DataModelDecl`,
 * `FieldDecl`, `EnumTypeDecl`, `EnumEntryDecl`, `DocumentMeta`, `PluginRequirement`, `AppSpec`
 * and `NavigationSpec`.
 *
 * They are grouped because what they share is more interesting than what separates them: each
 * one has exactly the fields its section declares, every default is absent from the wire, and
 * every name that will become a Kotlin symbol is a `String` this module deliberately does *not*
 * validate — a page's composable name, a data model's class name and a slot's name are all
 * checked by §17, because refusing at construction would turn a document that ought to load
 * into one that does not.
 *
 * `NavigationSpec` is the outlier and its emptiness is the test: a public field with no fields
 * is what "can gain a field later, whereas removing it is a break" looks like in code.
 */
class DeclarationsTest {

    // -----------------------------------------------------------------------------------
    // Page
    // -----------------------------------------------------------------------------------

    private val page = Page(
        id = PageId("p_home"),
        name = "HomeScreen",
        route = "/home",
        params = listOf(ParamDecl(ParamName("userId"), TypeRef.Str)),
        root = NodeId("n_root"),
    )

    @Test
    fun `a page is an id, a name, a route, its params, its state and its root`() {
        val text = Json.encodeToString<Page>(page)

        assertEquals(
            """{"id":"p_home","name":"HomeScreen","route":"/home",""" +
                """"params":[{"name":"userId","type":{"type":"str"}}],"root":"n_root"}""",
            text,
        )
        assertEquals(page, Json.decodeFromString<Page>(text))
        assertEquals(
            listOf("id", "name", "route", "params", "root"),
            Json.parseToJsonElement(text).jsonObject.keys.toList(),
            "a default leaked onto the wire, or a field moved",
        )

        // `params` and `state` both default and neither is written, which is what makes the
        // common screen a four-field fragment a person can read in a diff.
        assertEquals(
            """{"id":"p_home","name":"HomeScreen","route":"/home","root":"n_root"}""",
            Json.encodeToString<Page>(page.copy(params = emptyList())),
        )

        // `root` is a `NodeId` and not a `Node`. §5.6's whole trade is that a child is a
        // reference, and a page that held its tree would be the rejected nested option with one
        // less layer of nesting — so the bare string on the wire is the whole of the claim.
        assertEquals("n_root", Json.parseToJsonElement(text).jsonObject.getValue("root").jsonPrimitive.content)
    }

    // -----------------------------------------------------------------------------------
    // ComponentDecl and SlotDecl
    // -----------------------------------------------------------------------------------

    @Test
    fun `a component declaration is params, slots, state and a root`() {
        val component = ComponentDecl(
            id = ComponentDeclId("c_card"),
            name = "Card",
            params = listOf(ParamDecl(ParamName("title"), TypeRef.Str)),
            slots = listOf(SlotDecl(SlotName("content"))),
            root = NodeId("n_card_root"),
        )

        val text = Json.encodeToString<ComponentDecl>(component)

        assertEquals(
            """{"id":"c_card","name":"Card","params":[{"name":"title","type":{"type":"str"}}],""" +
                """"slots":[{"name":"content"}],"root":"n_card_root"}""",
            text,
        )
        assertEquals(component, Json.decodeFromString<ComponentDecl>(text))

        // `params` and `slots` have **no default**, so a component with neither still has to say
        // so. §11.6's `HostFunctionDecl` takes the same position on its return type: an
        // unstated fact is not a fact.
        assertEquals(
            """{"id":"c_bare","name":"Bare","params":[],"slots":[],"root":"n_1"}""",
            Json.encodeToString<ComponentDecl>(
                ComponentDecl(
                    ComponentDeclId("c_bare"),
                    "Bare",
                    emptyList(),
                    emptyList(),
                    root = NodeId("n_1"),
                ),
            ),
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ComponentDecl>("""{"id":"c","name":"C","root":"n_1"}""")
        }
    }

    @Test
    fun `a slot declaration carries a name and nothing else`() {
        // The four fields it does not have — `cardinality`, `accepts`, `provides`, `iteration`
        // — are `SlotSpec` fields in `:engine:schema`, which §23.3 puts out of this module's
        // reach, and §5.5:465 is what makes that a design: the engine synthesizes a
        // `ComponentSpec` for each `ComponentDecl`, and the synthesis is where a document slot
        // acquires them. The negative cases pin the absence, because a field added here would
        // compile, round-trip and break §23.3 with nothing in this module saying so.
        assertEquals("""{"name":"content"}""", Json.encodeToString<SlotDecl>(SlotDecl(SlotName("content"))))
        assertEquals(SlotDecl(SlotName("content")), Json.decodeFromString<SlotDecl>("""{"name":"content"}"""))
        assertEquals(
            listOf("name"),
            Json.parseToJsonElement("""{"name":"content"}""").jsonObject.keys.toList(),
        )
        for (extra in listOf("cardinality", "accepts", "provides", "iteration")) {
            assertFailsWith<SerializationException>("SlotDecl gained a '$extra' field") {
                Json.decodeFromString<SlotDecl>("""{"name":"content","$extra":"many"}""")
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // The document's own types
    // -----------------------------------------------------------------------------------

    @Test
    fun `a data model and a field are the shape the generator emits`() {
        // §16.6:1307 emits `data class User(val name: String, val age: Int)`, so the generator's
        // output *is* the specification of the record — there is no design space here. `Nullable`
        // and `ListOf` are the same row, spelled on the `TypeRef`.
        val model = DataModelDecl(
            id = DataModelId("User"),
            name = "User",
            fields = listOf(
                FieldDecl(PropertyKey("name"), TypeRef.Str),
                FieldDecl(PropertyKey("age"), TypeRef.Nullable(TypeRef.Int32)),
                FieldDecl(PropertyKey("tags"), TypeRef.ListOf(TypeRef.Str)),
            ),
        )

        val text = Json.encodeToString<DataModelDecl>(model)

        assertEquals(
            """{"id":"User","name":"User","fields":[{"name":"name","type":{"type":"str"}},""" +
                """{"name":"age","type":{"type":"nullable","inner":{"type":"i32"}}},""" +
                """{"name":"tags","type":{"type":"list","element":{"type":"str"}}}]}""",
            text,
        )
        assertEquals(model, Json.decodeFromString<DataModelDecl>(text))

        // There is no optionality flag, because `TypeRef.Nullable` already is that and a second
        // one would be a second source of truth for the same fact.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<DataModelDecl>(
                """{"id":"U","name":"U","fields":[{"name":"a","type":{"type":"str"},"optional":true}]}""",
            )
        }
        // And the field name is a checked identifier, so a key no generated class could carry is
        // refused at the id rather than at analysis.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<DataModelDecl>(
                """{"id":"U","name":"U","fields":[{"name":"has space","type":{"type":"str"}}]}""",
            )
        }
    }

    @Test
    fun `an enum declaration and its entries carry names and no values`() {
        // `Value.Enum(entry: String)` is the whole value a document can write, so an entry that
        // carried a payload would have nowhere to put it. §16.6:1301's Kotlin symbol belongs to
        // the schema's `EnumTypeSpec`, not here.
        val enum = EnumTypeDecl(
            id = TypeId("Role"),
            name = "Role",
            entries = listOf(EnumEntryDecl("Admin"), EnumEntryDecl("Guest")),
        )

        val text = Json.encodeToString<EnumTypeDecl>(enum)

        assertEquals(
            """{"id":"Role","name":"Role","entries":[{"name":"Admin"},{"name":"Guest"}]}""",
            text,
        )
        assertEquals(enum, Json.decodeFromString<EnumTypeDecl>(text))
        assertEquals("""{"name":"Admin"}""", Json.encodeToString<EnumEntryDecl>(EnumEntryDecl("Admin")))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<EnumEntryDecl>("""{"name":"Admin","value":1}""")
        }

        // `DataModelId` and `TypeId` are one value space over the same id syntax, so a key
        // present in both `dataModels` and `enums` is the duplicate §5.5:366 says the structural
        // pass treats it as. Nothing in the model can tell the two key types apart, which is
        // exactly why `dataModels` is not retyped.
        assertEquals(DataModelId("Role").value, TypeId("Role").value)
    }

    // -----------------------------------------------------------------------------------
    // The meta and app records
    // -----------------------------------------------------------------------------------

    @Test
    fun `the meta block is a name, plugins and component versions`() {
        val meta = DocumentMeta(
            name = "Demo",
            plugins = listOf(PluginRequirement(PluginId("forge.material3"), "1.2.0")),
            componentVersions = mapOf(ComponentType("m3.Text") to 3),
        )

        val text = Json.encodeToString<DocumentMeta>(meta)

        assertEquals(
            """{"name":"Demo","plugins":[{"id":"forge.material3","version":"1.2.0"}],""" +
                """"componentVersions":{"m3.Text":3}}""",
            text,
        )
        assertEquals(meta, Json.decodeFromString<DocumentMeta>(text))
        assertEquals("""{"name":"Demo"}""", Json.encodeToString<DocumentMeta>(DocumentMeta("Demo")))

        // The version is a bare `String` and not a semver the model validates: no section
        // specifies a grammar, and §27.2 names the outcome (`plugin.version_mismatch`) without
        // the comparison, so the rule belongs to the loader.
        assertEquals(
            """{"id":"forge.x","version":"not-a-version"}""",
            Json.encodeToString<PluginRequirement>(PluginRequirement(PluginId("forge.x"), "not-a-version")),
        )
        // A `ComponentType` key is checked as a namespaced id, so a bare name is refused.
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<DocumentMeta>("""{"name":"D","componentVersions":{"Text":3}}""")
        }
    }

    @Test
    fun `an app spec is a package, a start page and an empty navigation`() {
        val app = AppSpec("com.example.demo", PageId("p_home"))

        val text = Json.encodeToString<AppSpec>(app)

        assertEquals("""{"packageName":"com.example.demo","startPage":"p_home"}""", text)
        assertEquals(app, Json.decodeFromString<AppSpec>(text))

        // `NavigationSpec` has no fields, which is the test. The record exists because the
        // field has a default and is public API, so an empty record is a field that can gain a
        // field later whereas removing it is a break.
        assertEquals("{}", Json.encodeToString<NavigationSpec>(NavigationSpec()))
        assertEquals(NavigationSpec(), Json.decodeFromString<NavigationSpec>("{}"))
        assertEquals(app, AppSpec("com.example.demo", PageId("p_home"), NavigationSpec()))

        // A `kind` naming a navigation library is the decision `CodegenOptions.navigation`
        // already holds as a `NavigationStrategy` (§16.8), and §4.5's single-resolver rule
        // exists to stop it being taken twice. The negative case pins the absence.
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NavigationSpec>("""{"kind":"navigation3"}""")
        }
    }

    // -----------------------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------------------

    @Test
    fun `every declaration is a value, and a document diff can tell two apart`() {
        val one = Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_root"))
        val other = one.copy(route = "/start")

        assertEquals(one, one.copy())
        assertEquals(one.hashCode(), one.copy().hashCode())
        assertNotEquals(one, other, "two pages with different routes compared equal")
        assertNotEquals(
            Json.encodeToString<Page>(one),
            Json.encodeToString<Page>(other),
            "two different pages produced the same bytes",
        )

        // A renamed page is a new value and the same document: §6.2 says a rename changes
        // `name` and not `id`, so a diff shows the rename and nothing else follows from it.
        val renamed = one.copy(name = "MainScreen")
        assertEquals(PageId("p_home"), renamed.id)
        assertEquals(emptyList(), renamed.state)
    }
}
