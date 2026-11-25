package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.ComposeResourceStrategy
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.Patch
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.testsupport.generator.codegenCorpusView

// §29's six rows, as six calls with nothing in them but the call.
//
// ### Why the timed unit is a lambda and not a number
//
// Nothing in this file measures anything, and that is the whole design. A clock is the one
// thing in a benchmark that cannot be written in `commonMain`, so a suite that carried its own
// timing would have to declare an `expect fun` and two `actual` files before it could say a
// word — and §21.3 does list "benchmark clocks" in its allow-list, so the door is open. It is
// left shut anyway: the clock is four lines and it lives on the JVM side, while **what is being
// clocked** is engine code that is the same on every target and deserves a definition both
// targets share. Split it this way and the Android compilation really does execute these six
// bodies, against a real ten-thousand-node document, on every `check` — not the timing, but the
// work the timing is a claim about.
//
// ### The four scenarios that are separable, the fifth that needed a decision, and the sixth
// that waited for its mechanism
//
//   * Decode and encode are [JsonDocumentCodec] calls and nothing else. §29's "incl. migration
//     check" is not extra work this file has to add: `decode` reads the envelope, compares
//     `schemaVersion`, runs the migration chain and rejects a future payload before the document
//     is built, so timing the call times the check.
//   * Analysis is [Analyzer.analyze] on its own, with a **fresh** analyzer per call for
//     `CodegenCorpus`'s reason: two runs that share an object cannot see a field `analyze`
//     forgot to clear.
//   * Codegen needs §29's "analysis reused", so the resolved document is prepared once, in
//     [BenchmarkWorkload], outside the timed body, and the body hands that same instance to the
//     generator every time.
//   * **"Single command (patch + new doc)" is the one row that is two calls**, and §29's own
//     wording is what says so: a *patch* and a *new document* are [CommandPlanner.plan]'s
//     output and [PatchApplier.apply]'s. Timing only the first would have measured a map
//     lookup, and timing only the second would have measured a patch nobody planned.
//   * **"Incremental re-analysis after a single-property edit" is the row the plan marked
//     "(Phase post-MVP)"**, and it could not be honest before the mechanism existed: it is
//     §29.1's memoized carry, timed as §29's editing loop — the document as it was analysed
//     once in preparation, one legal single-property edit prepared as a planned-and-applied
//     patch, and the re-analysis of the result, dirty set folded from the patch, carried
//     against the prepared resolution. Unlike the analysis row, this one shares an analyzer on
//     purpose: the reuse seam is per-instance by contract (the schema revision, the overlay
//     cache), so a fresh analyzer per timed call would refuse every carry and measure a full
//     run wearing this row's label.

/** One row of §29's table: what it is, and the ceiling §29 states for it. */
internal enum class Scenario(

    /** §29's own words, so a report quotes the plan rather than paraphrasing it. */
    val title: String,

    /** §29's target, in milliseconds. See [BenchmarkBaseline] for what it is and is not. */
    val targetMillis: Long,
) {

    /** §29: "Decode JSON → `UiDocument` (incl. migration check)", `< 250 ms`. */
    Decode("Decode JSON -> UiDocument (incl. migration check)", 250),

    /** §29: "Encode canonical JSON", `< 200 ms`. */
    Encode("Encode canonical JSON", 200),

    /** §29: "Full analysis", `< 200 ms`. */
    Analyze("Full analysis", 200),

    /** §29: "Full codegen (analysis reused)", `< 300 ms`. */
    Codegen("Full codegen (analysis reused)", 300),

    /** §29: "Single command (patch + new doc)", `< 15 ms`. */
    Command("Single command (patch + new doc)", 15),

    /**
     * §29: "Incremental re-analysis after a single-property edit", `< 10 ms (Phase post-MVP)`.
     * The phase 13 row, collected now that §29.1's memoization exists to be timed.
     */
    Incremental("Incremental re-analysis after a single-property edit", 10),
}

/**
 * One measured call, and nothing else.
 *
 * A lambda rather than a `fun interface` because there is exactly one implementation of it and
 * it is written at the call site: a named type here would add a layer whose only content is a
 * `run()`, and the six bodies are three lines each.
 */
internal class ScenarioBody(

    /** Which row of §29 this is. */
    val scenario: Scenario,

    /** The call. Everything the measurement needs is in here and nothing else is timed. */
    val run: () -> Unit,
)

/**
 * Everything the six bodies are handed, prepared once.
 *
 * Prepared rather than timed on purpose: §29's codegen row says "analysis reused", and a
 * benchmark that quietly re-ran analysis inside the codegen body would be measuring the wrong
 * row while reporting it as the right one. Preparing here is also where the workload's identity
 * is fixed — the bytes the decode row decodes and the document the rest walk are the same
 * document the structural suite walks, so a change to the composition moves every number.
 */
internal class BenchmarkWorkload(composed: ComposedDocument) {

    /** The composed document. */
    val document: UiDocument = composed.document

    /** The canonical bytes of [document], which is what the decode row starts from. */
    val bytes: ByteArray = JsonDocumentCodec.encode(document)

    /** Analysis of [document], run once here so the codegen row can reuse it. */
    val analysis: AnalysisResult =
        Analyzer<ActionSpec, FunctionSpec, TypeSpec>(codegenCorpusView).analyze(document)

    /**
     * The lowered document [analysis] produced.
     *
     * Nullable because [AnalysisResult.resolved] is: a document with an error diagnostic has
     * no lowered form, and a benchmark that emitted code for one would be timing a refusal.
     * [scenarioBodies] refuses on this rather than measuring nothing.
     */
    val resolved: ResolvedDocument? get() = analysis.resolved

    /**
     * §29's single command, as a command that has not been planned yet.
     *
     * A [Command.SetProperty] on the document's first node in `NodeTable.ids()` order, which is
     * ascending by id (§6.2). **The node is taken from the document rather than named**, so the
     * command is legal whatever the generator drew: `CommandPlanner.planSetProperty` checks that
     * the node exists and nothing else — the key is not consulted against the component's spec,
     * because §25.3 names two policies and both of them are about slots.
     *
     * The key is `benchmarkTouched`, and it is **not** a property any registered component
     * declares. It cannot be namespaced: `PropertyKey` validates against a **simple** grammar,
     * `^[A-Za-z0-9_]{1,64}$`, so a dot in it is a construction failure rather than an
     * undeclared property. Re-analysing the edited document would therefore report it as
     * `prop.unknown`, and nothing here re-analyses, so that finding is never produced. A
     * benchmark editing a *real* property would take a different planner path — one that
     * consults the spec — so the cheaper path is what is timed, and saying so is worth more
     * than the microsecond it saves a reader from wondering about.
     */
    val edit: Command = Command.SetProperty(
        node = document.nodes.ids().first(),
        key = PropertyKey("benchmarkTouched"),
        value = PropertyValue.Const(Value.Str("touched")),
    )

    /**
     * The incremental row's edit: one single-property change, on a property the document's own
     * schema declares — the legal edit §29's row is a claim about.
     *
     * The node and the key are **taken from the document rather than named**, for the same
     * reason [edit]'s node is: the command is legal whatever the generator drew. The selection
     * walks `ids()` order for the first constant it can vary within its own type, in
     * constraint-cheapest order — a [Value.Bool] flips (a Bool has no range and no vocabulary,
     * so flipping it violates nothing), a [Value.Str] becomes another literal, and a
     * [Value.Int32] steps by one. That order is the lesson of the first green run: the
     * document's string constants do not all travel under a `TypeRef.Str` prop — an icon pair
     * or a range-bound count can hold a constant a naive replacement refuses — so the row
     * bets its preparation on the seed's draw as little as it can. [CommandPlanner.plan] still
     * consults the spec for a declared key, and a plan that refuses — an icon name, an
     * enum-backed literal — fails the preparation loudly instead of timing a refusal.
     */
    val incrementalEdit: Command = run {
        // The elvis does the nullability: the selection hands back a target or preparation
        // fails, so the declared type is the non-null shape and the uses below need no guard.
        val target: Triple<NodeId, PropertyKey, Value> = document.nodes.ids().firstNotNullOfOrNull { id ->
            val node = document.nodes[id] ?: return@firstNotNullOfOrNull null
            node.props.entries.firstNotNullOfOrNull { (key, propValue) ->
                val const = propValue as? PropertyValue.Const ?: return@firstNotNullOfOrNull null
                val varied = when (val value = const.value) {
                    is Value.Bool -> Value.Bool(!value.v)
                    is Value.Str -> Value.Str("incremental")
                    is Value.Int32 -> Value.Int32(value.v + 1)
                    else -> null
                } ?: return@firstNotNullOfOrNull null
                Triple(id, key, varied)
            }
        } ?: error(
            "the composed document stores no Bool, Str or Int32 constant on any node, so §29's " +
                "incremental row has no legal single-property edit to measure",
        )
        Command.SetProperty(
            node = target.first,
            key = target.second,
            value = PropertyValue.Const(target.third),
        )
    }
}

/**
 * Where every body's result is written.
 *
 * Without it, a call whose result is unused is a call a JIT may eliminate, and an eliminated
 * call times as nothing — which is not a benchmark that under-reports, it is a benchmark that
 * reports zero for free.
 *
 * **An object with a `var`, not a captured local**, and the reason is the elimination this
 * exists to prevent. A local captured by six lambdas is only reachable through the lambdas,
 * and a compiler is entitled to decide that nobody ever reads it; a field on an object that
 * six closures hold cannot be reasoned away the same way, because the object itself escapes.
 * The field is never read — that is what a black hole is — and nothing here needs it to be.
 */
private class Blackhole {

    /** The last result any body produced. Never read. */
    var value: Any? = null
}

/**
 * The five rows of §29's table, in the table's order, ready to be clocked — plus the
 * post-MVP sixth, whose preparation is the editing loop the row is a claim about: the
 * document as it was analysed once, one legal single-property edit planned and applied, the
 * dirty set folded from the patch ([dirtySetFor] carries the fold), and the previous
 * resolution from the **same analyzer** the timed calls use.
 *
 * ### The ids generator is shared, and that is not a defect
 *
 * [CommandPlanner.plan] takes an `IdGenerator` because [Command.DuplicateNode] and
 * [Command.WrapNode] need one (§25.3 says so). Neither [Command.SetProperty] nor the
 * incremental edit needs one, so this one never advances, and sharing it keeps the rows
 * reproducible.
 *
 * @throws IllegalStateException if analysis produced no [ResolvedDocument], which is the one
 *   precondition §29's table cannot be measured against, or if the incremental row's own edit
 *   was refused, or if its re-analysis resolves nothing — all three are refusals this table
 *   cannot be measured across, and each is better as a failure than as timings over a
 *   document the engine refused.
 */
internal fun scenarioBodies(workload: BenchmarkWorkload): List<ScenarioBody> {
    val resolved: ResolvedDocument = workload.resolved
        ?: error(
            "analysis of the composed document produced no ResolvedDocument, so §29's codegen " +
                "row has nothing to measure: " +
                workload.analysis.diagnostics.joinToString("; ") { it.code.value },
        )

    val view = codegenCorpusView
    val options = CodegenOptions(
        BENCHMARK_BASE_PACKAGE,
        resources = ComposeResourceStrategy("dev.rotalex.lutter.testsupport.resources"),
    )
    val ids = SequentialIdGenerator(BENCHMARK_NODE_PREFIX)
    val hole = Blackhole()

    // The incremental row's preparation, in §29's editing-loop order. The previous resolution
    // comes from the same analyzer the timed body uses — the reuse seam is per-instance by
    // contract (the schema revision, the overlay cache), so a fresh analyzer per call would
    // refuse every carry and time a full run wearing this row's label. The edit is planned
    // and applied here rather than in the timed body, because the row times the re-analysis,
    // not the command, and a dry run of the timed call refuses a result with no resolution
    // instead of letting the harness clock one. The whole previous result is kept, not just its
    // resolution: the reporting passes carry the diagnostics for the nodes outside the dirty set,
    // which is the half of the row the previous run also produced.
    val incrementalAnalyzer = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(view)
    val previousAnalysis: AnalysisResult = incrementalAnalyzer.analyze(workload.document)
    val previous: ResolvedDocument = checkNotNull(previousAnalysis.resolved) {
        "the composed document resolves nothing, so §29's incremental row has nothing to " +
            "carry: " +
            workload.analysis.diagnostics.joinToString("; ") { it.code.value }
    }
    val planned: Patch = when (val plan = CommandPlanner.plan(workload.incrementalEdit, workload.document, view, ids)) {
        is CommandResult.Ok -> plan.patch
        is CommandResult.Rejected -> error(
            "the benchmark's own single-property edit was refused: " +
                plan.reasons.joinToString("; ") { it.code.value + " " + it.message },
        )
    }
    val patched = PatchApplier.apply(planned, workload.document)
    val dirty = dirtySetFor(planned, patched)
    checkNotNull(incrementalAnalyzer.analyze(patched, dirty, previous, previousAnalysis.diagnostics).resolved) {
        "the incremental row's own edit produced a document analysis resolves nothing, so the " +
            "row would time a refusal: " +
            incrementalAnalyzer.analyze(patched, dirty, previous, previousAnalysis.diagnostics)
                .diagnostics
                .joinToString("; ") { it.code.value + " " + it.message }
    }

    return listOf(
        ScenarioBody(Scenario.Decode) { hole.value = JsonDocumentCodec.decode(workload.bytes) },
        ScenarioBody(Scenario.Encode) { hole.value = JsonDocumentCodec.encode(workload.document) },
        ScenarioBody(Scenario.Analyze) {
            hole.value = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(view).analyze(workload.document)
        },
        ScenarioBody(Scenario.Codegen) {
            hole.value = KotlinGenerator(view, options).generate(resolved, workload.analysis.diagnostics)
        },
        ScenarioBody(Scenario.Command) {
            hole.value = when (val planned = CommandPlanner.plan(workload.edit, workload.document, view, ids)) {
                is CommandResult.Ok -> PatchApplier.apply(planned.patch, workload.document)
                is CommandResult.Rejected -> error(
                    "the benchmark's own edit was refused: " +
                        planned.reasons.joinToString("; ") { it.code.value + " " + it.message },
                )
            }
        },
        ScenarioBody(Scenario.Incremental) {
            hole.value = incrementalAnalyzer.analyze(patched, dirty, previous, previousAnalysis.diagnostics)
        },
    )
}