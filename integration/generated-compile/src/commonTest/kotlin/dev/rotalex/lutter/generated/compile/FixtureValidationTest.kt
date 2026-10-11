package dev.rotalex.lutter.generated.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The fixtures the suite compares are valid documents, or the comparison proves nothing. */
class FixtureValidationTest {

    @Test
    fun `every fixture validates with no diagnostics`() {
        val schema = ConformanceHarness.schema()

        for (id in FixtureDocuments.ids) {
            val result = ConformanceHarness.analyze(schema, id)

            assertTrue(result.diagnostics.isEmpty(), "fixture '$id': ${result.diagnostics}")
            assertNotNull(result.resolved, "fixture '$id' resolved nothing")
        }
    }

    /**
     * The fixture set itself, spelled out.
     *
     * A list rather than a count, because the list is the claim: each entry names a document that
     * exists for a reason — a layout half, a state half, an expression corpus, and Phase 8's
     * component with a param and a slot. Adding a fixture without a reason for it is the drift
     * this catches, and so is dropping one.
     */
    @Test
    fun `the suite covers every wave one layout document plus one state one component and one corpus`() {
        assertEquals(
            listOf(
                "box_align",
                "column_color",
                "column_text",
                "component_param_slot",
                "expression_corpus",
                "row_weight",
                "spacer_size",
                "text_field_state",
            ),
            FixtureDocuments.ids,
        )
    }
}
