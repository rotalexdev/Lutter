package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.action.ActionSequence
import dev.rotalex.lutter.model.action.ActionStep
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.NodeScope
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * A handler written in the document reaches [ResolvedNode] whole, and a node that writes none
 * says so rather than arriving absent.
 *
 * Everything asserted here is what pass 7 either carries or does not: no pass checks a handler,
 * so a sequence that arrives is the document's own and the record cannot be what makes it
 * correct.
 */
class EventResolutionTest {

    private val analyzer: Analyzer<String, String, String> = Analyzer(testSchema())
    private val home: PageId = PageId("p_home")
    private val click: EventKey = EventKey("onClick")

    private val back: ActionSequence = ActionSequence(listOf(ActionStep(ActionId("nav.back"))))
    private val dialog: ActionSequence = ActionSequence(
        listOf(ActionStep(ActionId("ui.showDialog"), args = mapOf(PropertyKey("name") to constOf(Value.Str("a"))))),
    )

    @Test
    fun `a handler arrives as the sequence the document wrote`() {
        val resolved = resolvedOf(document { event(click, back) })

        assertEquals(mapOf(click to back), resolved.events, "the handler did not arrive whole")
        assertEquals(listOf(ActionId("nav.back")), actionsOf(resolved.events.getValue(click)))
    }

    @Test
    fun `a node that writes no handler resolves to an empty map`() {
        assertEquals(emptyMap(), resolvedOf(document { }).events)
    }

    @Test
    fun `two handlers on one node both arrive under their own key`() {
        val longPress = EventKey("onLongPress")

        val resolved = resolvedOf(document {
            event(click, back)
            event(longPress, dialog)
        })

        assertEquals(setOf(click, longPress), resolved.events.keys, "a key was lost or two shared one")
        assertEquals(listOf(ActionId("nav.back")), actionsOf(resolved.events.getValue(click)))
        assertEquals(listOf(ActionId("ui.showDialog")), actionsOf(resolved.events.getValue(longPress)))
    }

    @Test
    fun `a multi step handler carrying a nested branch arrives whole`() {
        val branched = ActionSequence(
            listOf(
                ActionStep(
                    action = ActionId("flow.if"),
                    branches = mapOf(BranchName("then") to back, BranchName("else") to dialog),
                ),
                ActionStep(ActionId("ui.hideDialog")),
            ),
        )

        val carried = resolvedOf(document { event(click, branched) }).events.getValue(click)

        assertEquals(branched, carried, "the handler was rewritten on the way through")
        assertEquals(2, carried.steps.size, "a step was lost: ${carried.steps}")
        assertEquals(
            listOf(ActionId("nav.back")),
            actionsOf(carried.steps[0].branches.getValue(BranchName("then"))),
            "the nested branch did not arrive",
        )
        assertEquals(
            listOf(ActionId("ui.showDialog")),
            actionsOf(carried.steps[0].branches.getValue(BranchName("else"))),
            "the second nested branch did not arrive",
        )
        assertEquals(
            constOf(Value.Str("a")),
            carried.steps[0].branches.getValue(BranchName("else")).steps[0].args[PropertyKey("name")],
            "a branch argument did not arrive",
        )
    }

    @Test
    fun `two nodes differing only in a handler are not equal`() {
        assertNotEquals(resolvedOf(document { event(click, back) }), resolvedOf(document { }))
    }

    /**
     * The default the codegen and runtime fixtures are built on: a node assembled by hand with
     * no handler reads as none, and `copy` attaches one afterwards.
     */
    @Test
    fun `a hand built node has no handler until one is attached`() {
        val bare = ResolvedNode(NodeId("n_1"), LinkType, emptyMap(), emptyList(), emptyMap(), emptySet())

        assertEquals(emptyMap(), bare.events)
        assertEquals(
            mapOf(click to back),
            bare.copy(events = mapOf(click to back)).events,
            "copy cannot attach a handler",
        )
    }

    /** The action ids [sequence] runs, in order. */
    private fun actionsOf(sequence: ActionSequence): List<ActionId> = sequence.steps.map { it.action }

    /** One page whose root is a `core.Link`, carrying whatever [block] binds. */
    private fun document(block: NodeScope.() -> Unit): UiDocument = homeDocument { node(LinkType, block = block) }

    private fun resolvedOf(document: UiDocument): ResolvedNode =
        assertNotNull(analyzer.analyze(document).resolved).pages.getValue(home).root
}