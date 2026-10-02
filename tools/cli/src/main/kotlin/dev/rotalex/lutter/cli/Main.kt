package dev.rotalex.lutter.cli

import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.AnalysisResult
import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import java.io.File
import kotlin.system.exitProcess

/**
 * The `forge` command: `generate` runs decode, validation and generation over a file.
 * The other subcommands stay stubs until their units land.
 */
public fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "generate") {
        printUsage()
        exitProcess(if (args.isEmpty()) 0 else 1)
    }
    runGenerate(args.drop(1))
}

private fun printUsage(): Unit = println(
    """
    forge — Forge Engine CLI

      forge generate <document.json> [--out <dir>] [--package <pkg>]   emit Kotlin/Compose
      forge validate <document.json>   diagnose a document     (later unit)
      forge diff <a.json> <b.json>      structural difference   (later unit)
      forge migrate <document.json>    upgrade to the current  (Phase 10)
    """.trimIndent(),
)

// Decodes, validates, generates, writes: each stage refuses before the next one runs.
private fun runGenerate(args: List<String>): Unit {
    val input: String? = args.firstOrNull { !it.startsWith("--") }
    if (input == null) {
        System.err.println("forge generate: missing <document.json>")
        exitProcess(2)
    }
    val outDir = File(optionValue(args, "--out") ?: "generated")
    // `TypeSpec` in the last slot, or `registerBuiltinEnums` cannot apply and every document
    // naming an enum entry — an arrangement, a shape — refuses instead of generating.
    val schema: Schema<ComponentSpec, ModifierSpec, Unit, Unit, TypeSpec> =
        Schema.build<ComponentSpec, ModifierSpec, Unit, Unit, TypeSpec> {
            registerBuiltinSpecs()
            registerBuiltinModifiers()
            registerBuiltinEnums()
        }
    val document: UiDocument = decode(File(input))
    val analysis: AnalysisResult = Analyzer(schema).analyze(document)
    for (diagnostic in analysis.diagnostics) {
        System.err.println(describe(diagnostic))
    }
    if (analysis.hasErrors) exitProcess(1)
    val resolved: ResolvedDocument = checkNotNull(analysis.resolved) {
        "Analysis passed but resolved nothing"
    }
    val options = CodegenOptions(optionValue(args, "--package") ?: document.app.packageName)
    val result: CodegenResult =
        KotlinGenerator(schema, options).generate(resolved, analysis.diagnostics)
    for (diagnostic in result.diagnostics) {
        if (diagnostic !in analysis.diagnostics) System.err.println(describe(diagnostic))
    }
    if (result.diagnostics.any { it.severity == Severity.Error }) exitProcess(1)
    val written: List<File> = FileSink.write(outDir, result.files)
    println("forge generate: wrote ${written.size} files to '${outDir.path}'")
}

private fun decode(file: File): UiDocument {
    if (!file.isFile) {
        System.err.println("forge generate: no such file '${file.path}'")
        exitProcess(2)
    }
    return try {
        JsonDocumentCodec.decode(file.readBytes()).document
    } catch (failure: Exception) {
        System.err.println("forge generate: cannot decode '${file.path}': ${failure.message}")
        exitProcess(2)
    }
}

private fun describe(diagnostic: Diagnostic): String {
    val where = diagnostic.location.nodeId?.toString()
        ?: diagnostic.location.pageId?.toString()
        ?: "document"
    return "${diagnostic.severity} ${diagnostic.code} at $where: ${diagnostic.message}"
}

private fun optionValue(args: List<String>, flag: String): String? {
    val index: Int = args.indexOf(flag)
    if (index < 0 || index + 1 >= args.size) return null
    return args[index + 1]
}
