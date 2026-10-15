package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PLAN §36's fuzz batch generates two hundred documents against the real builtin schema, and
 * this is the half of that claim a pull request can afford: a fraction of the seed range,
 * analysed, with no fork and no codegen.
 *
 * The generator is otherwise only ever run against `generatorSchema()`, which differs from the
 * builtin schema by three components no engine module registers. So nothing asserted that it can
 * read a schema without them — and a generator that cannot is indistinguishable, from the
 * nightly's log alone, from a batch that refuses every document and reports green.
 *
 * The codegen half is the nightly's own, because `CodegenCoverage` has already run over this
 * schema in `BuiltinCoverageTest`. A refusal there is a refusal of something downstream, so
 * failing here instead would blame the wrong layer.
 */
class BuiltinSchemaTest {

    @Test
    fun `every seed the batch walks analyses against the builtin schema`() {
        val schema = ConformanceHarness.schema()
        val failures = mutableListOf<String>()
        for (seed in 1L..SEEDS) {
            val document = DocumentGenerator(schema, seed).generate().document
            val errors = BuiltinEngine.analyze(schema, document).diagnostics
                .filter { it.severity == Severity.Error }
            if (errors.isNotEmpty()) {
                failures += "seed $seed: " + errors.joinToString("; ") { "${it.code.value}: ${it.message}" }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of $SEEDS seed(s) did not analyse against the builtin schema.\n" +
                failures.joinToString("\n\n"),
        )
    }

    private companion object {

        /** A fraction of the nightly's range: twenty seeds here, two hundred there. */
        const val SEEDS: Long = 20
    }
}