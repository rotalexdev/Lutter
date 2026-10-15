package dev.rotalex.lutter.testsupport.benchmark

import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.planDocument
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import dev.rotalex.lutter.testsupport.Golden
import dev.rotalex.lutter.testsupport.classpathGoldenSource
import dev.rotalex.lutter.testsupport.generator.CorpusExclusion
import dev.rotalex.lutter.testsupport.generator.codegenCorpusView
import dev.rotalex.lutter.testsupport.generator.firstByteDifference
import dev.rotalex.lutter.testsupport.generator.firstDifference
import dev.rotalex.lutter.testsupport.generator.renderDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// The structural half of PLAN §29, and the half that is an ordinary test.
//
// ### Why these are tests and the timings are not
//
// §29's table is five millisecond ceilings. A millisecond ceiling is not a fact about this
// repository — it is a fact about the machine, the load and the weather — so asserting one
// here would produce the failure mode that makes a benchmark suite get deleted: a red build
// on a busy CI runner that no engineer can act on, followed by a blanket raise of the
// thresholds until it goes green, at which point the suite asserts nothing at all.
//
// What *is* a fact about this repository is the other half of the same claim, and it is what
// these tests assert: **a document of §29's size is generatable, analysable, round-trippable
// and emittable at all.** A benchmark over a document the engine cannot read measures nothing,
// and that failure is silent — the harness would report five fast scenarios over a document
// the engine refused if `Analyzer` were allowed to hand back a null `ResolvedDocument` without
// anyone checking. So the composition is built here, on **both** targets, in the same
// `commonTest` source set as every other structural claim in this module, and it is built once
// per test compilation and shared.
//
// ### What running on both targets is and is not worth
//
// These tests execute on the Android host test and on Desktop, against a document of the same
// size built by the same code, and the last of them runs §29's five scenario bodies on each.
// That is real evidence that the rows are **common code** and therefore have one answer on
// both targets — which is half of §29's claim, and the half that is actually within reach of
// a test.
//
// It is **not** evidence about Android performance. §29's own header scopes the table to "JVM
// Desktop on a typical developer laptop", so no Android number was ever promised, and nothing
// in this repository runs on a device (`forge.kmp.library` turns host tests on and device
// tests off with a comment saying a CI emulator costs more than it would prove). An
// `androidUnitTest` runs on the host JVM, so its timings would be Desktop timings wearing an
// Android label. The honest statement is the narrow one: the work is the same work on both
// targets, and the timing is a property of the one target the plan named.
//
// ### What the composition cost, in one assertion
//
// `the composition actually composed` exists because the finding `LargeDocuments.kt` records —
// that the generator has no size knob and a ten-thousand-node document has to be assembled —
// is only true while the base document stays small. If a later `DocumentGenerator` grows one
// seed past ten thousand nodes, the loop below stops adding pages and this assertion says so,
// instead of the suite quietly measuring a single generated page and calling it §29's
// workload.

/**
 * The composed document, built once.
 *
 * One `lazy` rather than one build per test, and it is a file-level `val` rather than a member
 * of an `object` for the reason `NoMutableObjectStateTest` exists: `lazy` hands back the same
 * immutable document every time and nothing here mutates it.
 */
private val composed: ComposedDocument by lazy { composeLargeDocument() }

/** The workload over [composed], built once. Analysis and encoding are not free at this size. */
private val workload: BenchmarkWorkload by lazy { BenchmarkWorkload(composed) }

/**
 * The largest one-copy subtree the composition is still allowed to have.
 *
 * A bound and not an equality, because the base document is whatever seed [BENCHMARK_BASE_SEED]
 * draws and the point is the **order of magnitude**, not the draw. At the §29 target this admits
 * anything from a thousand pages upward, which is the claim `LargeDocuments.kt` and `refusalOf`
 * make in prose; the "~330 copies" that prose used to assert would need a copy of about thirty
 * nodes and is excluded outright.
 *
 * Widen it only alongside the prose it backs, and only with the reason — a bound that drifts
 * upward without a reason is how the next reader learns to skip it.
 */
private const val MAX_NODES_PER_COPY: Int = 10

class LargeDocumentTest {

    /**
     * The committed baseline's workload keys agree with the code that builds the document.
     *
     * The one part of a performance baseline that can be asserted without a machine, and the
     * part that makes a later comparison mean anything: a regression report has to be able to
     * say *what was measured*. If a baseline can claim ten thousand nodes while the composer
     * builds two hundred, every number derived from it is a number about a different document.
     */
    @Test
    fun `the committed baseline describes the workload the composer builds`() {
        val text = Golden.read(BenchmarkBaseline.RESOURCE, classpathGoldenSource())
        assertTrue(
            text.lines().any { it.trim() == BenchmarkBaseline.VERSION },
            "the committed baseline does not open with '${BenchmarkBaseline.VERSION}'",
        )

        val entries = BenchmarkBaseline.parse(text)
        assertEquals(BENCHMARK_BASE_SEED.toString(), entries["baseSeed"], "baseSeed")
        assertEquals(BENCHMARK_TARGET_NODES.toString(), entries["targetNodes"], "targetNodes")
        assertEquals(BENCHMARK_NODE_PREFIX, entries["nodeIdPrefix"], "nodeIdPrefix")
        assertEquals(BENCHMARK_PAGE_PREFIX, entries["pageIdPrefix"], "pageIdPrefix")
        assertEquals(BENCHMARK_BASE_PACKAGE, entries["basePackage"], "basePackage")

        for (scenario in Scenario.values()) {
            assertTrue(
                BenchmarkBaseline.targetKey(scenario) in entries,
                "the baseline carries no target for ${scenario.name}",
            )
            assertEquals(
                scenario.targetMillis.toString(),
                entries[BenchmarkBaseline.targetKey(scenario)],
                "§29's target for ${scenario.name} is not what the baseline records",
            )
        }

        // The invariant that makes a recorded timing meaningful, checked in the one direction
        // that has content: timings without a machine named are not comparable with anything,
        // so a baseline that carries timings and an `unrecorded` machine is refused. The other
        // direction — machine named, nothing recorded — is today's file and is fine.
        val anyRecorded = Scenario.values().any { BenchmarkBaseline.recordedNanos(entries, it) > 0L }
        if (anyRecorded) {
            val machine = entries["machine"]
            assertTrue(
                machine != null && machine.isNotEmpty() && machine != "unrecorded",
                "the baseline records timings but names no machine ('$machine'); nothing could be " +
                    "compared with them",
            )
        }
    }

    /**
     * The corpus is the schema minus exactly the components codegen cannot spell.
     *
     * ### What this row is really for
     *
     * Three of the four exclusions are named in `CodegenCorpus.kt` and cannot surprise anybody.
     * The fourth is chosen by a **rule** — a component whose spec declares an event its
     * `CodegenBinding.ComposeCall` does not bind — and a rule is free to widen on its own the
     * next time the schema gains a component. That is what it is for, and it is also how a
     * benchmark goes quietly blind: eight components become seven, the corpus gets a little
     * easier, and the table keeps its shape and its milliseconds.
     *
     * So the rule is not allowed to be silent. This row is the whole of its accountability, and
     * it is worth being blunt about what a failure means: **a fifth exclusion is not a defect in
     * the filter, it is information about `:engine:codegen`** — another component whose event
     * nothing can spell. The right response is to report it, in whatever row it deserves, and
     * then to write its name down here. Excluding a component because codegen cannot emit it is
     * correct once and corrosive twice; a pile of them is a corpus that stopped testing anything
     * while continuing to produce numbers.
     *
     * ### What §29's codegen row means with this component absent
     *
     * `codegen < 300 ms` is now a real measurement — before this exclusion the generator refused
     * the whole composed document and emitted nothing, so the row was timing a refusal — and it
     * is a measurement over **eight** components, not over the schema. It covers value params,
     * `ValueEmit.Cases` arrivals, trailing slot lambdas, the intrinsic slot outlet, `doc.*`
     * instances, the modifier set and the six actions. It does **not** cover a handler receiving
     * a declared event argument: both components that declared one are excluded here, and
     * `m3.Button`'s `onClick` declares none, so nothing in this corpus reads `RefTarget.EventArg`.
     * A reader must not take the number as a claim about the whole schema.
     */
    @Test
    fun `the corpus excludes exactly the components codegen cannot spell`() {
        assertEquals(
            mapOf(
                ComponentType("gen.SinglePanel") to CorpusExclusion.IntrinsicWithoutEmitter,
                ComponentType("gen.Banner") to CorpusExclusion.IntrinsicWithoutEmitter,
                ComponentType("gen.Probe") to CorpusExclusion.IntrinsicWithoutEmitter,
                ComponentType("m3.TextField") to CorpusExclusion.EventWithoutBinding,
            ),
            codegenCorpusView.exclusions,
            "the corpus no longer filters exactly the components CodegenCorpus names.\nIt filters:" +
                codegenCorpusView.exclusions.entries.joinToString(separator = "\n") { entry ->
                    "\n  ${entry.key} — ${entry.value.name}: ${entry.value.reason}"
                } +
                "\n\nA component that appeared here was excluded because codegen cannot spell it, " +
                "which is a finding about :engine:codegen to report rather than a filter to widen.",
        )
    }

    /**
     * A document of §29's size is built, and the building is the composition this suite claims.
     *
     * The assertion that fails is the finding, if it ever stops being true.
     */
    @Test
    fun `the composition reaches the node count and was a composition`() {
        assertTrue(
            composed.nodes >= BENCHMARK_TARGET_NODES,
            "the composed document holds ${composed.nodes} node(s), fewer than §29's $BENCHMARK_TARGET_NODES",
        )
        assertTrue(
            composed.addedPages > 0,
            "the base document for seed $BENCHMARK_BASE_SEED already holds ${composed.nodes} node(s), " +
                "so no page was copied and §29's workload is one generated page rather than a composition",
        )
        // The page count is not a number anybody chose: it is §29's target divided by the size of
        // the subtree one copy contributes, and that subtree is a screen and whatever is under it.
        // Asserting the divisor is what keeps the prose that explains the page count from rotting
        // into a second guess — `refusalOf`'s KDoc below estimated three hundred copies once, and
        // was wrong by more than an order of magnitude, with nothing in the build to say so.
        assertTrue(
            composed.nodesPerCopy in 1..MAX_NODES_PER_COPY,
            "each of the ${composed.addedPages} copies contributed ${composed.nodesPerCopy} node(s) " +
                "to a ${composed.baseNodes}-node base, and 'a handful of nodes per copy' — which " +
                "is what makes §29's ${BENCHMARK_TARGET_NODES} nodes a document of thousands of " +
                "pages rather than hundreds — is no longer what the composer does",
        )
        assertEquals(
            composed.nodes,
            composed.document.nodes.ids().count(),
            "NodeTable.ids() and NodeTable.size disagree, which is §6.2's ordering contract broken",
        )
    }

    /** §36.1's round-trip property, over a document of §29's size rather than a generated one. */
    @Test
    fun `a document of this size analyses clean and round-trips byte for byte`() {
        val document = workload.document
        val errors = workload.analysis.diagnostics.filter { it.severity == Severity.Error }
        assertTrue(
            errors.isEmpty(),
            "analysis of the composed document reported ${errors.size} error(s):\n" +
                errors.joinToString("\n") { it.code.value + " " + it.message },
        )

        val decoded = JsonDocumentCodec.decode(workload.bytes).document
        assertNull(
            firstDifference(document, decoded),
            "decoding the canonical bytes did not give back the composed document",
        )

        val reencoded = JsonDocumentCodec.encode(decoded)
        if (!workload.bytes.contentEquals(reencoded)) {
            fail(
                "encode(decode(encode(d))) did not reproduce encode(d):\n" +
                    firstByteDifference(workload.bytes, reencoded),
            )
        }
    }

    /**
     * §29's codegen row is a row about a document of this size, so it is answered here rather
     * than only on a clock.
     *
     * The file list is compared against §16.5's own planner rather than against a count a
     * person wrote down, which is `CodegenCorpus`'s argument applied: a check that shares the
     * implementation's data shares its bugs, and `planDocument` is the specification here.
     */
    @Test
    fun `a document of this size emits the files its own plan names`() {
        val resolved = workload.resolved
            ?: fail(
                "analysis produced no ResolvedDocument, so there is nothing to generate:\n" +
                    workload.analysis.diagnostics.joinToString("\n") { it.code.value + " " + it.message },
            )

        val options = CodegenOptions(BENCHMARK_BASE_PACKAGE)
        val planned = planDocument(resolved, BENCHMARK_BASE_PACKAGE)
        val emitted = KotlinGenerator(codegenCorpusView, options).generate(resolved, workload.analysis.diagnostics)

        assertTrue(
            emitted.files.files.isNotEmpty(),
            "codegen emitted no file for a ${composed.nodes}-node document over ${composed.pages} " +
                "page(s), so §29's codegen row would be measuring a refusal rather than codegen.\n" +
                refusalOf(emitted.diagnostics, workload.analysis.diagnostics.size),
        )
        assertEquals(
            planned.files.size,
            emitted.files.files.size,
            "codegen emitted ${emitted.files.files.size} file(s) and §16.5 plans ${planned.files.size}",
        )
        assertEquals(
            planned.files.map { it.path },
            emitted.files.files.map { it.path },
            "codegen's file list is not the one planDocument produced",
        )
    }

    /**
     * Every row of §29's table runs to completion **on this target**.
     *
     * The one thing the split of this suite across `commonTest` and `desktopTest` buys, and it
     * is worth stating precisely because it is narrower than it sounds. The clock is JVM-only
     * and always was; what this test does is run the five bodies themselves on the Android
     * host test too, over the same ten-thousand-node document. So the claim is not "Android is
     * fast" — it is "**§29's five rows are the same work on both targets**", which is the half
     * of §29's promise that is inside a test's reach and the half that a JVM-only harness
     * would have left untested.
     *
     * Each body is run once and nothing is asserted about its cost, which is the point: a test
     * with no millisecond in it cannot be red because a runner was busy.
     */
    @Test
    fun `every row of the performance table runs to completion on this target`() {
        val bodies = scenarioBodies(workload)
        assertEquals(
            Scenario.values().size,
            bodies.size,
            "§29's table has five rows and the scenario list has ${bodies.size}",
        )
        for (body in bodies) {
            try {
                body.run()
            } catch (failure: Throwable) {
                fail("${body.scenario.name} ('${body.scenario.title}') failed on this target:\n$failure")
            }
        }
    }
}

/**
 * Why an empty result is empty: the codes the generator refused with, and one located example each.
 *
 * §16.7 makes "no files" and "the generator said no" the same event, so the diagnostics are the
 * only thing in a `CodegenResult` that tells a refusal apart from an absence — and the codegen row
 * asserts on the files alone, which is how a refusal used to read as "emitted nothing" and cost a
 * reader the one fact they needed. Nothing here is a count of anything the test then checks; it is
 * the message, and it exists so the failure says what happened.
 *
 * [incoming] is how many diagnostics `generate` was handed, which it returns back alongside its
 * own: the unfiltered list would name every analysis finding a second time, and the analysis
 * findings are not what refused. `CodegenCorpus` drops the same prefix for the same reason.
 *
 * One example per code rather than one per finding, because the composer copies a single page
 * until the table holds ten thousand rows, and one copy is a screen and whatever is under it — a
 * handful of nodes, so the loop runs **over a thousand times** and a refusal that page already
 * carried arrives once per copy. This comment said "three hundred", which was wrong by more than
 * an order of magnitude and which nothing in the build was able to contradict; `the composition
 * reaches the node count and was a composition` now asserts the divisor that decides it. A
 * message is not a dump either way. The per-code count beside each example is what keeps a
 * one-example line from reading as a single occurrence.
 */
private fun refusalOf(diagnostics: List<Diagnostic>, incoming: Int): String {
    val errors: List<Diagnostic> = diagnostics.drop(incoming).filter { it.severity == Severity.Error }
    if (errors.isEmpty()) {
        return "codegen recorded no error of its own and still wrote nothing; §16.7 leaves no " +
            "other reading for an empty result than a breach in the generator"
    }
    val byCode: Map<String, List<Diagnostic>> = errors.groupBy { it.code.value }
    return buildString {
        append("codegen refused with ")
        append(byCode.size)
        append(" code(s) over ")
        append(errors.size)
        append(" finding(s):\n")
        for ((code, found) in byCode) {
            append("  ").append(code).append(" x").append(found.size).append('\n')
            append("    ").append(renderDiagnostic(found.first())).append('\n')
        }
    }
}