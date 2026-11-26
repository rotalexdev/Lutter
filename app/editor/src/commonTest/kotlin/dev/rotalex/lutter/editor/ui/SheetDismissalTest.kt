package dev.rotalex.lutter.editor.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule a dismissed sheet obeys, asserted without a window.
 *
 * The gesture that fires `onDismissRequest` cannot be injected into this module's runner — the
 * sheet composes in its own window — so what is pinned here is the decision that gesture leads to:
 * a sheet leaves the composition only after its hide finished, never while it is still on screen
 * and never after a hide that was itself cancelled. Removing a `ModalBottomSheet` in any of those
 * states is what tore a focused field and a dialog's state down mid-animation.
 */
class SheetDismissalTest {

    @Test
    fun a_finished_hide_leaves_the_sheet_off_screen() {
        assertTrue(removesHostAfter(cause = null, visible = false))
    }

    @Test
    fun a_sheet_still_on_screen_is_never_removed() {
        assertFalse(removesHostAfter(cause = null, visible = true))
    }

    @Test
    fun a_cancelled_hide_is_not_a_dismissal() {
        assertFalse(removesHostAfter(CancellationException("shown again"), visible = false))
    }
}
