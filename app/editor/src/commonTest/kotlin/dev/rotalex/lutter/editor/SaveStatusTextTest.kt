package dev.rotalex.lutter.editor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The status line's wording, as a value.
 *
 * The bar used to print the whole location, which on Android is
 * `/data/user/0/dev.rotalex.lutter/files/document.json` — two lines of filesystem path at the
 * bottom of the window, on every platform, for a file the person editing never chose and cannot
 * open. What they asked was "did that save?", so the line answers that and names the file.
 *
 * These are assertions about text because text is all this line is. A screenshot would prove it
 * once and rot; a string is checked on every run.
 */
class SaveStatusTextTest {

    private val androidLocation = "/data/user/0/dev.rotalex.lutter/files/document.json"

    @Test
    fun the_saved_line_names_the_file_and_never_the_directory() {
        val text = saveStatusText(androidLocation, SaveState.Saved(at = "23:35:20"), notice = null)

        assertEquals("Saved 23:35:20 · document.json", text)
        assertFalse(text.contains("/data/user"), text)
        assertFalse(text.contains("files/"), text)
    }

    @Test
    fun the_saved_line_keeps_the_word_a_test_waits_on() {
        // `MainActivityWorkspaceTest` waits for the save to land by looking for this word; the
        // reason it can is that the word is the status, not a decoration around it.
        assertContains(saveStatusText(androidLocation, SaveState.Saved(at = "08:00:00"), null), "Saved")
    }

    @Test
    fun the_first_frame_does_not_claim_a_save_that_has_not_happened() {
        val text = saveStatusText(androidLocation, SaveState.Idle, notice = null)

        assertEquals("Not saved yet · document.json", text)
        assertFalse(text.contains("Saved"), text)
    }

    @Test
    fun a_failure_says_it_and_says_why() {
        val text = saveStatusText(
            location = androidLocation,
            state = SaveState.Failed(at = "23:35:21", reason = "No space left on device"),
            notice = null,
        )

        assertTrue(text.startsWith("NOT SAVED 23:35:21:"), text)
        assertContains(text, "No space left on device")
    }

    @Test
    fun a_notice_leads_and_the_status_follows() {
        val text = saveStatusText(
            location = androidLocation,
            state = SaveState.Saved(at = "23:35:20"),
            notice = "The previous document could not be read; this is a new one.",
        )

        assertTrue(text.startsWith("The previous document"), text)
        assertContains(text, "Saved 23:35:20 · document.json")
    }

    @Test
    fun windows_separators_are_stripped_too() {
        val text = saveStatusText(
            location = "C:\\Users\\someone\\AppData\\Local\\Lutter\\document.json",
            state = SaveState.Saved(at = "23:35:20"),
            notice = null,
        )

        assertEquals("Saved 23:35:20 · document.json", text)
        assertFalse(text.contains("C:\\"), text)
    }

    @Test
    fun a_bare_file_name_is_left_alone() {
        assertEquals("document.json", documentLabel("document.json"))
    }
}
