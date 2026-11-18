package dev.rotalex.lutter.testsupport.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §29's performance table as the harness states it, and the one row phase 13 is adding.
 *
 * The plan's own table is the contract, so the assertion is the table's shape — six rows, in
 * the plan's order, the last one the post-MVP promise this phase is collecting:
 * "Incremental re-analysis after a single-property edit | < 10 ms (Phase post-MVP)".
 */
class BenchmarkTableRowTest {

    @Test
    fun `the incremental row is the plan's sixth with a ten millisecond promise`() {
        assertEquals(10L, Scenario.Incremental.targetMillis)

        assertEquals(
            listOf("Decode", "Encode", "Analyze", "Codegen", "Command", "Incremental"),
            Scenario.values().map { it.name },
            "the rows follow §29's table order, the post-MVP row last as the plan lists it",
        )
    }
}
