package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.builtins.SlotOutletSpec
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.value.Value
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every generated document analyses with no error-severity diagnostic.
 *
 * ### Why this is the load-bearing test of the four
 *
 * A generator that produces *invalid* documents is not a weaker generator, it is a generator
 * that produces nothing: every property test downstream is allowed to assume its input is
 * analysable, so an invalid document turns `RoundTripPropertyTest` into a test of the analyzer's
 * refusals and `PatchInversePropertyTest` into a test of how a rejected patch is applied. Both
 * would still pass — neither property can fail on a document nothing can render — which is the
 * exact failure §5.6 makes invisible: a document in the wrong order is indistinguishable from
 * one in the right order until the engine reads it.
 *
 * So this test is not "the generator is nice". It is the precondition every other test in the
 * phase rests on, and it is here rather than in W1 because a property test is the wrong place to
 * discover that the corpus is unusable.
 *
 * ### Every failure names its seed
 *
 * The seed is the replay handle: `DocumentGenerator(generatorTestSchema, 417).generate()` is the
 * whole reproduction, and the message says so before it says anything else. The document follows
 * in full rather than as a count, because a count says "one of forty nodes" and the renderer
 * says which one — the same reasoning Phase 9's `renderDocument` was written for.
 *
 * ### Why the range is two hundred and not a thousand
 *
 * §36's thousand belongs to the property tests, which are cheap per document; this one runs the
 * whole seven-pass analysis over every seed, so it is the most expensive test in the module. Two
 * hundred documents is enough that a systematic defect — a slot filter always wrong, a scope
 * always missing — shows up on essentially every seed rather than on one, and the failure list
 * below is capped so a systematic defect produces a readable report instead of a wall.
 *
 * The documents are not sampled by draw. A contiguous range is reproducible, and a defect that
 * only appears on odd seeds would be a defect worth finding rather than one worth missing.
 */
class DocumentGeneratorValidityTest {

    @Test
    fun `every generated document analyses without an error`() {
        val refused = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (!result.hasErrors) continue
            for (diagnostic in result.diagnostics) {
                if (diagnostic.severity != Severity.Error) continue
                refused += render(seed, generated, diagnostic)
                if (refused.size >= REPORT_LIMIT) break
            }
            if (refused.size >= REPORT_LIMIT) break
        }

        assertTrue(
            refused.isEmpty(),
            "${refused.size} error diagnostics over ${VALIDITY_SEED_COUNT} generated documents.\n" +
                "Replay any one with DocumentGenerator(generatorTestSchema, <seed>).generate().\n" +
                refused.joinToString("\n\n"),
        )
    }

    @Test
    fun `analysis resolves rather than merely reporting`() {
        // `hasErrors == false` is not the same as "the document lowered": `Analyzer` returns
        // `AnalysisResult(resolved = null)` whenever any pass errored, and a corpus test that
        // only checked the flag would accept a document that stops at the analysis stage. W1
        // will run `ResolutionPass`'s product through the round trip, so the corpus is only
        // usable if that product exists.
        val unresolved = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (result.resolved != null) continue
            unresolved += "seed $seed resolved nothing:\n" + renderGenerated(generated)
            if (unresolved.size >= REPORT_LIMIT) break
        }

        assertTrue(
            unresolved.isEmpty(),
            "${unresolved.size} generated documents the analyzer refused to lower.\n" +
                unresolved.joinToString("\n\n"),
        )
    }

    @Test
    fun `a document with declared components is analysed through the overlay`() {
        // §8.4's overlay is built by `Analyzer` only when `document.components` is not empty, and
        // a `doc.*` instance analysed against the static schema alone is `component.unknown`.
        // Phase 9 found this from the editing side, where a `DropTargets` check could not resolve
        // a `doc.*` parent; this is the assertion that the corpus contains the case and that the
        // one public path over it handles it.
        var withInstance = 0
        val failures = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val generated = generatedDocument(seed)
            if (generated.plan.instantiatedComponents.isEmpty()) continue
            withInstance += 1
            val result = Analyzer(generatorTestSchema).analyze(generated.document)
            if (!result.hasErrors) continue
            failures += "seed $seed:\n" + renderGenerated(generated)
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            withInstance > 0,
            "no generated document placed a doc.* instance, so nothing exercised the overlay",
        )
        assertTrue(
            failures.isEmpty(),
            "${failures.size} documents with a doc.* instance failed analysis.\n" +
                failures.joinToString("\n\n"),
        )
    }

    @Test
    fun `a root carries no modifier the codegen stage refuses`() {
        // Analysis enforces `ModifierSpec.requiresScope` and nothing else, so the row above cannot
        // see this: a root satisfies `requiresScope` for `layout.align` and is still refused by
        // `KotlinGenerator.emitModifier`, which reads `emit.scopeEntries` and finds no enclosing
        // scope. The cost is honest and small — a root stops carrying `layout.align`, and since
        // every document that did was refused wholesale, nothing emittable is lost.
        val scoped: Set<ModifierType> = generatorTestSchema.modifiers.all()
            .filter { it.emit.scopeEntries.isNotEmpty() }
            .map { it.type }
            .toSet()
        val failures = mutableListOf<String>()

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val document = generatedDocument(seed).document
            val roots = document.pages.values.map { "page '${it.id}'" to it.root } +
                document.components.values.map { "component '${it.id}'" to it.root }
            for ((label, root) in roots) {
                for (entry in document.nodes.require(root).modifiers) {
                    if (entry.type !in scoped) continue
                    failures += "seed $seed: the $label root carries '${entry.type}'."
                }
                if (failures.size >= REPORT_LIMIT) break
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            failures.isEmpty(),
            "${failures.size} root(s) carrying a modifier with no enclosing scope to read through.\n" +
                failures.joinToString("\n\n"),
        )
    }

    /**
     * Every outlet writes a name codegen can turn into a slot.
     *
     * Analysis cannot see this: `SlotOutletSpec.slot` is a `TypeRef.Str`, so `gen-19134` is a
     * legal string to every pass and only codegen's `SlotName(named.v)` refuses it. Asserting
     * that construction rather than restating `IdSyntax`, which is `internal` to `:engine:model`
     * and could drift. That the name resolves to a slot the declaration actually has is NOT
     * asserted: nothing in the corpus can break it, and a row claiming it would claim more than
     * it proves.
     */
    @Test
    fun `a slot outlet writes a name codegen can turn into a slot`() {
        val outlet = SlotOutletSpec.spec.type
        val slotKey = SlotOutletSpec.slot.key
        val failures = mutableListOf<String>()
        var checked = 0

        for (seed in seeds(VALIDITY_SEED_COUNT)) {
            val document = generatedDocument(seed).document
            for (id in document.nodes.ids()) {
                val node = document.nodes.require(id)
                if (node.type != outlet) continue
                checked += 1
                val written = node.props[slotKey]
                val named = ((written as? PropertyValue.Const)?.value as? Value.Str)?.v
                if (named != null && runCatching { SlotName(named) }.isSuccess) continue
                failures += "seed $seed: node $id carries slot $written"
                if (failures.size >= REPORT_LIMIT) break
            }
            if (failures.size >= REPORT_LIMIT) break
        }

        assertTrue(
            checked > 0,
            "no generated document placed a slot outlet, so the row above it had nothing to check",
        )
        assertTrue(
            failures.isEmpty(),
            "${failures.size} slot outlet(s) name a slot codegen cannot spell.\n" +
                failures.joinToString("\n\n"),
        )
    }

    private fun render(seed: Long, generated: GeneratedDocument, diagnostic: Diagnostic): String =
        buildString {
            append("seed ").append(seed)
            append(" [").append(diagnostic.code).append("] ")
            append(diagnostic.message).append('\n')
            append("  at ").append(diagnostic.location).append('\n')
            append(renderGenerated(generated))
        }

    private companion object {
        const val VALIDITY_SEED_COUNT: Int = 200

        /** Enough failures to show every distinct cause, few enough to stay readable. */
        const val REPORT_LIMIT: Int = 5
    }
}
