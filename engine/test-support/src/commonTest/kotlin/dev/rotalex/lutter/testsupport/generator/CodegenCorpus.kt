package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.ImportPolicy
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.PlannedFile
import dev.rotalex.lutter.codegen.planDocument
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import dev.rotalex.lutter.schema.types.TypeSpec

// The codegen corpus: a schema the generator can emit from, one run's worth of output, and
// the printer's rules restated as checks.
//
// ### Why this lives in the harness rather than in the consumer's test source set
//
// PLAN §36.2 wants `DeterminismTest` and `PermutationTest`, and both need three things at once:
// the seeded document generator (this module), `:engine:codegen` for the files, and
// `:engine:analysis` for the diagnostics a codegen call is handed.
//
// `:engine:codegen` cannot reach this module — `ModuleGraphRules.ALLOWED_IN_TESTS` has no line
// for it, and adding one is a policy change that belongs to whoever owns the rules, not to a
// work unit that needs a fixture. `:integration:generated-compile` is in the same position, which
// is the gap Phase 10's own notes already recorded. This module is the one place the build has
// already wired all three together: `build.gradle.kts` puts every engine module on its
// `commonMainApi`, and `commonTest` therefore sees the generator and the codegen path with no
// declared edge at all. So the tests live here and cost no change to the module graph. Moving
// them to `:engine:codegen` later is a one-line move plus the `ALLOWED_IN_TESTS` line that makes
// it legal.
//
// ### Why the schema filters three components
//
// `generatorSchema()` adds three components — `gen.SinglePanel`, `gen.Banner`, `gen.Probe` —
// that declare `intrinsic()` emission with no emitter behind them. `CodegenCoverage.check` runs
// from `KotlinGenerator`'s constructor and refuses a component whose intrinsic binding has a slot
// or an event, because the engine-owned path writes a node's properties and has nowhere to put
// either. `KotlinGenerator` therefore **throws** over that schema rather than refusing documents,
// which means the schema has to be narrowed before a corpus can be generated at all.
//
// Narrowing it here rather than in the generator is the right division: those three exist to give
// `DocumentGenerator` reach over `Cardinality.ZeroOrOne`, `ChildFilter.Only`, `ChildFilter.Except`
// and the value shapes no builtin property uses. That is a real coverage argument for the
// validity and round-trip suites, which never generate code, and a liability for this one.
//
// **What the filter costs, stated plainly.** A document this corpus builds can never contain a
// `ZeroOrOne` slot, an `Only` or `Except` filter, a list, url, map, icon, document-object or
// document-enum value, or a negative slot cardinality check. Every one of those is still covered
// by `DocumentGeneratorCoverageTest` over the unfiltered schema, so no coverage claim is lost
// overall — but this corpus does not exercise them, and a failure that only those shapes could
// produce will not be found here.
//
// ### Why a refusal is an outcome and not a gap
//
// `KotlinGenerator` answers a document it cannot emit with empty files and a diagnostic, never a
// throw. A document can refuse for reasons that have nothing to do with determinism — a
// typography token naming a role this engine does not spell is one — so an outcome carries both
// halves and a test compares whichever of them it has. Two runs that both refuse have to refuse
// *identically*, which is itself a determinism claim and would otherwise go unchecked.
//
// The census in `DeterminismTest` is what stops the rest from passing over a corpus of nothing.

/**
 * The schema the codegen corpus is generated and analysed against.
 *
 * One instance rather than a call per seed, for the reason `GeneratorFixtures.kt` gives: the
 * registry is immutable, and `Registry.all()` hands back a copy, so a caller cannot reach through
 * this one either. Building it once is what makes "two independent runs" cost two generators
 * rather than two schemas.
 */
internal val codegenCorpusView: SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    EmittableView(generatorSchema(), setOf(SINGLE_PANEL, BANNER, PROBE))

/** The package every corpus document is generated into. Arbitrary, and fixed on purpose. */
internal const val CORPUS_BASE_PACKAGE: String = "com.example.corpus"

/**
 * §16.3's header, restated here so the check does not have to reach into the generator.
 *
 * `KotlinGenerator.headerText()` writes the same string and holds no constant for it, so this is
 * a second copy in a test rather than a single source. The failure mode of that is bounded: if
 * the header changes, every file this corpus emits fails `every emitted file is a well-formed
 * source file` with the header named, which is a one-line fix in the test and not a silent
 * divergence. A named constant in `GenPlan` next to `HeaderPolicy.Minimal` would remove the copy.
 */
internal const val CORPUS_HEADER: String = "// Generated by Forge. Do not edit."

/** One emitted file, as a path and its text. */
internal class CorpusFile(val path: String, val content: String)

/**
 * One run of the whole pipeline over one document.
 *
 * Everything a determinism claim is made about, in the order it is produced: the canonical bytes,
 * what analysis said, what codegen said, and what codegen wrote. [planned] is the generator's own
 * §16.5 file list, kept so the layout can be checked against the planner rather than against a
 * list written out in a test — the `GenerationPlan` argument, applied to file paths.
 */
internal class CorpusOutcome(
    val seed: Long,
    val documentBytes: ByteArray,
    val analysisDiagnostics: List<String>,
    val codegenDiagnostics: List<String>,
    val files: List<CorpusFile>,
    val planned: List<PlannedFile>,
) {

    /** Whether codegen wrote anything. A refusal is a valid outcome; an empty one is not useful. */
    val emitted: Boolean get() = files.isNotEmpty()

    /** One line naming this run, for a failure message that has to say which seed failed. */
    fun describe(): String =
        "seed $seed: ${files.size} file(s) ${files.map { it.path }}, " +
            "${analysisDiagnostics.size} analysis and ${codegenDiagnostics.size} codegen diagnostic(s)"
}

/**
 * One document through analysis and codegen, and nothing kept that a comparison could not use.
 *
 * A **fresh** `KotlinGenerator` and a **fresh** `Analyzer` per call, deliberately. A reused
 * generator is supported — `KotlinGenerator.generate` clears its per-run scratch, and
 * `CodegenTest` proves it — but "two runs agree" is only evidence about determinism if the two
 * runs did not share an object that could carry state from one into the other. Reuse cannot see
 * a field that `generate` forgot to clear, because both runs start from the same cleared value;
 * two instances can.
 */
internal fun outcomeOf(seed: Long): CorpusOutcome = outcomeOf(DocumentGenerator(codegenCorpusView, seed).generate())

/** [generated] through the pipeline. See [outcomeOf] for why nothing is reused. */
internal fun outcomeOf(generated: GeneratedDocument): CorpusOutcome {
    val analysis = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(codegenCorpusView).analyze(generated.document)
    val resolved = analysis.resolved
        ?: return CorpusOutcome(
            generated.seed,
            encodedBytes(generated.document),
            analysis.diagnostics.map { renderDiagnostic(it) },
            emptyList(),
            emptyList(),
            emptyList(),
        )

    val plan = planDocument(resolved, CORPUS_BASE_PACKAGE)
    val result = KotlinGenerator(codegenCorpusView, CodegenOptions(CORPUS_BASE_PACKAGE))
        .generate(resolved, analysis.diagnostics)

    return CorpusOutcome(
        seed = generated.seed,
        documentBytes = encodedBytes(generated.document),
        analysisDiagnostics = analysis.diagnostics.map { renderDiagnostic(it) },
        codegenDiagnostics = result.diagnostics.map { renderDiagnostic(it) },
        files = result.files.files.map { CorpusFile(it.path, it.content) },
        planned = plan.files,
    )
}

/**
 * A diagnostic as one line, field by field in [DiagnosticSorter]'s own order.
 *
 * Rendered rather than compared as objects on purpose: `Diagnostic` is a data class, so `equals`
 * already answers the question, but a failure would print two `Diagnostic(...)` renderings that
 * are byte-identical — the exact trap `firstDifference` exists to avoid, and the reason the
 * arguments of `state.set` and the message of `ref.dangling` never show up in a report otherwise.
 * The fields that carry the location are printed even when null, as `-`, so a message cannot be
 * ambiguous about which half of a diagnostic moved.
 */
internal fun renderDiagnostic(diagnostic: Diagnostic): String {
    val at = diagnostic.location
    return listOf(
        diagnostic.severity.name,
        diagnostic.code.value,
        at.pageId?.value ?: ABSENT,
        at.componentDeclId?.value ?: ABSENT,
        at.nodeId?.value ?: ABSENT,
        at.property?.value ?: ABSENT,
        at.modifierIndex?.toString() ?: ABSENT,
        at.event?.value ?: ABSENT,
        at.path.joinToString(separator = "/").ifEmpty { ABSENT },
        diagnostic.message,
    ).joinToString(separator = "|")
}

/**
 * Where two byte arrays first differ, as an offset and the two runs around it.
 *
 * Copied from `RoundTripPropertyTest` for the reason its own copy of `firstDifference` records:
 * `GeneratorFixtures.kt` lives in a **test** source set, and no Gradle configuration publishes one
 * module's tests to another module's tests. `contentEquals` answers "different" and nothing
 * else, and two `ByteArray`s render as `[123, 45, 33, …]`.
 */
internal fun firstByteDifference(left: ByteArray, right: ByteArray): String {
    val shared = minOf(left.size, right.size)
    var at = 0
    while (at < shared && left[at] == right[at]) at += 1
    if (at == shared && left.size == right.size) {
        return "the two byte strings are equal, which this message should never be reached from"
    }

    val from = maxOf(0, at - CONTEXT_BYTES)
    val leftRun = left.copyOfRange(from, minOf(left.size, at + CONTEXT_BYTES)).decodeToString()
    val rightRun = right.copyOfRange(from, minOf(right.size, at + CONTEXT_BYTES)).decodeToString()
    return "first difference at offset $at, from $from:\n  left  = $leftRun\n  right = $rightRun"
}

/**
 * Every way one emitted file breaks a §16.3 or §16.5 rule, as text. Empty when it breaks none.
 *
 * Returned as a list rather than asserted inline so that one run reports every violation it found
 * instead of the first, which matters here more than usual: the rules are independent, and a file
 * that fails three of them is three facts a reader would otherwise learn one build at a time.
 *
 * ### What this can and cannot see
 *
 * It reads the bytes, so it sees anything expressible *in* them: a CR, a missing newline, an
 * unsorted import, an exponent. It cannot see anything expressible only in **how** they were
 * produced — a generator that reached `Random.Default` and happened to draw the same twice, or a
 * `Double.toString()` that a different platform spells differently and a different platform never
 * runs. Those are the halves of §36.2's claim that a byte-level check is structurally unable to
 * reach, and they are what `DeterminismTest`'s other cases and the committed golden of a later
 * work unit are for.
 */
internal fun formattingViolations(outcome: CorpusOutcome): List<String> {
    val violations = mutableListOf<String>()
    val paths = outcome.files.map { it.path }

    if (paths != paths.sorted()) violations += "file paths are not in ascending order: $paths"
    if (paths.size != paths.toSet().size) violations += "two emitted files share a path: $paths"

    for (file in outcome.files) violations += pathViolations(file) + contentViolations(file, outcome)
    return violations
}

/** §16.5's paths: relative, `/`-separated, and nothing a Windows or a shell would rewrite. */
private fun pathViolations(file: CorpusFile): List<String> {
    val violations = mutableListOf<String>()
    if (file.path.contains("\\")) {
        violations += "'${file.path}' carries a backslash; §16.5's layout joins with '/' on every platform"
    }
    if (file.path.startsWith("/")) violations += "'${file.path}' is absolute"
    if (file.path.contains(":")) violations += "'${file.path}' carries a drive separator"
    return violations
}

/**
 * The printer's own rules, restated.
 *
 * Every one of these is a rule §16.3 or §16.4 states and `KtPrinter` implements; none of them is
 * read back out of `KtPrinter`, because a check that shares the implementation's data shares its
 * bugs. The point of the file is that these lines are what the plan says, and a reader can check
 * them against the plan without running anything.
 */
private fun contentViolations(file: CorpusFile, outcome: CorpusOutcome): List<String> {
    val violations = mutableListOf<String>()
    val content = file.content

    if (content.isEmpty()) {
        violations += "'${file.path}' is empty"
        return violations
    }
    if (content.contains('\r')) {
        violations += "'${file.path}' carries a carriage return, so the line separator is not LF on this platform"
    }
    if (!content.endsWith("\n")) violations += "'${file.path}' does not end in a newline"
    if (content.endsWith("\n\n")) violations += "'${file.path}' ends in more than one newline"

    // The trailing newline leaves one empty tail, which is not a line.
    val lines = content.split('\n').dropLast(1)

    if (lines.firstOrNull() != CORPUS_HEADER) {
        violations += "'${file.path}' does not open with the minimal header: '${lines.firstOrNull()}'"
    }
    val declaredPackage = lines.getOrNull(1)?.removePrefix("package ")
    val expectedPackage = outcome.planned.firstOrNull { it.path == file.path }?.packageName
    if (declaredPackage != expectedPackage) {
        violations += "'${file.path}' declares package '$declaredPackage', the plan says '${expectedPackage}'"
    }
    if (lines.getOrNull(2) != "") violations += "'${file.path}' has no blank line after the package line"

    // The import block is the run of lines straight after that blank, and nothing else: a
    // declaration can never begin with the keyword, so the run is exactly the imports.
    val imports = lines.drop(3).takeWhile { it.startsWith("import ") }
    if (imports.isNotEmpty() && lines.getOrNull(3 + imports.size) != "") {
        violations += "'${file.path}' has no blank line after its import block"
    }
    violations += importViolations(file, imports, declaredPackage)
    return violations
}

/**
 * §16.4's import rules: no wildcards, no duplicates, no default package, nothing of the file's
 * own package, plain before aliased, each group sorted.
 *
 * The last two are restated rather than merely asserted to be sorted, because "sorted" alone does
 * not say *which* sort: `KtPrinter` returns `plain.sorted() + aliased.sorted()`, so an aliased
 * import that sorts before a plain one is still a violation. Rebuilding the expected list and
 * comparing is the only way to see that.
 */
private fun importViolations(file: CorpusFile, imports: List<String>, declaredPackage: String?): List<String> {
    val violations = mutableListOf<String>()
    val aliased = imports.filter { it.contains(" as ") }
    val plain = imports.filterNot { it.contains(" as ") }
    if (imports != plain.sorted() + aliased.sorted()) {
        violations += "'${file.path}' imports are not plain-then-aliased, each group sorted:\n" +
            imports.joinToString("\n")
    }
    if (imports.size != imports.toSet().size) violations += "'${file.path}' imports a name twice"

    for (line in imports) {
        val fqn = line.removePrefix("import ").substringBefore(" as ")
        if (fqn.endsWith(".*")) violations += "'${file.path}' uses a wildcard import: $line"
        val packageName = fqn.substringBeforeLast('.', missingDelimiterValue = "")
        if (packageName in ImportPolicy.DefaultPackages) {
            violations += "'${file.path}' imports '${fqn}', which Kotlin has in scope by default"
        }
        if (packageName == declaredPackage) {
            violations += "'${file.path}' imports '${fqn}', which is its own package"
        }
    }
    return violations
}

/**
 * Every number in one file that D1's canonical form could not have written.
 *
 * ### Why this is in this file and not only in the golden
 *
 * PLAN §5.4's canonical formatter assembles a number's text from a scaled integer and never
 * reaches `Float.toString()` or `Double.toString()`, and `CanonicalNumbers.kt` says exactly why:
 * the JVM delegates to `java.lang.Double.toString`, whose algorithm JDK 19 changed, and
 * Kotlin/Native, JS and Wasm each carry their own. A document format cannot rest on that.
 *
 * The emitter is the other end of the same rule, and this check is what asks whether it honours
 * it. `LiteralPrinter.floatText` builds a number's text by calling `Double.toString()` and
 * appending `".0"` when the result has no fractional part, so the canonical formatter is reached
 * from the model and **not** from codegen — `:engine:codegen` cannot reach it, because
 * `canonicalText` is `internal` to `:engine:model`.
 *
 * Two cases are illegal on any platform and one is illegal on some:
 *
 *  * an exponent (`1.0E-4.dp`), which is a `Double` literal and so has no `.dp` — a generated
 *    file that does not compile in the consumer's tree;
 *  * more than four fractional digits, which is the widened spelling of a value that has four;
 *  * a magnitude below `10^-3` at all, which is where a JVM's `Double.toString` chooses
 *    scientific notation. The generator's numbers are half-integers from `0.0` upwards, so this
 *    corpus never reaches any of the three — **which is the point of saying so here**: the check
 *    is what a future corpus or a widened generator would trip, and the gap in it is a coverage
 *    limit, not a pass.
 *
 * String literals are blanked before the scan. A document may legitimately carry the text
 * `"1.00000"` inside a string, and a number check that cannot tell a string from a literal is a
 * check that reports noise.
 */
internal fun nonCanonicalNumbers(outcome: CorpusOutcome): List<String> {
    val violations = mutableListOf<String>()
    for (file in outcome.files) {
        val code = file.content.replace(STRING_LITERAL, "\"\"")
        for (match in EXPONENT.findAll(code)) {
            violations += "'${file.path}' writes '${match.value}', which is scientific notation"
        }
        for (match in OVER_PRECISE.findAll(code)) {
            violations += "'${file.path}' writes '${match.value}', which carries more than four " +
                "fractional digits and is a widened float"
        }
    }
    return violations
}

/**
 * The three components this module adds, without the engine-owned binding.
 *
 * `SchemaView` rather than `Schema` because `Schema`'s constructor is `internal` to
 * `:engine:schema`, which is right — a schema is built through its builder — and because
 * `Analyzer` already builds its own overlay per call for `doc.*` types. The filtered registry is
 * rebuilt here rather than reused because `Registry` is an interface with no filtered view of its
 * own, and `RegistryBuilder.build` returns values in key order, so the rebuilt registry iterates
 * exactly as the original one did minus three entries.
 */
private class EmittableView(
    delegate: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    hidden: Set<ComponentType>,
) : SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {

    override val components: Registry<ComponentType, ComponentSpec> =
        RegistryBuilder<ComponentType, ComponentSpec>().apply {
            for (spec in delegate.components.all()) {
                if (spec.type !in hidden) register(spec.type, spec)
            }
        }.build()

    override val modifiers: Registry<ModifierType, ModifierSpec> = delegate.modifiers
    override val actions: Registry<ActionId, ActionSpec> = delegate.actions
    override val functions: Registry<FunctionId, FunctionSpec> = delegate.functions
    override val types: Registry<TypeId, TypeSpec> = delegate.types
}

/** Stands in for a diagnostic location field that is not set. */
private const val ABSENT: String = "-"

/** Bytes of context either side of a byte difference. Enough to read; not enough to bury. */
private const val CONTEXT_BYTES: Int = 16

/**
 * A double-quoted literal, backslash escapes included, as `LiteralPrinter.escape` writes them.
 *
 * Not a raw string, which would have to open with four consecutive quotes to carry the delimiter
 * inside itself; the escaped form is the same pattern with nothing about it to misread.
 */
private val STRING_LITERAL = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

/** `1.0E-4`: a magnitude a JVM's `Double.toString` spells in scientific notation. */
private val EXPONENT = Regex("""\d+(?:\.\d+)?[eE][+-]?\d+""")

/** `0.10000000149011612`: a `Float` at `0.1`, widened and printed. */
private val OVER_PRECISE = Regex("""(?<![\w.])\d+\.\d{5,}(?![\w.])""")