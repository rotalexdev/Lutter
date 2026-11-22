package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.ComposeResourceStrategy
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.ResourceStrategy
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.schema.component.PlatformTag
import java.io.File

/**
 * `forge generate <document.json> [--out <dir>] [--package <pkg>] [--navigation <name>]
 * [--state <name>] [--injection <name>]`.
 *
 * PLAN §36.3's second half: decode, validate, generate. Each stage refuses before the next one
 * runs, which is the whole of what "each stage refuses" means — a document with an error never
 * reaches the generator, so a broken document cannot produce code that looks plausible.
 *
 * `--navigation`, `--state` and `--injection` choose §15's strategies. They belong to the command and not to
 * the document because they are how an app is built, not what it says: one document generates
 * under any pair, which is the seam's whole claim.
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
        signature = "generate <document.json> [--out <dir>] [--package <pkg>] " +
            "[--platforms android,desktop] [--navigation <simple|navigation-compose|navigation3>] " +
            "[--state <snapshot|viewmodel>] [--injection <none|koin|metro|hilt>]",
        summary = "decode, validate and emit Kotlin/Compose",
        positionals = listOf("document.json"),
        flags = mapOf(
            "--out" to Arity.Value,
            "--package" to Arity.Value,
            "--platforms" to Arity.Value,
            "--resources-package" to Arity.Value,
            "--navigation" to Arity.Value,
            "--state" to Arity.Value,
            "--injection" to Arity.Value,
            // Repeatable, for the same reason `check` takes it: a document names plugins, and
            // generation runs the schema those plugins extend.
            "--plugin" to Arity.Many,
            "--help" to Arity.None,
            "-h" to Arity.None,
        ),
        run = { line, out, err -> run(line, out, err) },
    )

    internal fun run(line: CommandLine, out: Appendable, err: Appendable): Int {
        val input: String = line.positional("document.json")
        val outDir: File = File(line.option("--out") ?: DEFAULT_OUT)
        val document: UiDocument = Engine.readDocument(File(input), spec.name)

        val plugins = PluginHost.load(
            line.values("--plugin").map(::File),
            spec.name,
        )
        for (loaded in plugins) out.appendLine("forge ${spec.name}: loaded ${PluginHost.describe(loaded)}")

        // One schema for both stages; see `Engine.schema`.
        val schema = Engine.schema(plugins, spec.name)
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

        val basePackage: String = line.option("--package") ?: document.app.packageName
        val options = CodegenOptions(
            basePackage,
            // Read before anything is written: an unknown name is a refusal, not a fallback to
            // the default, because a caller who typed `--navigation navigaton3` means a strategy
            // and silently getting `simple` is a project whose wiring nobody asked for.
            navigation = Strategies.navigation(line.option("--navigation"), basePackage, spec.name),
            state = Strategies.state(line.option("--state"), spec.name),
            injection = Strategies.injection(line.option("--injection"), spec.name),
            // §20:1598: the scaffold owns the Res package; the command is told rather than
            // guessing. Absent, a resource emission is refused with a finding instead.
            resources = line.option("--resources-package")?.let(::ComposeResourceStrategy) ?: ResourceStrategy.None,
            // Absent, the generator's own default: a portable target set, so a command that never
            // says what it is building keeps the bytes it emitted before the flag existed. Named
            // here because one refusal axis is the target set — `hilt` is Android-only — and an
            // instruction to "select an Android-only target set" needs a flag that can do it.
            targetPlatforms = Strategies.platforms(
                line.option("--platforms"),
                spec.name,
                PlatformTag.ALL,
            ),
        )
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
