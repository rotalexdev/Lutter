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

    @Test
    fun `the suite covers every wave one layout document plus one state document`() {
        assertEquals(
            listOf(
                "box_align",
                "column_color",
                "column_text",
                "row_weight",
                "spacer_size",
                "text_field_state",
            ),
            FixtureDocuments.ids,
        )
    }
}
