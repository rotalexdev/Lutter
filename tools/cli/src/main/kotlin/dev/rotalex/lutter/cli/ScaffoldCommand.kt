package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.codegen.InjectionStrategy
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.codegen.NavigationStrategy
import dev.rotalex.lutter.codegen.ResourceStrategy
import dev.rotalex.lutter.codegen.StateStrategy
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.scaffold.ProjectSpec
import dev.rotalex.lutter.scaffold.ScaffoldGenerator
import dev.rotalex.lutter.schema.component.PlatformTag
import java.io.File

/**
 * `forge scaffold <app-name> [--package <pkg>] [--out <dir>] [--platforms <list>] [--document <file.json>]
 * [--navigation <name>] [--state <name>]`.
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
 * `--navigation` and `--state` choose the strategies, and they reach both halves: the codegen
 * pass that runs for `--document` and the `ProjectSpec` the Gradle project is built from. That
 * pairing is why the flags live here as well as on `generate` — a project whose `shared` module
 * was generated under `navigation3` and whose catalog never heard of `navigation3-ui` is the
 * failure the scaffold exists to prevent, and it is invisible until the first build.
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
            "[--document <file.json>] [--navigation <simple|navigation-compose|navigation3>] " +
            "[--state <snapshot|viewmodel>] [--injection <none|koin|metro|hilt>]",
        summary = "write a Gradle project for a generated app",
        positionals = listOf("app-name"),
        flags = mapOf(
            "--package" to Arity.Value,
            "--out" to Arity.Value,
            "--platforms" to Arity.Value,
            "--document" to Arity.Value,
            "--navigation" to Arity.Value,
            "--state" to Arity.Value,
            "--injection" to Arity.Value,
            "--help" to Arity.None,
            "-h" to Arity.None,
        ),
        run = { line, out, err -> run(line, out, err) },
    )

    internal fun run(line: CommandLine, out: Appendable, err: Appendable): Int {
        val appName: String = line.positional("app-name")
        val basePackage: String = line.option("--package") ?: defaultPackage(appName)
        val platforms: Set<PlatformTag> =
            Strategies.platforms(line.option("--platforms"), spec.name, SCAFFOLDED_PLATFORMS)
        val outDir: File = File(line.option("--out") ?: DEFAULT_OUT)
        // Resolved once and handed to both halves below: the codegen pass and the spec. A second
        // parse would be a second chance for the two to disagree about what was asked for.
        val navigation = Strategies.navigation(line.option("--navigation"), basePackage, spec.name)
        val state = Strategies.state(line.option("--state"), spec.name)
        val injection = Strategies.injection(line.option("--injection"), spec.name)

        // A document is optional; a rejected one is fatal before any file exists.
        val document: File? = line.option("--document")?.let(::File)
        val codegen: GeneratedFiles = when (document) {
            null -> GeneratedFiles(emptyList())
            else -> codegen(document, basePackage, platforms, navigation, state, injection, err)
                ?: return ExitCode.Rejected
        }

        // Named `project` and not `spec`: `spec` is the command, and a local of that name would
        // silently answer `${spec.name}` with the project's fields instead of the command's.
        val projectSpec = ProjectSpec(
            appName = appName,
            basePackage = basePackage,
            targetPlatforms = platforms,
            navigation = navigation,
            state = state,
            injection = injection,
        )
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
    private fun codegen(
        document: File,
        basePackage: String,
        platforms: Set<PlatformTag>,
        navigation: NavigationStrategy,
        state: StateStrategy,
        injection: InjectionStrategy,
        err: Appendable,
    ): GeneratedFiles? {
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

        val options = CodegenOptions(
            basePackage,
            navigation = navigation,
            state = state,
            injection = injection,
            resources = ResourceStrategy.None,
            // The project's own target set, not the generator's default: a container refused for
            // its targets has to be asked about the platforms this project actually has, or the
            // refusal answers a question nobody asked.
            targetPlatforms = platforms,
        )
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
     * The two runners this scaffold can write, and the default when `--platforms` is absent.
     *
     * Android and Desktop and not `PlatformTag.ALL`: iOS and Web are refused by
     * `Strategies.platforms` because no app module for them exists here, so a default listing
     * them would describe a project the scaffold cannot produce.
     */
    private val SCAFFOLDED_PLATFORMS: Set<PlatformTag> = setOf(PlatformTag.Android, PlatformTag.Desktop)

    private const val DEFAULT_OUT: String = "scaffolded"
}
