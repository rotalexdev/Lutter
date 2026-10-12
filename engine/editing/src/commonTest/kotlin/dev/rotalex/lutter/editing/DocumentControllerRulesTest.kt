package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.EditCodes
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.OMEGA_TEXT
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT_KEY
import dev.rotalex.lutter.editing.command.plannerDocument
import dev.rotalex.lutter.model.doc.UiDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * PLAN §26.3's four rules, one test each.
 *
 * ### Why these are four tests and not one
 *
 * §26.3 is four sentences and each is a separate claim about a separate part of the controller:
 * the redo stack lives in [dev.rotalex.lutter.editing.history.History], the refusal path lives in
 * [DocumentController.dispatch], grouping lives in `transaction`, and persistence is a property
 * of the module rather than of a function. A single test asserting all four would keep passing
 * as three of them stopped being true — it would fail once, loudly, with four causes.
 *
 * ### Every rule is asserted on an observable, not on an internal
 *
 * The controller does not expose its history, and that is deliberate (§26.3's fourth rule plus
 * §25.1). So "the redo stack was cleared" is observed as `canRedo` going false and `redo()`
 * returning false, and "one undo step" is observed by counting how many times `undo()` returns
 * true before it stops. A test that could reach the history would be testing a door the editor
 * is not supposed to have.
 *
 * ### Nothing here reads a clock
 *
 * The merge window is exercised in `HistoryTest`, where it belongs, and through `dispatch` in
 * the test below. No test in this module can produce a different answer by running slower than
 * another, which is §28.3's determinism claim stated about the tests themselves.
 */
class DocumentControllerRulesTest {

    /**
     * §26.3, rule one: *"Redo stack cleared on new command."*
     *
     * Two halves, because the rule is only half a rule without the second. Redo works while it
     * is allowed to, and it stops working the moment anything is edited after the undo — not
     * because redo was disabled, but because the document it would have been applied to is no
     * longer the one it was undone from.
     */
    @Test
    fun `a new command clears the redo stack`() {
        val controller = controller()
        val pristine: UiDocument = controller.state.value.document

        controller.dispatch(Command.RenameNode(ALPHA, "First"))
        controller.dispatch(Command.RenameNode(ALPHA, "Second"))

        // Redo works before the new command, so the assertion below is about the clearing and
        // not about a redo that never functioned.
        assertTrue(controller.undo())
        assertEquals("First", labelOf(controller.state.value.document, ALPHA))
        assertTrue(controller.redo())
        assertEquals("Second", labelOf(controller.state.value.document, ALPHA))
        assertTrue(controller.undo())

        controller.dispatch(Command.RenameNode(ALPHA, "Third"))

        assertFalse(controller.state.value.canRedo)
        assertFalse(controller.redo(), "the cleared redo entry came back")
        assertEquals("Third", labelOf(controller.state.value.document, ALPHA))

        // And the edit that cleared it is still one undo step away, so clearing did not cost
        // the redo entry's place in the stack.
        assertTrue(controller.undo())
        assertEquals(pristine, controller.state.value.document)
    }

    /**
     * §26.3, rule two: *"Failed commands (rejected) never enter history."*
     *
     * `Command.RemoveNode(ROOT)` is the fixture's own refusal: the page root has no parent, so
     * `planRemoveNode` answers `EditCodes.NoParent`. The assertion that matters is the triple at
     * the bottom — same document, same revision, no undo step — and a fourth thing that has no
     * assertion here and is checked by the next test: the redo stack survived.
     */
    @Test
    fun `a refused command changes neither the document nor the history`() {
        val controller = controller()
        val pristine: UiDocument = controller.state.value.document

        val refused: CommandResult = controller.dispatch(Command.RemoveNode(ROOT))

        assertTrue(refused is CommandResult.Rejected, "expected a rejection, got $refused")
        assertEquals(EditCodes.NoParent, (refused as CommandResult.Rejected).reasons.single().code)

        assertEquals(pristine, controller.state.value.document, "a refusal mutated the document")
        assertEquals(0L, controller.state.value.revision, "a refusal advanced the revision")
        assertFalse(controller.state.value.canUndo)
        assertFalse(controller.undo(), "a refusal became an undo step")
    }

    /**
     * The other half of rule two, and the one a simpler implementation gets wrong.
     *
     * A refusal must not clear the redo stack either. §26.3's first rule says a *new command*
     * clears it, and a command that was refused is not a new anything — it is a message to the
     * user saying the drop was not allowed. Clearing a person's redo because they tried an edit
     * the schema forbids would take away work they had already undone for no reason at all.
     */
    @Test
    fun `a refused command leaves the redo stack alone`() {
        val controller = controller()
        val pristine: UiDocument = controller.state.value.document

        controller.dispatch(Command.RenameNode(ALPHA, "First"))
        controller.dispatch(Command.RenameNode(ALPHA, "Second"))
        assertTrue(controller.undo())

        controller.dispatch(Command.RemoveNode(ROOT))

        assertTrue(controller.state.value.canRedo, "a refusal is not an edit and cannot invalidate a redo")
        assertTrue(controller.redo())
        assertEquals("Second", labelOf(controller.state.value.document, ALPHA))

        // Two undos from here and the document is the one the controller was built with, which
        // is also the count check: the refusal added nothing to either stack.
        var steps: Int = 0
        while (controller.undo()) steps++
        assertEquals(2, steps, "the refusal became a step")
        assertEquals(pristine, controller.state.value.document)
    }

    /**
     * §26.3, rule three: *"`transaction {}` groups multiple commands into one history entry."*
     *
     * The subject is the count, so the assertion is a count: `undo()` is asked until it stops
     * and the number of `true`s is compared. Anything else — the document after one undo, the
     * revision — would also pass against a controller that recorded three entries and happened
     * to undo them in an order that looked right.
     *
     * The three commands are §26.3's own example. Wrapping a node and then renaming it is not
     * three unrelated edits: the second can only be planned because the first moved the node,
     * which is also why the composed patch is invertible in one step rather than three.
     */
    @Test
    fun `a transaction is one undo step and not one per command`() {
        val controller = controller()
        val pristine: UiDocument = controller.state.value.document

        controller.transaction("Wrap in Card") {
            dispatch(Command.WrapNode(GAMMA, CARD, CONTENT))
            dispatch(Command.RenameNode(GAMMA, "Wrapped"))
            dispatch(Command.SetProperty(ALPHA, TEXT_KEY, OMEGA_TEXT))
        }

        assertNotEquals(pristine, controller.state.value.document, "the transaction changed nothing")
        assertEquals(1L, controller.state.value.revision, "one gesture, one published revision")

        var steps: Int = 0
        while (controller.undo()) steps++

        assertEquals(1, steps, "three commands produced three undo steps")
        assertEquals(pristine, controller.state.value.document)
    }

    /**
     * Rule three again, for the other shape §26.3 names: bulk paste.
     *
     * The size is the variable here — one entry whatever the count — so a run of three is
     * compared against a run of one in the same test, and the three-insert case is built from
     * `DuplicateNode` because the fixture's single free row is one fixed id and pasting the same
     * id three times is a duplicate rather than a paste.
     */
    @Test
    fun `a transaction is one step whatever its size`() {
        val single = controller()
        single.transaction("Insert one") { dispatch(Command.DuplicateNode(GAMMA)) }
        val bulk = controller()
        bulk.transaction("Paste three") {
            dispatch(Command.DuplicateNode(GAMMA))
            dispatch(Command.DuplicateNode(GAMMA))
            dispatch(Command.DuplicateNode(GAMMA))
        }

        assertEquals(1L, single.state.value.revision)
        assertEquals(1L, bulk.state.value.revision, "a paste of three is three published revisions")

        var singleSteps: Int = 0
        while (single.undo()) singleSteps++
        var bulkSteps: Int = 0
        while (bulk.undo()) bulkSteps++

        assertEquals(1, singleSteps)
        assertEquals(1, bulkSteps)
        assertEquals(plannerDocument(), single.state.value.document)
        assertEquals(plannerDocument(), bulk.state.value.document)
    }

    /**
     * Rule four: *"History is editor session state; it is not persisted in the document."*
     *
     * **Asserted on the bytes.** The strongest statement available inside this module is that a
     * document which has been through a session encodes to exactly the same string as one that
     * never has — and that no label or merge key the session used appears anywhere in it. A
     * history stored in `UiDocument` would break both, and a `DocumentState` carrying one would
     * be invisible to this test but visible to the next one, which is why both are asserted
     * here and the second is asserted at all.
     *
     * The encoder is `UiDocument`'s own generated serializer rather than a configured instance
     * from `:engine:serialization`: §23.3 puts that module out of this one's reach, and the
     * default `Json` is what `PatchWireFormatTest` uses for the same reason.
     */
    @Test
    fun `a session leaves nothing of itself in the document`() {
        val controller = controller()
        val pristine: String = encode(controller.state.value.document)

        controller.transaction("Wrap in Card") {
            dispatch(Command.WrapNode(GAMMA, CARD, CONTENT))
            dispatch(Command.RenameNode(GAMMA, "Wrapped"))
        }
        // The node's new name is document content and is expected in the encoding; the label and
        // the merge key beside it are session state and are not. Naming them separately is the
        // difference between this test proving the rule and this test proving nothing.
        controller.dispatch(Command.RenameNode(ALPHA, "Typed"), label = "Rename Alpha", mergeKey = "n_a:text")

        val live: String = encode(controller.state.value.document)
        assertNotEquals(pristine, live, "the session changed nothing, so this test proves nothing")

        for (sessionOnly in listOf("Wrap in Card", "Rename Alpha", "n_a:text")) {
            assertFalse(sessionOnly in live, "'$sessionOnly' is session state and reached the document")
        }
        assertTrue("Typed" in live, "the node's own label is document content, not session state")

        var steps: Int = 0
        while (controller.undo()) steps++

        assertEquals(2, steps, "the transaction and the standalone edit are two entries")
        assertEquals(pristine, encode(controller.state.value.document))
    }

    /**
     * Rule four from the other side: the state an editor collects carries a document, a counter
     * and two booleans, and nothing else.
     *
     * Written as an equality against a hand-built [DocumentState] rather than as a count of
     * fields, because a field-by-field check would need reflection this module does not carry
     * and because the equality is the stronger statement anyway: it says the controller produced
     * exactly the record §25.2 declares, with no room in it for a history.
     */
    @Test
    fun `the state an editor collects is the document and nothing else`() {
        val controller = controller()
        controller.dispatch(Command.RenameNode(ALPHA, "First"))
        controller.dispatch(Command.RenameNode(ALPHA, "Second"))
        assertTrue(controller.undo())

        val current: DocumentState = controller.state.value
        assertEquals(
            DocumentState(document = current.document, revision = 3L, canUndo = true, canRedo = true),
            current,
        )

        // And the revision is a count of applied patches, not of edits: three patches were
        // applied (two forwards and one back) and the counter says so.
        assertEquals(3L, current.revision)
    }

    /** The document's own encoding, which is what a session must not have written into. */
    private fun encode(document: UiDocument): String =
        Json.encodeToString(UiDocument.serializer(), document)
}