package dev.rotalex.lutter.testsupport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoldenTest {

    private fun source(vararg entries: Pair<String, String>): GoldenSource {
        val stored = entries.toMap()
        return GoldenSource { path -> stored[path] }
    }

    @Test
    fun returnsTheStoredTextWhenItMatches() {
        val golden = Golden.assertEquals("card.json", "stored", source("card.json" to "stored"))

        assertEquals("stored", golden)
    }

    @Test
    fun returnsTheActualTextForWritingWhenUpdating() {
        val toWrite = Golden.assertEquals("card.json", "regenerated", source(), update = true)

        assertEquals("regenerated", toWrite)
    }

    @Test
    fun reportsBothValuesOnMismatch() {
        val failure = assertFailsWith<IllegalStateException> {
            Golden.assertEquals("card.json", "actual", source("card.json" to "expected"))
        }

        assertTrue(failure.message.orEmpty().contains("expected"), failure.message.orEmpty())
        assertTrue(failure.message.orEmpty().contains("actual"), failure.message.orEmpty())
    }

    @Test
    fun reportsAnAbsentGoldenAsAnAuthoringError() {
        assertFailsWith<IllegalStateException> {
            Golden.read("missing.json", source())
        }
    }
}
