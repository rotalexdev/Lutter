package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.GeneratedFile
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.Severity
import java.io.File
import kotlin.jvm.JvmName
import kotlin.system.exitProcess

/**
 * PLAN §28.5's nightly batch: seeded documents written out for a real Gradle compile.
 *
 * Arguments are `<outDir> <documentCount> <basePackage>` and the seed range is `1..documentCount`,
 * so the number a run asks for and the range it walks cannot disagree.
 *
 * ### Three ways this batch passes on nothing
 *
 * Every document gets its own directory *and* its own base package, because the generator names
 * every page `GenScreen$index`: without both, N documents share one set of relative paths and one
 * package, and N-1 of them are overwritten before the compiler is ever asked.
 *
 * A refusal fails the batch rather than being skipped. §16.7 empties a result carrying one
 * error-severity diagnostic, so a batch that logged refusals and exited zero would compile
 * nothing and report green.
 *
 * Every iteration either writes a document or appends a line to `failures`, so
 * `produced + failures.size == documentCount` holds by construction. The exit checks both sides:
 * the list names the documents, and the count catches a batch whose failures were recorded but
 * whose count was not.
 *
 * ### Why `BuiltinEngine.schema()` and not `generatorSchema()`
 *
 * Only the first is a schema a user could have compiled a document against; the three `gen.*`
 * components `generatorSchema()` adds are registered by no engine module. What that costs is
 * `gen.Probe`'s value shapes and `ChildFilter.Only`, `Except` and `Cardinality.ZeroOrOne`, which
 * therefore reach no compile here.
 *
 * `desktopMain` and `internal`, not a `commonMain` `main`: the first because this writes files and
 * `NoPlatformApisInCommonMainTest` refuses `java.io` in any `commonMain`, the second because a
 * public declaration here lands in `api/desktop/test-support.api` on every pull request.
 * `@JvmName` is what keeps an `internal` function reachable as a JVM entry point at all.
 */
@JvmName("main")
internal fun main(args: Array<String>) {
    if (args.size != EXPECTED_ARGS) {
        println("usage: fuzz-batch <outDir> <documentCount> <basePackage>")
        exitProcess(USAGE)
    }
    val out = File(args[OUT_DIR])
    val count = args[DOCUMENT_COUNT].toInt()
    val basePackage = args[BASE_PACKAGE]
    check(count > 0) { "documentCount must be positive, not $count" }

    // Whatever a previous run over a wider seed range wrote is still under this directory and
    // would still be compiled, so the count below would not be counting the batch's sources.
    out.deleteRecursively()
    out.mkdirs()

    val schema = BuiltinEngine.schema()
    val failures = mutableListOf<String>()
    var produced = 0
    var written = 0
    val started = System.currentTimeMillis()

    for (seed in 1L..count.toLong()) {
        val id = "doc$seed"
        val document = DocumentGenerator(schema, seed).generate().document
        val analysis = BuiltinEngine.analyze(schema, document)
        if (analysis.hasErrors) {
            failures += "seed $seed, analysis: " + renderErrors(analysis.diagnostics)
            continue
        }
        val resolved = analysis.resolved
        if (resolved == null) {
            failures += "seed $seed: analysis reported no errors and resolved nothing"
            continue
        }
        // One generator per document: a reused one carries per-run scratch that nothing about
        // the next document would reset.
        val result = KotlinGenerator(schema, CodegenOptions(basePackage + "." + id))
            .generate(resolved, analysis.diagnostics)
        val errors = result.diagnostics.filter { it.severity == Severity.Error }
        if (errors.isNotEmpty()) {
            failures += "seed $seed, codegen: " + renderErrors(errors)
            continue
        }
        if (result.files.files.isEmpty()) {
            failures += "seed $seed: codegen emitted no file and recorded no error"
            continue
        }
        write(File(out, id), result.files.files)
        produced += 1
        written += result.files.files.size
    }

    val elapsed = System.currentTimeMillis() - started
    println("fuzz batch: seeds 1..$count, $produced of $count produced files, $written file(s), ${elapsed}ms")
    if (failures.isEmpty() && produced == count) return
    println("fuzz batch: ${failures.size} document(s) did not compile")
    for (failure in failures) println("  $failure")
    exitProcess(REFUSED)
}

/** The findings that stopped a document, capped so one refusal cannot bury the next. */
private fun renderErrors(diagnostics: List<Diagnostic>): String {
    val errors = diagnostics.filter { it.severity == Severity.Error }
    val shown = errors.take(SHOWN_PER_DOCUMENT).joinToString("; ") { "${it.code.value}: ${it.message}" }
    return if (errors.size > SHOWN_PER_DOCUMENT) {
        "$shown; and ${errors.size - SHOWN_PER_DOCUMENT} more"
    } else {
        shown
    }
}

/** Writes each path under [dir] as given; codegen itself never touches a filesystem. */
private fun write(dir: File, files: List<GeneratedFile>) {
    dir.mkdirs()
    for (generated in files) {
        val target = File(dir, generated.path)
        target.parentFile?.mkdirs()
        target.writeText(generated.content)
    }
}

private const val EXPECTED_ARGS = 3
private const val OUT_DIR = 0
private const val DOCUMENT_COUNT = 1
private const val BASE_PACKAGE = 2

/** Findings named per document before the rest are counted, so a long run stays readable. */
private const val SHOWN_PER_DOCUMENT = 3

private const val USAGE = 2
private const val REFUSED = 1