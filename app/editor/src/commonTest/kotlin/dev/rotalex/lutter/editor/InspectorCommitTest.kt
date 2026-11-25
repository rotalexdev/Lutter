package dev.rotalex.lutter.editor

import dev.rotalex.lutter.editor.ui.isNewEdit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule behind the inspector's commit-on-leave.
 *
 * There is no Compose UI test in this module (the hosts own those), so the half of the
 * behaviour that can be stated without a screen is stated here: what counts as an edit.
 */
class InspectorCommitTest {

    @Test
    fun a_field_that_keeps_its_text_is_not_an_edit() {
        assertFalse(isNewEdit(sent = "\"Hello\"", draft = "\"Hello\""))
    }

    @Test
    fun typed_text_is_an_edit() {
        assertTrue(isNewEdit(sent = "\"Hello\"", draft = "\"Helloo\""))
    }

    @Test
    fun clearing_the_field_is_an_edit() {
        assertTrue(isNewEdit(sent = "\"Hello\"", draft = ""))
    }

    @Test
    fun whitespace_is_a_difference_the_engine_decides_not_the_field() {
        // The field does not trim: `PropertyText.apply` is the parser of record, and a rule that
        // guessed at canonical form here would refuse text the engine accepts.
        assertTrue(isNewEdit(sent = "\"Hello\"", draft = " \"Hello\" "))
    }
}
