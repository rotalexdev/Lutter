package dev.rotalex.lutter.testsupport.incremental

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.testsupport.benchmark.dirtySetFor
import dev.rotalex.lutter.testsupport.generator.DocumentGenerator
import dev.rotalex.lutter.testsupport.generator.codegenCorpusView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §32 P13's correctness spine, restated as the equality it names: **for generated documents and
 * random patches, incremental re-analysis yields a `ResolvedDocument` equal to a from-scratch
 * analysis.**
 *
 * ### A seeded loop, because this repository has no property-testing library
 *
 * The same decision `PatchRoundTripTest` records for §26.2: nothing in
 * `gradle/libs.versions.toml` draws documents, `settings.gradle.kts` takes every other
 * coordinate from the org-wide `rootLibs` catalog, and the repo's own pattern for breadth is
 * the seeded loop — `DeterminismTest` and `PermutationTest` both run `DocumentGenerator` over
 * seed ranges. This suite draws document `seed` for `seed in 1..1000` and its patch from the
 * same seed, so a failure names the seed that reproduces it, and the two analyses it compares
 * are the engine's own — unlike a table, the oracle here *is* the from-scratch run, because
 * the claim is exactly “the witness agrees with the ground truth”, not “the witness is this
 * literal”.
 *
 * ### What “equal” means, and why the analyzer is shared
 *
 * `ResolvedDocument` is a data class, so equality is structural over every field the phase
 * touches — pages, the node index, the theme, the resolved props, the carried handler
 * sequences — and [dev.rotalex.lutter.analysis.AnalysisResult.diagnostics] is compared
 * separately, because the reporting passes run in full on both sides and a witness that
 * resolved the same tree while reporting differently is still a defect.
 *
 * The analyzer is one instance for the whole loop, and that is the honest setup rather than a
 * shortcut: the carry seam is per-instance by contract (the schema revision and the overlay
 * cache), so a fresh analyzer per analysis would refuse every carry and this suite would
 * compare two full runs. The [Analyzer.analyze] call underneath is the same one the product
 * makes; it is the *reuse* the phase added, exercised as its callers exercise it.
 *
 * ### The three patch shapes, and the one the phase does not widen through this suite
 *
 * A patch is folded to its dirty set by `dirtySetFor` — the caller-side half §6.2:537 puts on
 * whoever owns both modules, which is this module (`DirtySets.kt` carries the contract). Its
 * fold has three inputs, and each is drawn here in turn by `seed % 3`:
 *
 *   * a **property edit** — the row §29:2038 is a claim about — which folds through
 *     `Patch.touchedNodes` and the ancestor closure;
 *   * a **subtree removal**, whose ids are gone from the document the index is built over and
 *     whose vacated parent is the fold's second input;
 *   * a **move**, drawn as a reposition **within one slot** (the shape §25.3's `index` is
 *     defined for), which is the fold's second input again and the one that changes the chain
 *     of the moved node's descendants without naming them.
 *
 * The three record kinds (`PutRecord`, `SetTheme`, `SetComponentVersion`) are not drawn:
 * `DirtySetFoldTest` pins them as inputs, and their widening — `recordsAffectAllNodes` makes
 * the dirty set `ALL` — turns this suite's claim into a comparison of two runs that both
 * recompute everything. Skipped here as vacuous, not as covered.
 *
 * ### Anti-vacuity
 *
 * A loop of 1,000 documents where the planner refused every edit would pass in silence, so
 * every refusal is counted, the total compared is floored, and each shape is floored
 * separately: a suite that stopped drawing moves because a rule changed would fail instead of
 * quietly narrowing what “the spine” means.
 */
internal class IncrementalEqualityTest {

    @Test
    fun `incremental re-analysis equals a from-scratch analysis over a thousand seeded documents`() {
        val view = codegenCorpusView
        val analyzer = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(view)
        val ids = SequentialIdGenerator("equality_n_")
        val compared = IntArray(SHAPES.size)
        var missed = 0

        for (seed in 1..DOCUMENTS) {
            val document = DocumentGenerator(view, seed.toLong()).generate().document
            val previous: ResolvedDocument = analyzer.analyze(document).resolved ?: run {
                // A generated document analysis cannot lower at all has no “previous
                // resolution” to carry, so there is nothing for this suite to compare. Counted
                // and floored rather than skipped quietly.
                missed++
                continue
            }

            val shape = seed % SHAPES.size
            val accepted: Pair<Command, Patch>? = firstAccepted(candidatesFor(shape, document, seed)) { command ->
                CommandPlanner.plan(command, document, view, ids)
            }
            if (accepted == null) {
                missed++
                continue
            }

            val patched = PatchApplier.apply(accepted.second, document)
            val dirty = dirtySetFor(accepted.second, patched)
            val incremental = analyzer.analyze(patched, dirty, previous)
            val full = analyzer.analyze(patched)

            assertEquals(
                full.diagnostics,
                incremental.diagnostics,
                "diagnostics differ between the carried and the full analysis (${SHAPES[shape]}, seed $seed)",
            )
            assertEquals(
                full.resolved,
                incremental.resolved,
                "resolution differs between the carried and the full analysis (${SHAPES[shape]}, seed $seed)",
            )
            compared[shape]++
        }

        val total = compared.sum()
        assertTrue(
            total >= MIN_TOTAL,
            "only $total of $DOCUMENTS seeded documents were compared ($missed had no planable edit)",
        )
        for (shape in SHAPES.indices) {
            assertTrue(
                compared[shape] >= MIN_PER_SHAPE,
                "${SHAPES[shape]} was compared only ${compared[shape]} times, so the spine no longer covers it",
            )
        }
    }
}

/** The three fold inputs this suite draws, one per line in the class KDoc. */
private val SHAPES: List<String> = listOf("a property edit", "a subtree removal", "a move within one slot")

private const val DOCUMENTS: Int = 1_000
private const val MIN_TOTAL: Int = 600
private const val MIN_PER_SHAPE: Int = 100

/**
 * The candidate commands of one [shape], rotated by [seed].
 *
 * Every candidate is a command the planner may still refuse — a root cannot be removed, an
 * icon pair's literal is a fixed shape — and [firstAccepted] walks the rotation until one
 * plans, so the draw is “the first legal edit of this shape from here”, deterministic per
 * seed.
 */
private fun candidatesFor(shape: Int, document: UiDocument, seed: Int): Sequence<Command> =
    when (shape) {
        0 -> propertyCandidates(document).rotatedBy(seed)
        1 -> document.nodes.ids().map { Command.RemoveNode(it) }.toList().rotatedBy(seed)
        else -> moveCandidates(document).rotatedBy(seed)
    }

/**
 * One candidate per constant a property holds that can be varied within its own type — the
 * same shape the benchmark row draws, enumerated whole so the rotation reaches a different
 * node per seed. A `Bool` flips (nothing to violate), a `Str` becomes another literal, an
 * `Int32` steps by one; everything else is left to the shapes that do not need the constant
 * replaced.
 */
private fun propertyCandidates(document: UiDocument): List<Command> {
    val candidates = mutableListOf<Command>()
    for (id in document.nodes.ids()) {
        val node = document.nodes[id] ?: continue
        for ((key, stored) in node.props) {
            val const = stored as? PropertyValue.Const ?: continue
            val varied = when (val value = const.value) {
                is Value.Bool -> Value.Bool(!value.v)
                is Value.Str -> Value.Str("equality")
                is Value.Int32 -> Value.Int32(value.v + 1)
                else -> null
            } ?: continue
            candidates += Command.SetProperty(id, key, PropertyValue.Const(varied))
        }
    }
    return candidates
}

/**
 * One candidate per child that is not already first in its slot: the reposition §25.3's
 * `index` defines, which the planner accepts without a cardinality question because the slot
 * does not change.
 */
private fun moveCandidates(document: UiDocument): List<Command> {
    val candidates = mutableListOf<Command>()
    for (parentId in document.nodes.ids()) {
        val parent = document.nodes[parentId] ?: continue
        for ((slot, children) in parent.slots) {
            children.forEachIndexed { index, child ->
                if (index == 0) return@forEachIndexed
                candidates += Command.MoveNode(child, parentId, slot, 0)
            }
        }
    }
    return candidates
}

private fun List<Command>.rotatedBy(seed: Int): Sequence<Command> {
    if (isEmpty()) return emptySequence()
    val start = seed % size
    return (drop(start) + take(start)).asSequence()
}

private fun firstAccepted(
    candidates: Sequence<Command>,
    plan: (Command) -> CommandResult,
): Pair<Command, Patch>? {
    for (command in candidates) {
        when (val result = plan(command)) {
            is CommandResult.Ok -> return command to result.patch
            is CommandResult.Rejected -> continue
        }
    }
    return null
}
