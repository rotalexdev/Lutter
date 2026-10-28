package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.editing.command.Command
import dev.rotalex.lutter.editing.command.CommandPlanner
import dev.rotalex.lutter.editing.command.CommandResult
import dev.rotalex.lutter.editing.patch.PatchApplier
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SequentialIdGenerator
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.testsupport.generator.codegenCorpusView

// §29's five rows, as five calls with nothing in them but the call.
//
// ### Why the timed unit is a lambda and not a number
//
// Nothing in this file measures anything, and that is the whole design. A clock is the one
// thing in a benchmark that cannot be written in `commonMain`, so a suite that carried its own
// timing would have to declare an `expect fun` and two `actual` files before it could say a
// word — and §21.3 does list "benchmark clocks" in its allow-list, so the door is open. It is
// left shut anyway: the clock is four lines and it lives on the JVM side, while **what is being
// clocked** is engine code that is the same on every target and deserves a definition both
// targets share. Split it this way and the Android compilation really does execute these five
// bodies, against a real ten-thousand-node document, on every `check` — not the timing, but the
// work the timing is a claim about.
//
// ### The four scenarios that are separable, and the fifth that needed a decision
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
}

/**
 * One measured call, and nothing else.
 *
 * A lambda rather than a `fun interface` because there is exactly one implementation of it and
 * it is written at the call site: a named type here would add a layer whose only content is a
 * `run()`, and the five bodies are three lines each.
 */
internal class ScenarioBody(

    /** Which row of §29 this is. */
    val scenario: Scenario,

    /** The call. Everything the measurement needs is in here and nothing else is timed. */
    val run: () -> Unit,
)

/**
 * Everything the five bodies are handed, prepared once.
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
}

/**
 * Where every body's result is written.
 *
 * Without it, a call whose result is unused is a call a JIT may eliminate, and an eliminated
 * call times as nothing — which is not a benchmark that under-reports, it is a benchmark that
 * reports zero for free.
 *
 * **An object with a `var`, not a captured local**, and the reason is the elimination this
 * exists to prevent. A local captured by five lambdas is only reachable through the lambdas,
 * and a compiler is entitled to decide that nobody ever reads it; a field on an object that
 * five closures hold cannot be reasoned away the same way, because the object itself escapes.
 * The field is never read — that is what a black hole is — and nothing here needs it to be.
 */
private class Blackhole {

    /** The last result any body produced. Never read. */
    var value: Any? = null
}

/**
 * The five rows of §29's table, in the table's order, ready to be clocked.
 *
 * ### The ids generator is shared, and that is not a defect
 *
 * [CommandPlanner.plan] takes an `IdGenerator` because [Command.DuplicateNode] and
 * [Command.WrapNode] need one (§25.3 says so). [Command.SetProperty] does not, so this one
 * never advances, and sharing it keeps the row reproducible.
 *
 * @throws IllegalStateException if analysis produced no [ResolvedDocument], which is the one
 *   precondition §29's table cannot be measured against. Failing here beats five rows of
 *   timings over a document the engine refused.
 */
internal fun scenarioBodies(workload: BenchmarkWorkload): List<ScenarioBody> {
    val resolved: ResolvedDocument = workload.resolved
        ?: error(
            "analysis of the composed document produced no ResolvedDocument, so §29's codegen " +
                "row has nothing to measure: " +
                workload.analysis.diagnostics.joinToString("; ") { it.code.value },
        )

    val view = codegenCorpusView
    val options = CodegenOptions(BENCHMARK_BASE_PACKAGE)
    val ids = SequentialIdGenerator(BENCHMARK_NODE_PREFIX)
    val hole = Blackhole()

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
    )
}