package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.ImportPolicy
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.PlannedFile
import dev.rotalex.lutter.codegen.planDocument
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.CodegenBinding
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
// ### Why the schema filters the three intrinsic components
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
// ### Why the schema also filters every component whose event has no binding
//
// The rule above drops a component by **name**, and it can: three types, one failing mode, a
// throw at construction. The one below cannot, and the difference is the reason it is written as
// a predicate.
//
// **The class is: a component whose spec declares an event that its `CodegenBinding.ComposeCall`
// does not bind.** Today that class has exactly one member, and it is a builtin.
// `TextFieldSpec` declares `event(onValueChange)` and its compose call binds only
// `param("value", …)` — `ButtonSpec`, the only other spec that declares an event at all, binds
// it as `event(onClick, "onClick")`. So the model's handler for `onValueChange` has somewhere to
// go and the generated file has nowhere to put it: `KotlinGenerator`'s `unboundEvent` check
// refuses every node of that type carrying a handler, as `codegen.no_binding` and the message
// `Event 'onValueChange' has no parameter to receive it`.
//
// **That is a real gap in `:engine:codegen`, and it is not this work unit's to close.**
// `CodegenCoverage.check` is §4.5's build-time fail-fast and `callProblem` validates **one
// direction** of the spec/binding pair: it refuses an `EventBinding` the spec does not declare
// (`event '…' is not declared`) and never asks whether every declared event is bound. A
// component in this class therefore *passes* the fail-fast, and refuses later — once per node,
// at document time, with a code that names neither the spec nor the binding. That asymmetry is
// the defect, it belongs to `:engine:codegen` as its own unit and its own row, and this file
// only declines to measure around it. Nothing here fixes it and nothing here reports it as
// fixed.
//
// **Why a rule and not a second name.** Three reasons, and only the third is about style.
//
//   * A name list is a filter that grows by one entry per defect, and the next defect is found
//     by whoever hits it. A rule catches the same defect on any component, including a builtin
//     added after this was written, without anyone remembering to come back here.
//   * A rule is harder to get wrong than it looks: it is one `none` over two declarations, and
//     it is checked against the same two lists `CodegenCoverage` reads.
//   * A rule can change the corpus **on its own**, which is the whole risk. `EmittableView`
//     therefore records what it removed in [EmittableView.exclusions] and `LargeDocumentTest`
//     asserts that record is exactly the four types expected. Excluding by name needs no such
//     check, because a name list only changes when a person edits it; a predicate can widen
//     silently the next time the schema gains a component, and a benchmark that quietly stopped
//     testing something is worse than a red one.
//
// **What the corpus still covers, stated plainly.** The pool is eight components — `core.Column`,
// `core.Row`, `core.Box`, `core.Spacer`, `core.SlotOutlet`, `m3.Text`, `m3.Button`, `m3.Card` —
// and between them they cover every path the schema has that still works: value parameters, a
// `ValueEmit.Cases` arrival pattern on both layouts, a trailing slot lambda, an `Intrinsic`
// component and the `doc.*` document instances, plus the whole §31.2 modifier set and all six
// §11.4 actions. `LargeDocumentTest` pins the size of the composed document this runs over and
// names what is missing from it, so §29's codegen row finally measures codegen instead of a
// refusal — and the reader of that row can see how much of the schema it covers.
//
// **And the honest part is the loss, which is larger than it looks.** Before this filter the
// corpus emitted **no files at all** on that document — §16.7 makes every error empty the result
// — so nothing was covered before either; the exclusion does not take coverage away from a
// working corpus, it is what lets one start. What remains unreachable afterwards is narrower
// and worth naming precisely: **no handler in this corpus receives a declared event argument,
// so `RefTarget.EventArg` is no longer read by anything generated here.** Both components that
// declared an argument are the two excluded for unspellable bindings — `m3.TextField`'s
// `value` and `gen.Probe`'s `probeValue` — and the one remaining event, `m3.Button`'s
// `onClick`, declares none, so its lambda is emitted with no parameters. That is a real gap in
// the corpus and it is the price of this row being green.
//
// ### What a refusal is an outcome and not a gap
//
// `KotlinGenerator` answers a document it cannot emit with empty files and a diagnostic, never a
// throw. A document can refuse for reasons that have nothing to do with determinism — a
// typography token naming a role this engine does not spell is one — so an outcome carries both
// halves and a test compares whichever of them it has. Two runs that both refuse have to refuse
// *identically*, which is itself a determinism claim and would otherwise go unchecked.
//
// The census in `DeterminismTest` is what stops the rest from passing over a corpus of nothing.
//
// ### Who uses this today
//
// Two suites: `DeterminismTest` and `PermutationTest`. It is written for the next one rather than
// for those two — every helper takes a `UiDocument` and a seed, which is all a caller that did not
// get its document from `DocumentGenerator` has. The one thing this file owns and neither suite
// does is the filtered schema, and `codegenCorpusView` is the whole of that.

/**
 * The schema the codegen corpus is generated and analysed against.
 *
 * One instance rather than a call per seed, for the reason `GeneratorFixtures.kt` gives: the
 * registry is immutable, and `Registry.all()` hands back a copy, so a caller cannot reach through
 * this one either. Building it once is what makes "two independent runs" cost two generators
 * rather than two schemas.
 *
 * Typed as [EmittableView] rather than as the `SchemaView` every caller passes it to, because
 * [EmittableView.exclusions] is the only way a reader can find out what the filter dropped without
 * reading the filter — and a filter whose contents cannot be read from the outside is a filter
 * nobody can audit. Every call site widens it back to `SchemaView` on its own.
 */
internal val codegenCorpusView: EmittableView =
    EmittableView(generatorSchema(), setOf(SINGLE_PANEL, BANNER, PROBE))

/**
 * Why [EmittableView] dropped a component, as a type rather than as a sentence.
 *
 * A `Map<ComponentType, String>` would carry the same information and one of these would not: the
 * reason is what `LargeDocumentTest` asserts on, and a test that compares prose has to be
 * rewritten whenever the prose is improved. A test that compares two members of this enum keeps
 * saying what it meant after the wording moves.
 */
internal enum class CorpusExclusion(val reason: String) {

    /**
     * An `Intrinsic` spec carrying a slot or an event, which `CodegenCoverage.intrinsicProblem`
     * refuses — so `KotlinGenerator` **throws at construction** rather than refusing a document.
     */
    IntrinsicWithoutEmitter(
        "declares intrinsic() emission with a slot or an event and no emitter behind it, " +
            "which CodegenCoverage refuses when the generator is constructed",
    ),

    /**
     * A `ComposeCall` spec declaring an event the binding does not bind — the `onValueChange`
     * case. Refused per node at document time as `codegen.no_binding`, which is why it needed its
     * own rule and why the rule is written over the shape rather than over the name.
     */
    EventWithoutBinding(
        "declares an event its compose call does not bind, so codegen refuses every node " +
            "carrying a handler for it as codegen.no_binding",
    ),
}

/**
 * Whether codegen can put [spec]'s declared events into the call it writes.
 *
 * The class is named in the file KDoc; this is the whole of the test, and it reads the same two
 * declarations `KotlinGenerator.unboundEvent` does — `spec.events` against
 * `binding.events` — so a component that passes here and is refused there is a bug in this
 * function rather than a difference of opinion about what "bound" means.
 *
 * Narrowed to `ComposeCall` deliberately. `Intrinsic` specs are the *other* exclusion, they fail
 * earlier and louder, and their event is emitted by the engine-owned path rather than by a
 * parameter — so treating them here as well would drop a third component for a second reason and
 * make [EmittableView.exclusions] say something the file does not otherwise claim.
 */
internal fun declaresEventNoBindingCanReceive(spec: ComponentSpec): Boolean {
    val binding = spec.codegen
    if (binding !is CodegenBinding.ComposeCall) return false
    return spec.events.any { declared -> binding.events.none { it.event == declared.key } }
}

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
 * what analysis said, what codegen said, and what codegen wrote. [planned] is the engine's own
 * §16.5 file list, kept so the layout can be checked against the planner rather than against a
 * list written out in a test.
 *
 * [codegenDiagnostics] is what **codegen** added, with the analysis findings already reported in
 * [analysisDiagnostics] left out: `KotlinGenerator.generate` returns the diagnostics it was handed
 * back alongside its own, so the unfiltered list names every analysis finding twice, and it names
 * them at all on the success path while the refusal path below has none. Dropping the prefix makes
 * the field mean the same thing either way.
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
 * One document from [seed], generated and pushed through the pipeline.
 *
 * The convenience half, and it earns its place because a determinism claim is about a *seed*:
 * writing the two calls out at every site is how a site ends up comparing two runs that were not
 * over the same seed.
 */
internal fun outcomeOf(seed: Long): CorpusOutcome =
    outcomeOf(seed, DocumentGenerator(codegenCorpusView, seed).generate().document)

/**
 * [document], labelled with [seed], through analysis and codegen — nothing kept that a comparison
 * could not use.
 *
 * ### Why the document is the argument and not the generator's `GeneratedDocument`
 *
 * A pipeline that only accepts a generator's output can only be fed by a generator. `PermutationTest`
 * was the first caller that could not do that — a permuted document has no plan, because the plan
 * records what the *generator* decided and a permutation changes nothing about that — so it handed
 * this function a `UiDocument`, got an argument-type error, and the nine diagnostics behind that one
 * were every member read off the wrong receiver.
 *
 * The input is the document; [seed] is a label for the failure message, which is all it ever was.
 * Nothing in [CorpusOutcome] reads the generator's `GenerationPlan`: `planned` is §16.5's own
 * `planDocument`, which is the thing the layout is checked against either way.
 *
 * ### Why nothing is reused
 *
 * A **fresh** `KotlinGenerator` and a **fresh** `Analyzer` per call, deliberately. A reused
 * generator is supported — `KotlinGenerator.generate` clears its per-run scratch, and
 * `CodegenTest` proves it — but "two runs agree" is only evidence about determinism if the two
 * runs did not share an object that could carry state from one into the other. Reuse cannot see
 * a field that `generate` forgot to clear, because both runs start from the same cleared value;
 * two instances can.
 */
internal fun outcomeOf(seed: Long, document: UiDocument): CorpusOutcome {
    val analysis = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(codegenCorpusView).analyze(document)
    val reported: List<String> = analysis.diagnostics.map { renderDiagnostic(it) }
    val resolved = analysis.resolved
        ?: return CorpusOutcome(
            seed,
            encodedBytes(document),
            reported,
            emptyList(),
            emptyList(),
            emptyList(),
        )

    val plan = planDocument(resolved, CORPUS_BASE_PACKAGE)
    val result = KotlinGenerator(codegenCorpusView, CodegenOptions(CORPUS_BASE_PACKAGE))
        .generate(resolved, analysis.diagnostics)

    return CorpusOutcome(
        seed = seed,
        documentBytes = encodedBytes(document),
        analysisDiagnostics = reported,
        codegenDiagnostics = result.diagnostics.drop(analysis.diagnostics.size).map { renderDiagnostic(it) },
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
 * ### What this row can and cannot reach
 *
 * PLAN §5.4's canonical formatter assembles a number's text from a scaled integer and never
 * reaches `Float.toString()` or `Double.toString()`, and `CanonicalNumbers.kt` says exactly why:
 * the JVM delegates to `java.lang.Double.toString`, whose algorithm JDK 19 changed, and
 * Kotlin/Native, JS and Wasm each carry their own. A document format cannot rest on that.
 *
 * `LiteralPrinter.floatText` used to be the other end of that rule and to break it: it spelled a
 * number with `Double.toString()` and appended `".0"` when the result had no fractional part, so
 * a `Value.Dp(0.0001f)` emitted `1.0E-4.dp` — a `Double` literal, where `.dp` is declared on
 * `Float` and `Int`, so the file did not compile in the consumer's tree — and a `Value.Dp(0.1f)`
 * emitted `0.10000000149011612.dp`, the widened spelling §18 refuses to write. It now goes
 * through `fixedDecimalText` and trims to the last non-zero digit, so codegen reaches the same
 * canonical arithmetic the model does.
 *
 * **So the three spellings this row hunts are no longer reachable from a `LiteralPrinter`
 * literal**: an exponent, more than four fractional digits, and a magnitude below `10^-3` are all
 * things a scaled integer cannot produce. What is left is the narrower claim this row can still
 * make, and it is worth making — a number some *other* part of codegen wrote for itself, such as
 * the `1` in `backStack.size > 1`, or an emitter added later that bypasses the printer. It is the
 * only row in either suite that looks at the digits rather than at an agreement between two runs.
 *
 * The generator's numbers are half-integers from `0.0` upwards, so this corpus does not reach even
 * that much. That is a coverage limit to state, not a pass to bank.
 *
 * String literals are blanked before the scan. A document may legitimately carry the text
 * `"1.00000"` inside a string, and a number check that cannot tell a string from a literal is a
 * check that reports noise.
 *
 * Hex literals are blanked for the same reason, and `LiteralPrinter`'s `Color(0x…)` is the shape
 * that requires it: eight hex digits spell `0E375` or `37E594` often enough that `EXPONENT` reads
 * the colour as scientific notation, and a colour is not a decimal.
 */
internal fun nonCanonicalNumbers(outcome: CorpusOutcome): List<String> {
    val violations = mutableListOf<String>()
    for (file in outcome.files) {
        val code = file.content.replace(STRING_LITERAL, "\"\"").replace(HEX_LITERAL, "")
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
 * The components this module adds, without the engine-owned binding.
 *
 * `SchemaView` rather than `Schema` because `Schema`'s constructor is `internal` to
 * `:engine:schema`, which is right — a schema is built through its builder — and because
 * `Analyzer` already builds its own overlay per call for `doc.*` types. The filtered registry is
 * rebuilt here rather than reused because `Registry` is an interface with no filtered view of its
 * own, and `RegistryBuilder.build` returns values in key order, so the rebuilt registry iterates
 * exactly as the original one did minus the entries [exclusions] names.
 *
 * ### Why [exclusions] exists
 *
 * The filter is half a name list and half a rule, and only the name list cannot surprise anybody:
 * it changes when a person edits it. The rule can widen on its own — the next builtin that
 * declares an event its binding does not bind is excluded the moment it is registered, with no
 * edit here and no person deciding it — which is the behaviour wanted and also the one that can
 * quietly turn a benchmark into a measurement of nothing.
 *
 * So the filter reports itself. [exclusions] is computed from the same pass that builds
 * [components], and `LargeDocumentTest` asserts it is exactly the four types it expects. A
 * fifth is not a failure of taste, it is the signal that §29's numbers have stopped meaning what
 * the file KDoc says they mean, and it is better as a red test than as a faster benchmark.
 */
internal class EmittableView(
    delegate: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>,
    private val hidden: Set<ComponentType>,
) : SchemaView<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {

    /** Every component this view does not carry, and the rule that dropped it. See above. */
    val exclusions: Map<ComponentType, CorpusExclusion> = delegate.components.all()
        .filter { spec -> spec.type in hidden || declaresEventNoBindingCanReceive(spec) }
        .associate { spec ->
            spec.type to if (spec.type in hidden) {
                CorpusExclusion.IntrinsicWithoutEmitter
            } else {
                CorpusExclusion.EventWithoutBinding
            }
        }

    override val components: Registry<ComponentType, ComponentSpec> =
        RegistryBuilder<ComponentType, ComponentSpec>().apply {
            for (spec in delegate.components.all()) {
                if (spec.type !in exclusions) register(spec.type, spec)
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

/** `Color(0x0E375A4C)`: hex digits spell `0E375`, which `EXPONENT` reads as an exponent. */
private val HEX_LITERAL = Regex("""\b0[xX][0-9a-fA-F]+\b""")

/** `1.0E-4`: a magnitude a JVM's `Double.toString` spells in scientific notation. */
private val EXPONENT = Regex("""\d+(?:\.\d+)?[eE][+-]?\d+""")

/** `0.10000000149011612`: a `Float` at `0.1`, widened and printed. */
private val OVER_PRECISE = Regex("""(?<![\w.])\d+\.\d{5,}(?![\w.])""")