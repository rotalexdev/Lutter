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
     * exists for a reason — a layout half, a state half, an expression corpus, Phase 8's component
     * with a param and a slot, and its two theme-token documents. Adding a fixture without a
     * reason for it is the drift this catches, and so is dropping one.
     *
     * The two theme fixtures are a pair and not one: `theme_tokens` is the only document that can
     * hold a token the theme declares *and* a token it shadows, and `material_token` is the only
     * one that can hold a token Material answers for. §14.2's rule — a selected theme is the whole
     * vocabulary — means no single document reaches both, and between them they are the whole of
     * §14.2's resolution table.
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
                "material_token",
                "row_weight",
                "spacer_size",
                "text_field_state",
                "theme_tokens",
                "box_background",
                "border_text",
                "card_align",
                "column_min_height",
                "column_padding",
                "nested_depth3",
                "row_align",
                "row_padding",
                "slot_outlet",
                "spacer_flex",
                "text_alpha",
                "text_multi_fixed",
                "text_state_ref",
            ),
            FixtureDocuments.ids,
        )
    }
}
