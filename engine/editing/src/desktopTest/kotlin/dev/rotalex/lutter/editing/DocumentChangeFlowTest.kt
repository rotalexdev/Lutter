package dev.rotalex.lutter.editing

import dev.rotalex.lutter.editing.command.ALPHA
import dev.rotalex.lutter.editing.command.CARD
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CONTENT
import dev.rotalex.lutter.editing.command.GAMMA
import dev.rotalex.lutter.editing.command.MINTED_ONE
import dev.rotalex.lutter.editing.command.OMEGA_TEXT
import dev.rotalex.lutter.editing.command.ROOT
import dev.rotalex.lutter.editing.command.TEXT_KEY
import dev.rotalex.lutter.editing.patch.Patch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DocumentController.changes], observed the way an editor observes it.
 *
 * ### Why this file is in `desktopTest` and not `commonTest`
 *
 * Collecting a flow is a suspending call and something has to run the coroutine. `runBlocking`
 * is the obvious answer and it is **not available in a common source set** — kotlinx-coroutines
 * declares it for the concurrent targets only — which is why `:engine:serialization` has its one
 * coroutine test under `src/desktopTest` for exactly this reason. Everything else this work unit
 * asserts is synchronous and lives in `commonTest`, where it runs on both targets.
 *
 * ### The race this file has to get right
 *
 * The flow has no replay, by design, so a collector that subscribes late misses what it missed.
 * `async` does not start collecting until the current coroutine suspends, which means the
 * dispatches below would be dropped without the `yield()` that lets it run first. The `yield` is
 * load-bearing and is commented as such at the call site — and every collection is wrapped in
 * `withTimeout`, so a mistake here is a failed assertion with a name rather than a CI job that
 * hangs until the runner kills it.
 */
class DocumentChangeFlowTest {

    /**
     * One announcement per applied patch, in order, each carrying the nodes it named.
     *
     * The last comparison is the sharp one: **an undo announces the inverse of the patch it
     * undoes**, not the patch itself. Announcing the original would tell an editor to repaint the
     * nodes a `RemoveSubtree` is about to put back, which for that op is precisely the set it
     * should not redraw.
     */
    @Test
    fun `every applied edit announces itself and an undo announces its inverse`() = runBlocking {
        val controller = controller()

        val observed = async { controller.changes.take(4).toList() }
        // Load-bearing: `async` does not run until the current coroutine suspends, so without
        // this the four dispatches below would happen before anybody was listening and would be
        // dropped — the flow has no replay, which is the point of the test.
        yield()

        controller.dispatch(Command.RenameNode(ALPHA, "First"))
        controller.dispatch(Command.SetProperty(ALPHA, TEXT_KEY, OMEGA_TEXT))
        controller.transaction("Wrap in Card") {
            dispatch(Command.WrapNode(GAMMA, CARD, CONTENT))
        }
        controller.undo()

        val changes = withTimeout(FLOW_TIMEOUT_MS) { observed.await() }

        assertEquals(listOf(1L, 2L, 3L, 4L), changes.map { it.revision })
        assertEquals(setOf(ALPHA), changes[0].touchedNodes)
        assertEquals(setOf(ALPHA), changes[1].touchedNodes)

        // The wrap inserted the wrapper and moved the node into it, so it names both; the undo
        // of it moves the node back and removes the wrapper, so it names both as well.
        assertEquals(setOf(MINTED_ONE, GAMMA), changes[2].touchedNodes)
        assertEquals(setOf(MINTED_ONE, GAMMA), changes[3].touchedNodes)
        assertEquals(changes[2].patch.inverse(), changes[3].patch)
    }

    /**
     * The two edits that announce nothing.
     *
     * One refused and one that had nothing to do. Both are silent because neither changed a
     * document: an editor woken for either would repaint an identical tree, and the revision a
     * change announces is how a collector tells whether it missed anything. The first
     * announcement the flow ever carries is therefore the one rename below, at revision one.
     */
    @Test
    fun `a refused command and a patch with no ops announce nothing`() = runBlocking {
        val controller = controller()

        val observed = async { controller.changes.take(1).toList() }
        yield()

        // Neither of the first two reaches the flow: one was refused, the other carried no ops.
        controller.dispatch(Command.RemoveNode(ROOT))
        controller.dispatch(Command.ApplyPatch(Patch(emptyList())))

        controller.dispatch(Command.RenameNode(ALPHA, "Only"))

        val changes = withTimeout(FLOW_TIMEOUT_MS) { observed.await() }

        assertEquals(1, changes.size)
        assertEquals(1L, changes.single().revision)
        assertEquals(setOf(ALPHA), changes.single().touchedNodes)
    }

    /** Milliseconds a collection may take before the test fails rather than hangs. */
    private companion object {
        const val FLOW_TIMEOUT_MS: Long = 10_000L
    }
}