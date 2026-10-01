package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.codegen.CodegenCoverage
import kotlin.test.Test

/** Both fail-fast gates hold over the skeleton schema, or construction itself refuses. */
class BuiltinCoverageTest {

    @Test
    fun `every builtin spec renders and emits`() {
        val schema = ConformanceHarness.schema()

        CodegenCoverage.check(schema)
        ConformanceHarness.runtime(schema)
    }
}
