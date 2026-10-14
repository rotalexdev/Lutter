package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.doc.UiDocument
import java.io.File

/**
 * `forge generate <document.json> [--out <dir>] [--package <pkg>]`.
 *
 * PLAN §36.3's second half: decode, validate, generate. Each stage refuses before the next one
 * runs, which is the whole of what "each stage refuses" means — a document with an error never
 * reaches the generator, so a broken document cannot produce code that looks plausible.
 *
 * ### The flags, and why they default the way they do
 *
 * `--out` defaults to `generated` and `--package` to the document's own `app.packageName`,
 * because both are already answered by the input: §5.5 puts the package on the document for
 * exactly this, and a caller who has not said otherwise means "use what the document says".
 */
internal object GenerateCommand {

    /** What the usage prints and what `forge generate` dispatches to. */
    val spec: CommandSpec = CommandSpec(
        name = "generate",
        signature = "generate <document.json> [--out <dir>] [--package <pkg>]",
        summary = "decode, validate and emit Kotlin/Compose",
        positionals = listOf("document.json"),
        flags = mapOf(
            "--out" to Arity.Value,
            "--package" to Arity.Value,
            "--help" to Arity.None,
            "-h" to Arity.None,
        ),
        run = { line, out, err -> run(line, out, err) },
    )

    internal fun run(line: CommandLine, out: Appendable, err: Appendable): Int {
        val input: String = line.positional("document.json")
        val outDir: File = File(line.option("--out") ?: DEFAULT_OUT)
        val document: UiDocument = Engine.readDocument(File(input), spec.name)

        // One schema for both stages; see `Engine.builtinSchema`.
        val schema = Engine.builtinSchema()
        val analysis = Engine.analyze(schema, document)
        Engine.report(analysis.diagnostics, err)
        if (analysis.hasErrors) return ExitCode.Rejected

        // `hasErrors` is false and `resolved` is null: the engine resolved nothing and had
        // nothing to say about why. That is a bug in the engine, not a fact about this
        // document, so it does not become `ExitCode.Rejected`.
        val resolved = analysis.resolved
            ?: throw CliFailure(
                ExitCode.EngineFailure,
                "forge ${spec.name}: analysis reported no errors and resolved nothing",
            )

        val options = CodegenOptions(line.option("--package") ?: document.app.packageName)
        val result = KotlinGenerator(schema, options).generate(resolved, analysis.diagnostics)
        // `CodegenResult.diagnostics` is the incoming list *plus* the generator's own, so the
        // findings printed above are in there already and printing the list wholesale would
        // report each of them twice.
        for (diagnostic in result.diagnostics) {
            if (diagnostic !in analysis.diagnostics) err.appendLine(Engine.describe(diagnostic))
        }
        if (Engine.hasErrors(result.diagnostics)) return ExitCode.Rejected

        val written = FileSink.write(outDir, result.files)
        out.appendLine("forge ${spec.name}: wrote ${written.size} files to '${outDir.path}'")
        return ExitCode.Ok
    }

    private const val DEFAULT_OUT = "generated"
}
