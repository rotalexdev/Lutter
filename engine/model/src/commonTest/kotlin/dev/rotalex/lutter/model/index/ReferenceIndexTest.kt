package dev.rotalex.lutter.model.index

import dev.rotalex.lutter.model.action.ActionId
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.AppSpec
import dev.rotalex.lutter.model.doc.DocumentMeta
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.NodeTable
import dev.rotalex.lutter.model.doc.Page
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.FunctionId
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `ReferenceIndex` contract: which nodes mention a page, a resource, a state or a document
 * component.
 *
 * PLAN §5.6:442 declares this class with **no members** — a constructor and the comment *"who
 * references state/page/resource/component (safe delete & rename)"* — so the four queries
 * below are the narrowest set that answers §6.2's "who uses X" for the four kinds §5.6 names.
 * Two things follow from that, and both are tested:
 *
 *  * **A component instance references its declaration by its component type, not by a
 *    reference value.** §5.7 defines an instance as a node whose type is `doc.<id>`, so an
 *    index that only looked for `Value.Ref` would report every component in a document as
 *    unused — and safe-delete would delete it.
 *  * **An id in a document is compared as a string and never reconstructed.** `Value.Ref.id` is
 *    a bare `String` and every id type's `init` refuses a malformed value, so rebuilding a
 *    `PageId` from a document to compare it would take the index down on a document that opens
 *    perfectly well. The last test is that case.
 *
 * **What this index does not cover** is a declaration: `StateDecl.initial` is a `Value?` and a
 * theme's `custom` map is a bag of them, so a `Value.Ref` can be written somewhere with no node
 * behind it. A `NodeId` is the only locator this module can point at — §17.2's
 * `DiagnosticLocation` lives in `:engine:analysis` and §23.3 puts it out of reach — so that gap
 * is stated rather than papered over with a second locator type.
 */
class ReferenceIndexTest {

    private val target = PropertyKey("target")

    private val button = Node(
        id = NodeId("n_button"),
        type = ComponentType("m3.Button"),
        props = mapOf(target to PropertyValue.Const(Value.Ref(RefKind.Page, "p_profile"))),
        modifiers = listOf(
            ModifierEntry(
                type = ModifierType("layout.padding"),
                args = mapOf(
                    PropertyKey("start") to PropertyValue.Const(
                        Value.Ref(RefKind.Resource, "r_icon"),
                    ),
                ),
            ),
        ),
        events = mapOf(
            EventKey("onClick") to ActionSequence(
                listOf(
                    ActionStep(
                        action = ActionId("nav.navigate"),
                        args = mapOf(
                            target to PropertyValue.Computed(
                                Expr.Call(
                                    function = FunctionId("state.read"),
                                    args = listOf(Expr.Ref(RefTarget.State(StateId("s_count")))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val instance = Node(
        id = NodeId("n_instance"),
        type = ComponentType("doc.c_card"),
        props = mapOf(target to PropertyValue.Const(Value.Ref(RefKind.Component, "c_card"))),
    )

    private val nested = Node(
        id = NodeId("n_nested"),
        type = ComponentType("m3.Text"),
        props = mapOf(
            target to PropertyValue.Const(
                Value.ListOf(
                    listOf(
                        Value.MapOf(
                            value = Value.Str("dp"),
                            entries = mapOf(
                                PropertyKey("gap") to Value.Ref(RefKind.Page, "p_profile"),
                            ),
                        ),
                        Value.Obj(
                            typeId = TypeId("User"),
                            fields = mapOf(
                                PropertyKey("avatar") to Value.Ref(RefKind.Resource, "r_icon"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val document = UiDocument(
        meta = DocumentMeta("Demo"),
        app = AppSpec("com.example.demo", PageId("p_home")),
        pages = mapOf(
            PageId("p_home") to Page(PageId("p_home"), "HomeScreen", "/home", root = NodeId("n_button")),
        ),
        components = emptyMap(),
        nodes = NodeTable.EMPTY.with(button).with(instance).with(nested),
    )

    private val index = ReferenceIndex(document)

    @Test
    fun `a page is referenced from a property, from inside a list, a map and an object`() {
        // The four containers all recurse, because a reference nested three deep is a reference
        // a safe delete has to see. An index that only looked at the top level of a property
        // would report `p_profile` unused while the button still navigates to it.
        assertEquals(
            setOf(NodeId("n_button"), NodeId("n_nested")),
            index.pageReferrers(PageId("p_profile")),
        )
        assertEquals(emptySet<NodeId>(), index.pageReferrers(PageId("p_absent")))
    }

    @Test
    fun `a resource is referenced from a modifier argument and from inside an object`() {
        // `ModifierEntry.args` is a reference too, and it is the same
        // `Map<PropertyKey, PropertyValue>` as `Node.props` and `ActionStep.args`.
        assertEquals(
            setOf(NodeId("n_button"), NodeId("n_nested")),
            index.resourceReferrers(ResourceId("r_icon")),
        )
    }

    @Test
    fun `a state is referenced from an expression inside an action argument`() {
        // `RefTarget.State` inside an `Expr` inside a `PropertyValue.Computed` inside an action
        // argument, which is the deepest shape a reference can take in a document. Five levels
        // of nesting and the index still answers.
        assertEquals(setOf(NodeId("n_button")), index.stateReferrers(StateId("s_count")))
        assertEquals(emptySet<NodeId>(), index.stateReferrers(StateId("s_absent")))
    }

    @Test
    fun `a component is referenced by its instances and by its references`() {
        // §5.7: an instance is a node whose type is `doc.<id>`, so it references the
        // declaration through its *component type*. An index that only looked for a
        // `Value.Ref` would call every component in a document unused.
        assertEquals(setOf(NodeId("n_instance")), index.componentReferrers(ComponentDeclId("c_card")))
        assertEquals(emptySet<NodeId>(), index.componentReferrers(ComponentDeclId("c_absent")))

        // And a built-in type is not an instance of anything: `m3.Button` is namespaced, but it
        // is not under `doc.`, so it is not a reference to a document component.
        assertEquals(emptySet<NodeId>(), index.componentReferrers(ComponentDeclId("m3")))
    }

    @Test
    fun `an id the document spells badly is compared, not constructed`() {
        // The honest failure mode of the alternative. `Value.Ref.id` is a bare `String` and every
        // id type refuses a malformed value in its `init`, so rebuilding a `PageId` from a
        // document in order to compare it would take the whole index down on a file that opens.
        val broken = Node(
            id = NodeId("n_broken"),
            type = ComponentType("m3.Text"),
            props = mapOf(
                target to PropertyValue.Const(Value.Ref(RefKind.Page, "has space")),
            ),
        )
        val brokenIndex = ReferenceIndex(document.copy(nodes = NodeTable.EMPTY.with(broken)))

        // The document decodes, so the index has to answer.
        assertEquals(emptySet<NodeId>(), brokenIndex.pageReferrers(PageId("p_home")))
        // And the id the caller would have to construct to look it up is the thing that throws.
        assertFailsWith<IllegalArgumentException> { PageId("has space") }
    }

    @Test
    fun `a document with no references answers every query with nothing`() {
        val bare = NodeTable.EMPTY.with(Node(id = NodeId("n_1"), type = ComponentType("core.Column")))
        val bareIndex = ReferenceIndex(document.copy(nodes = bare))

        assertEquals(emptySet<NodeId>(), bareIndex.pageReferrers(PageId("p_home")))
        assertEquals(emptySet<NodeId>(), bareIndex.resourceReferrers(ResourceId("r_icon")))
        assertEquals(emptySet<NodeId>(), bareIndex.stateReferrers(StateId("s_count")))
        assertEquals(emptySet<NodeId>(), bareIndex.componentReferrers(ComponentDeclId("c_card")))
    }
}
