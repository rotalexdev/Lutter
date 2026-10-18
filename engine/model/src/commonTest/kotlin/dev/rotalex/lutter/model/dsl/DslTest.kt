package dev.rotalex.lutter.model.dsl

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.ColorRole
import dev.rotalex.lutter.model.doc.ColorSpec
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.model.doc.Qualifier
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.doc.ResourceSource
import dev.rotalex.lutter.model.doc.ShapeRole
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.TextRole
import dev.rotalex.lutter.model.doc.TokenName
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.DataModelId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.ThemeId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.index.NodeOwner
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.ColorArgb
import dev.rotalex.lutter.model.value.Value
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The builder DSL contract: what a built document guarantees, and what it refuses.
 *
 * Two agreements are asserted because the DSL owns both ends of them: the table holds
 * every root the pages and components name, and every slot id names a node the table
 * holds. Encodes pin their base type — `encodeToString<UiDocument>` — because the
 * inferred concrete serializer would drop the union discriminator.
 *
 * The rows past the node table are about reachability, which is what PLAN §36.1's first
 * bullet asks for and what the DSL could not offer until every aggregate had a call. The
 * twelve-field row is the one that would have failed before: a document carrying an app
 * state, a data model, an enum type, a host function, a theme or a resource could not be
 * written with the builder at all, and no round trip can prove a field that has no way in.
 *
 * Every refusal below asserts its message, not just its type. A guardrail that throws
 * without saying why is a guardrail its caller cannot act on — which is why the refusals
 * name *which* map collided rather than only that something did.
 */
class DslTest {

    private fun demo(): UiDocument = buildDocument("Demo", SequentialIdGenerator("n_")) {
        page("Home", route = "/home") {
            node("core.Column") {
                slot(
                    "children",
                    listOf(
                        node("m3.Text") { prop("text", Value.Str("Welcome")) },
                        node("m3.Button") {
                            slot("content", listOf(node("m3.Text") { prop("text", Value.Str("Go")) }))
                            event("onClick", ActionSequence(listOf(ActionStep(ActionId("nav.navigate")))))
                        },
                    ),
                )
                modifier("layout.fillMaxSize")
            }
        }
        component(ComponentDeclId("Card"), "Card") {
            node("core.Column") {
                slot("children", listOf(node("m3.Text") { computed("text", Expr.Const(Value.Int32(1))) }))
            }
        }
    }

    /**
     * A document carrying one declaration of everything `UiDocument` declares past its nodes.
     *
     * The typed overloads rather than the string ones, because `DslEqualsJsonTest` in
     * `:engine:serialization` writes the string ones against a hand-written envelope and a
     * declaration only both sides reach is one neither test would ever compile.
     */
    private fun complete(): UiDocument = buildDocument("Complete", theme = ThemeId("t_brand")) {
        appState(
            listOf(
                StateDecl(
                    id = StateId("s_session"),
                    name = "session",
                    type = TypeRef.Str,
                    initial = Value.Str("anon"),
                    persistence = Persistence.Saveable,
                ),
            ),
        )
        dataModel(DataModelId("d_account"), "Account") {
            field(PropertyKey("id"), TypeRef.Str)
            field("age", TypeRef.Nullable(TypeRef.Int32))
        }
        enumType(TypeId("Role"), "Role") {
            entry("Admin")
            entry("Guest")
        }
        hostFunction("fetchAccount", TypeRef.Object(TypeId("d_account"))) {
            param(ParamName("accountId"), TypeRef.Str)
        }
        theme(ThemeId("t_brand"), "Brand") {
            color(ColorRole("primary"), ColorSpec(light = ColorArgb.parse("#FF6200EE")))
            typography(TextRole("body"), mapOf(PropertyKey("fontSize") to Value.Sp(14f)))
            shape(ShapeRole("small"), mapOf(PropertyKey("radius") to Value.Dp(4f)))
            dimension(TokenName("md.spacing.medium"), Value.Dp(16f))
            custom(TokenName("brand.accent"), Value.Color(ColorArgb.parse("#FFFFBB33")))
        }
        resource(ResourceId("r_greeting"), "Greeting", ResourceKind.String) {
            variant(setOf(Qualifier.Locale("en")), ResourceSource.Text("Hello"))
        }
        page("Home", route = "/home") {
            node("core.Column")
        }
    }

    /**
     * The message from repeating [again] inside a theme that already declares all five maps.
     *
     * One body and five callers, because the five refusals are one rule spelled five ways and a
     * row that proved only the first would leave four of them untested — including the two
     * whose keys are dotted names rather than roles, which is where a copy-paste would slip.
     */
    private fun refuseSecondRole(again: ThemeScope.() -> Unit): String {
        val failure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupRole") {
                page("Home", route = "/home") { node("core.Column") }
                theme("t_x", "X") {
                    color("primary", ColorArgb.parse("#FF6200EE"))
                    typography("body", mapOf(PropertyKey("fontSize") to Value.Sp(14f)))
                    shape("small", mapOf(PropertyKey("radius") to Value.Dp(4f)))
                    dimension("md.spacing.medium", Value.Dp(8f))
                    custom("brand.accent", Value.Str("x"))
                    again(this)
                }
            }
        }
        return failure.message!!
    }

    @Test
    fun `a built document round-trips with the base type pinned`() {
        val document = demo()

        val text = Json.encodeToString<UiDocument>(document)

        assertTrue(text.contains(""""type":"const""""), "a prop lost its union tag: $text")
        assertEquals(document, Json.decodeFromString<UiDocument>(text))
    }

    @Test
    fun `the table the page roots and the component roots agree`() {
        val document = demo()
        val index = DocumentIndex(document)

        val pageRoot = document.pages.getValue(PageId("Home")).root
        assertTrue(document.nodes.contains(pageRoot), "page root '$pageRoot' is missing from the table")
        assertEquals(NodeOwner.Page(PageId("Home")), index.ownerOf(pageRoot))

        val componentRoot = document.components.getValue(ComponentDeclId("Card")).root
        assertTrue(document.nodes.contains(componentRoot), "component root is missing from the table")
        assertEquals(NodeOwner.Component(ComponentDeclId("Card")), index.ownerOf(componentRoot))

        val button = document.nodes.require(NodeId("n_3"))
        val label = button.slots.getValue(SlotName("content")).single()
        assertTrue(document.nodes.contains(label), "slot id '$label' names no node")
        assertEquals(NodeId("n_3"), index.parentOf(label)?.parentId)
    }

    @Test
    fun `nodes without an id are counted`() {
        val document = demo()

        assertTrue(document.nodes.contains(NodeId("n_1")), "expected a minted n_1")
        assertTrue(document.nodes.contains(NodeId("n_2")), "expected a minted n_2")
        assertEquals(document.nodes, NodeTable.EMPTY.let {
            var table = it
            for (id in document.nodes.ids()) table = table.with(document.nodes.require(id))
            table
        })
    }

    @Test
    fun `a page with no nodes has no root to point at`() {
        val failure = assertFailsWith<IllegalStateException> {
            buildDocument("Empty") {
                page("Home", route = "/home") {
                }
            }
        }

        assertTrue(failure.message!!.contains("no root"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `two top-level nodes with no pin are refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            buildDocument("Two") {
                page("Home", route = "/home") {
                    node("core.Column")
                    node("core.Column")
                }
            }
        }

        assertTrue(failure.message!!.contains("no root"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `an explicit pin resolves two top-level nodes`() {
        val document = buildDocument("Pinned") {
            page("Home", route = "/home") {
                val first = node("core.Column")
                node("core.Column")
                root(first)
            }
        }

        assertEquals(NodeId("n_1"), document.pages.getValue(PageId("Home")).root)
    }

    @Test
    fun `a duplicate node id is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            buildDocument("Dup") {
                page("Home", route = "/home") {
                    node("core.Column", id = NodeId("n_same"))
                    node("core.Column", id = NodeId("n_same"))
                }
            }
        }

        assertTrue(failure.message!!.contains("duplicate"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `an ill-formed type is refused by the record`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            buildDocument("Bad") {
                page("Home", route = "/home") {
                    node("Column")
                }
            }
        }

        assertTrue(
            failure.message!!.contains("Invalid ComponentType"),
            "it did not say why: ${failure.message}",
        )
    }

    @Test
    fun `a document with no pages cannot name a start page`() {
        val failure = assertFailsWith<IllegalStateException> {
            buildDocument("Nowhere") {
            }
        }

        assertTrue(failure.message!!.contains("no pages"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `a dangling start page decodes because references belong to analysis`() {
        val document = buildDocument("Dangling", startPage = PageId("p_absent")) {
            page("Home", route = "/home") {
                node("core.Column")
            }
        }

        assertEquals(PageId("p_absent"), document.app.startPage)
        assertEquals(document, Json.decodeFromString<UiDocument>(Json.encodeToString<UiDocument>(document)))
    }

    @Test
    fun `duplicate pages and components are refused`() {
        val pageFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupPage") {
                page("Home", route = "/a") {
                    node("core.Column")
                }
                page("Home", route = "/b") {
                    node("core.Column")
                }
            }
        }
        assertTrue(pageFailure.message!!.contains("duplicate"), "it did not say why: ${pageFailure.message}")

        val componentFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupComponent") {
                page("Home", route = "/home") {
                    node("core.Column")
                }
                component(ComponentDeclId("Card"), "Card") {
                    node("core.Column")
                }
                component(ComponentDeclId("Card"), "Card") {
                    node("core.Column")
                }
            }
        }
        assertTrue(
            componentFailure.message!!.contains("duplicate"),
            "it did not say why: ${componentFailure.message}",
        )
    }

    @Test
    fun `component types flow through untouched`() {
        val document = demo()

        assertEquals(
            ComponentType("m3.Text"),
            document.nodes.require(NodeId("n_2")).type,
            "a built node does not carry the type it was given",
        )
    }

    @Test
    fun `a document carries all twelve fields the record declares`() {
        val document = complete()

        assertEquals("Complete", document.meta.name)
        assertEquals("com.example.app", document.app.packageName)
        assertEquals(PageId("Home"), document.app.startPage)
        assertEquals(setOf(PageId("Home")), document.pages.keys)
        assertTrue(document.components.isEmpty(), "the fixture declares no component")
        assertEquals(1, document.nodes.size, "the fixture declares one page root and nothing else")

        assertEquals(listOf(StateId("s_session")), document.appState.map { it.id })
        assertEquals(Persistence.Saveable, document.appState.single().persistence)
        assertEquals(setOf(DataModelId("d_account")), document.dataModels.keys)
        assertEquals(
            listOf("id", "age"),
            document.dataModels.getValue(DataModelId("d_account")).fields.map { it.name.value },
            "the fields are in the order the generated class takes them",
        )
        assertEquals(setOf(TypeId("Role")), document.enums.keys)
        assertEquals(
            listOf("Admin", "Guest"),
            document.enums.getValue(TypeId("Role")).entries.map { it.name },
        )
        assertEquals(listOf("fetchAccount"), document.hostFunctions.map { it.name })
        assertEquals(setOf(ThemeId("t_brand")), document.themes.keys)
        assertEquals(ThemeId("t_brand"), document.theme)
        assertEquals(setOf(ResourceId("r_greeting")), document.resources.keys)

        // And the whole of it survives the wire, which is the only way "reachable" is worth
        // anything: a field that reaches the record and not the bytes has been declared twice.
        val text = Json.encodeToString<UiDocument>(document)
        assertEquals(document, Json.decodeFromString<UiDocument>(text))
    }

    @Test
    fun `a duplicated id is refused in every map the document declares`() {
        val modelFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupModel") {
                page("Home", route = "/home") { node("core.Column") }
                dataModel("d_x", "X")
                dataModel("d_x", "X")
            }
        }
        assertTrue(
            modelFailure.message!!.contains("duplicate data model id"),
            "it did not say why: ${modelFailure.message}",
        )

        val enumFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupEnum") {
                page("Home", route = "/home") { node("core.Column") }
                enumType("Role", "Role")
                enumType("Role", "Role")
            }
        }
        assertTrue(
            enumFailure.message!!.contains("duplicate enum type id"),
            "it did not say why: ${enumFailure.message}",
        )

        val themeFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupTheme") {
                page("Home", route = "/home") { node("core.Column") }
                theme("t_x", "X")
                theme("t_x", "X")
            }
        }
        assertTrue(themeFailure.message!!.contains("duplicate theme id"), "it did not say why: ${themeFailure.message}")

        val resourceFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupResource") {
                page("Home", route = "/home") { node("core.Column") }
                resource("r_x", "X", ResourceKind.String)
                resource("r_x", "X", ResourceKind.String)
            }
        }
        assertTrue(
            resourceFailure.message!!.contains("duplicate resource id"),
            "it did not say why: ${resourceFailure.message}",
        )
    }

    @Test
    fun `a data model and an enum type share one id namespace`() {
        // §5.5 declares the two maps as two halves of one `TypeId`, and neither record can see
        // the other map — so this is the one collision no pass downstream could ever name.
        val modelFirst = assertFailsWith<IllegalArgumentException> {
            buildDocument("Shared") {
                page("Home", route = "/home") { node("core.Column") }
                dataModel("Role", "Role")
                enumType("Role", "Role")
            }
        }
        assertTrue(modelFirst.message!!.contains("one TypeId namespace"), "it did not say why: ${modelFirst.message}")

        val enumFirst = assertFailsWith<IllegalArgumentException> {
            buildDocument("Shared") {
                page("Home", route = "/home") { node("core.Column") }
                enumType("Role", "Role")
                dataModel("Role", "Role")
            }
        }
        assertTrue(enumFirst.message!!.contains("one TypeId namespace"), "it did not say why: ${enumFirst.message}")
    }

    @Test
    fun `a theme gives one role one value, and names the map it collided in`() {
        val expected = listOf(
            "duplicate colour role",
            "duplicate typography role",
            "duplicate shape role",
            "duplicate dimension token",
            "duplicate custom token",
        )
        val messages = listOf(
            refuseSecondRole { color("primary", ColorArgb.parse("#FF000000")) },
            refuseSecondRole { typography("body", emptyMap()) },
            refuseSecondRole { shape("small", emptyMap()) },
            refuseSecondRole { dimension("md.spacing.medium", Value.Dp(4f)) },
            refuseSecondRole { custom("brand.accent", Value.Str("y")) },
        )

        // Compared as lists and not per case, so a map that named the wrong one of the five fails
        // here rather than passing every individual assertion.
        assertEquals(
            expected,
            messages.map { message -> expected.firstOrNull { message.contains(it) } },
            "the refusals did not each name their own map\n${messages.joinToString("\n")}",
        )
    }

    @Test
    fun `two variants answering to the same qualifiers are refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            buildDocument("DupVariant") {
                page("Home", route = "/home") { node("core.Column") }
                resource("r_x", "X", ResourceKind.String) {
                    variant(setOf(Qualifier.Locale("en")), ResourceSource.Text("Hello"))
                    variant(setOf(Qualifier.Locale("en")), ResourceSource.Text("Hallo"))
                }
            }
        }

        assertTrue(failure.message!!.contains("same qualifiers"), "it did not say why: ${failure.message}")
    }

    @Test
    fun `a list is not a map, so app state and host functions append`() {
        // The other half of the duplicate rule, and the half a builder gets wrong by reaching
        // for one check: `Page.state` has always accepted two of the same `StateId`, so the two
        // document-level lists are held to the same ground and not to the maps'.
        val document = buildDocument("Lists") {
            page("Home", route = "/home") { node("core.Column") }
            appState(
                listOf(
                    StateDecl(StateId("s_one"), "one", TypeRef.Str),
                    StateDecl(StateId("s_one"), "one", TypeRef.Str),
                ),
            )
            hostFunction("ping", TypeRef.Bool)
            hostFunction("ping", TypeRef.Bool)
        }

        assertEquals(2, document.appState.size, "app state refused a repeated id the way a map does")
        assertEquals(2, document.hostFunctions.size, "host functions refused a repeated name the way a map does")
    }

    @Test
    fun `a selection naming no declared theme decodes because references belong to analysis`() {
        val document = buildDocument("DanglingTheme", theme = ThemeId("t_absent")) {
            page("Home", route = "/home") { node("core.Column") }
            theme("t_real", "Real") {
                color("primary", ColorArgb.parse("#FF6200EE"))
            }
        }

        assertEquals(ThemeId("t_absent"), document.theme)
        assertEquals(setOf(ThemeId("t_real")), document.themes.keys)
        assertEquals(document, Json.decodeFromString<UiDocument>(Json.encodeToString<UiDocument>(document)))
    }

    @Test
    fun `each declaration id validates in its own constructor`() {
        val modelFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("BadModel") {
                page("Home", route = "/home") { node("core.Column") }
                dataModel("has space", "X")
            }
        }
        assertTrue(
            modelFailure.message!!.contains("Invalid DataModelId"),
            "it did not say why: ${modelFailure.message}",
        )

        val enumFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("BadEnum") {
                page("Home", route = "/home") { node("core.Column") }
                enumType("has space", "X")
            }
        }
        assertTrue(enumFailure.message!!.contains("Invalid TypeId"), "it did not say why: ${enumFailure.message}")

        val themeFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("BadTheme") {
                page("Home", route = "/home") { node("core.Column") }
                theme("has space", "X")
            }
        }
        assertTrue(themeFailure.message!!.contains("Invalid ThemeId"), "it did not say why: ${themeFailure.message}")

        val resourceFailure = assertFailsWith<IllegalArgumentException> {
            buildDocument("BadResource") {
                page("Home", route = "/home") { node("core.Column") }
                resource("has space", "X", ResourceKind.String)
            }
        }
        assertTrue(
            resourceFailure.message!!.contains("Invalid ResourceId"),
            "it did not say why: ${resourceFailure.message}",
        )
    }
}
