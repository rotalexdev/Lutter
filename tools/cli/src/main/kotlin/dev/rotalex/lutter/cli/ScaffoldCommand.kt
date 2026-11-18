package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.ResourceStrategy
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.scaffold.ProjectSpec
import dev.rotalex.lutter.scaffold.ScaffoldGenerator
import dev.rotalex.lutter.schema.component.PlatformTag
import java.io.File

/**
 * `forge scaffold <app-name> [--package <pkg>] [--out <dir>] [--platforms <list>] [--document <file.json>]`.
 *
 * §32 P14's fourth clause — "`ProjectSpec` + generator from `GeneratedFiles`; … CLI command
 * (`scaffold`)" — lands here. The two halves of generation meet in this command and nowhere
 * else: `--document` runs the same decode → validate → generate pipeline `forge generate`
 * runs, and its `GeneratedFiles` becomes the scaffold's input, which is why the generated UI
 * ends up in `shared/` instead of beside it.
 *
 * ### Why the project is written first and the document checked before it
 *
 * The document is validated before anything is written, and a rejection leaves no directory at
 * all. That order is the point: a scaffold that emitted the Gradle project and then refused
 * the document would leave a tree that looks buildable, and the first `gradle build` a user
 * runs fails on a `shared/` module with no UI in it.
 *
 * ### The defaults are decisions, so they are visible
 *
 * `--package` invents `com.example.<slug>`, because a package cannot be derived from an app
 * name without inventing a domain too. The one-line report names the package it chose, so the
 * placeholder is read rather than discovered in a manifest six files later. `--out` defaults
 * to `scaffolded`, the sibling of `generate`'s `generated`, and `--platforms` to the two
 * runners this engine can build — Ios and Web sit outside §2 C1's Android-and-Desktop scope.
 */
internal object ScaffoldCommand {

    /** What the usage prints and what `forge scaffold` dispatches to. */
    val spec: CommandSpec = CommandSpec(
        name = "scaffold",
        signature = "scaffold <app-name> [--package <pkg>] [--out <dir>] [--platforms android,desktop] " +
            "[--document <file.json>]",
        summary = "write a Gradle project for a generated app",
        positionals = listOf("app-name"),
        flags = mapOf(
            "--package" to Arity.Value,
            "--out" to Arity.Value,
            "--platforms" to Arity.Value,
            "--document" to Arity.Value,
            "--help" to Arity.None,
            "-h" to Arity.None,
        ),
        run = { line, out, err -> run(line, out, err) },
    )

    internal fun run(line: CommandLine, out: Appendable, err: Appendable): Int {
        val appName: String = line.positional("app-name")
        val basePackage: String = line.option("--package") ?: defaultPackage(appName)
        val platforms: Set<PlatformTag> = platforms(line.option("--platforms"))
        val outDir: File = File(line.option("--out") ?: DEFAULT_OUT)

        // A document is optional; a rejected one is fatal before any file exists.
        val document: File? = line.option("--document")?.let(::File)
        val codegen: GeneratedFiles = when (document) {
            null -> GeneratedFiles(emptyList())
            else -> codegen(document, basePackage, err) ?: return ExitCode.Rejected
        }

        // Named `project` and not `spec`: `spec` is the command, and a local of that name would
        // silently answer `${spec.name}` with the project's fields instead of the command's.
        val projectSpec = ProjectSpec(appName = appName, basePackage = basePackage, targetPlatforms = platforms)
        val project = try {
            ScaffoldGenerator.generate(projectSpec, codegen)
        } catch (refusal: IllegalArgumentException) {
            // `ScaffoldGenerator` validates what Gradle and Kotlin will accept, and it refuses
            // in the language of the value rather than of the flag. The flag is what a caller
            // can fix, so the refusal is restated as one that names the command.
            throw CliFailure(ExitCode.Unusable, "forge ${spec.name}: ${refusal.message}")
        }

        val written = FileSink.write(outDir, project)
        out.appendLine(
            "forge ${spec.name}: wrote ${written.size} files to '${outDir.path}' " +
                "(package $basePackage, platforms ${platforms.map { it.name.lowercase() }.sorted().joinToString(", ")})",
        )
        return ExitCode.Ok
    }

    /**
     * The document's UI, or `null` when the document was refused.
     *
     * The package the generated code declares is the project's, not the document's: the files
     * are placed under `shared/src/commonMain/kotlin/` by the scaffold, so a codegen pass that
     * trusted the document would put a `package` line that disagrees with the tree it lands in.
     */
    private fun codegen(document: File, basePackage: String, err: Appendable): GeneratedFiles? {
        val decoded: UiDocument = Engine.readDocument(document, spec.name)
        val schema = Engine.builtinSchema()
        val analysis = Engine.analyze(schema, decoded)
        Engine.report(analysis.diagnostics, err)
        if (analysis.hasErrors) return null

        val resolved = analysis.resolved
            ?: throw CliFailure(
                ExitCode.EngineFailure,
                "forge ${spec.name}: analysis reported no errors and resolved nothing",
            )

        val options = CodegenOptions(basePackage, resources = ResourceStrategy.None)
        val result = KotlinGenerator(schema, options).generate(resolved, analysis.diagnostics)
        for (diagnostic in result.diagnostics) {
            if (diagnostic !in analysis.diagnostics) err.appendLine(Engine.describe(diagnostic))
        }
        if (Engine.hasErrors(result.diagnostics)) return null
        return result.files
    }

    /**
     * `com.example.<slug>`: the placeholder that says "not decided yet".
     *
     * Not `--package`'s absence made an error: the first scaffold of a new project has no
     * domain, and refusing to write anything until one exists is a worse first experience than
     * a package a user will search for and replace.
     */
    private fun defaultPackage(appName: String): String =
        "com.example." + appName.lowercase().filter { it.isLetterOrDigit() }

    /**
     * `--platforms` as the spec's target set.
     *
     * Names are matched case-insensitively so `--platforms Android` is not a refusal, and the
     * two gated platforms get their own sentence: refusing `ios` as "unknown" would hide that
     * it is a decision outside the scheduled platform scope (§2 C1) rather than a typo.
     */
    private fun platforms(value: String?): Set<PlatformTag> {
        val requested: List<String> = (value ?: DEFAULT_PLATFORMS)
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (requested.isEmpty()) {
            throw CliFailure(ExitCode.Unusable, "forge ${spec.name}: --platforms names no platform")
        }
        val tags = requested.map { name ->
            when (name) {
                "android" -> PlatformTag.Android
                "desktop" -> PlatformTag.Desktop
                "ios", "web" -> throw CliFailure(
                    ExitCode.Unusable,
                    "forge ${spec.name}: '$name' is not scaffolded yet — §2 C1 narrows the platform scope to " +
                        "Android and Desktop; scaffolded: android, desktop",
                )
                else -> throw CliFailure(
                    ExitCode.Unusable,
                    "forge ${spec.name}: unknown platform '$name'; accepted: android, desktop",
                )
            }
        }
        return tags.toSet()
    }

    private const val DEFAULT_OUT: String = "scaffolded"

    private const val DEFAULT_PLATFORMS: String = "android,desktop"
}
