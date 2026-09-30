package dev.rotalex.lutter.model.dsl

import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.index.DocumentIndex
import dev.rotalex.lutter.model.index.NodeOwner
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
 * Every refusal below asserts its message, not just its type. A guardrail that throws
 * without saying why is a guardrail its caller cannot act on.
 */
class DslTest {

    private fun demo(): UiDocument = buildDocument("Demo", SequentialIdGenerator("n_")) {
        page("Home", route = "/home") {
            node("core.Column") {
                slot(
                    "children",
                    node("m3.Text") { prop("text", Value.Str("Welcome")) },
                    node("m3.Button") {
                        slot("content", node("m3.Text") { prop("text", Value.Str("Go")) })
                        event("onClick", ActionSequence(listOf(ActionStep(ActionId("nav.navigate")))))
                    },
                )
                modifier("layout.fillMaxSize")
            }
        }
        component(ComponentDeclId("Card"), "Card") {
            node("core.Column") {
                slot("children", node("m3.Text") { computed("text", Expr.Const(Value.Int32(1))) })
            }
        }
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
}
