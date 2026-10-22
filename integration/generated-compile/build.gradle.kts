import com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation
import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("forge.kmp.compose")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:serialization"))
    commonMainApi(project(":engine:interpreter"))
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:editing"))
    commonMainApi(project(":engine:codegen"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins"))
    commonMainApi(project(":engine:builtins-compose"))
    // Generated screens call Column/Text directly, so the module compiling them needs more
    // than the compiler plugin's runtime: the same three artifacts builtins-compose uses.
    commonMainImplementation(rootLibs.jetbrains.compose.ui)
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
    commonMainImplementation(rootLibs.jetbrains.compose.material3)
    // Desktop-only UI test runner; commonTest stays pure so Android host tests keep running.
    desktopTestImplementation(compose.desktop.uiTestJUnit4)
    // Plan §36.1 bullet 7's Android half: `RuntimeSemanticsTest` composes under Robolectric on
    // the host runner. Robolectric boots the Android runtime the desktop runner does not pretend
    // to be, and the androidx junit4 rule is what launches a Compose activity for it.
    androidHostTestImplementation(libs.robolectric)
    androidHostTestImplementation(libs.androidx.ui.test.junit4)
    // Declared because the rule launches `TestActivity`, which this manifest-only artifact
    // registers and nothing on the classpath above carries.
    androidHostTestImplementation(libs.androidx.ui.test.manifest)
    // Skiko's native library, or every `runComposeUiTest` dies in `LibraryLoadException` on a
    // headless runner. uiTestJUnit4 pulls the test API, not the rendering backend.
    desktopTestImplementation(compose.desktop.currentOs)
    // The generator behind PLAN §36's nightly fuzz batch. `BuiltinSchemaTest` asserts the
    // precondition that makes the batch meaningful — the seeded generator reads a schema no
    // engine module had to be taught — and the nightly then compiles what it produces. Test
    // scope for the reason `ModuleGraphRules.ALLOWED_IN_TESTS` gives: the harness is fixtures,
    // and nothing that ships may reach it.
    commonTestImplementation(project(":engine:test-support"))
}

// Build-time edge to :tools:cli, not a code dependency: this configuration is outside the
// module-graph check's list on purpose, the same way a buildscript classpath is.
val cliRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(cliRuntime.name, project(":tools:cli"))
}

// The same shape for PLAN §28.5's fuzz batch: a resolvable classpath for a forked JVM. Outside
// the module-graph scanner the way `cliRuntime` above is, and deliberately: it is a build-time
// edge and not a claim about what this module's code may reach. A source set's own classpath
// cannot serve it either, because the batch's output is wired into this module's `commonMain`, so
// a classpath carrying this module's compiled output would make the task depend on the
// compilation it feeds.
val fuzzRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(fuzzRuntime.name, project(":engine:test-support"))
}

// PLAN §28.4's fixture task: runs the CLI over a fixtures directory, then emits the document
// constants the tests decode plus, where the screens can be called with a modifier alone, the
// screen registry the UI test renders.
//
// Two directories run it rather than one. A screen whose handlers read a receiver declares that
// receiver as a parameter with no default, so §28.4's `(Modifier) -> Unit` registry cannot call
// it: `registryFile` has no way to build an `AppNavigator`, a `SnackbarHost` or an `AppHost`. The
// navigating fixtures therefore get their document constants and no registry entry, and the click
// test calls their screen itself so it owns the navigator whose state it observes.
abstract class GenerateFixtures : DefaultTask() {
    @get:InputDirectory
    abstract val fixtures: DirectoryProperty

    @get:OutputDirectory
    abstract val generated: DirectoryProperty

    @get:Classpath
    abstract val cliClasspath: ConfigurableFileCollection

    /** The object the document constants are declared as, so two runs cannot collide. */
    @get:Input
    abstract val documentsObject: Property<String>

    /** False where a screen needs a receiver the registry has no way to supply. */
    @get:Input
    abstract val emitsRegistry: Property<Boolean>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val out = generated.get().asFile
        out.mkdirs()
        val screens = mutableMapOf<String, String>()
        val texts = mutableMapOf<String, String>()
        val files = fixtures.get().asFile.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()
        for (file in files) {
            val id = file.nameWithoutExtension
            val pkg = "dev.rotalex.lutter.generated." + id.replace(Regex("[^A-Za-z0-9_]"), "_")
            exec.javaexec {
                classpath = cliClasspath
                mainClass.set("dev.rotalex.lutter.cli.MainKt")
                args("generate", file.absolutePath, "--out", File(out, id).absolutePath, "--package", pkg)
            }
            val text = file.readText()
            require(!text.contains("\"\"\"")) { "Fixture '$id' holds a triple quote, which raw strings cannot carry" }
            texts[id] = text.replace("$", "${'$'}")
            if (emitsRegistry.get()) screens[id] = firstScreen(pkg, text)
        }
        File(out, documentsObject.get() + ".kt").writeText(documentsFile(documentsObject.get(), texts))
        if (emitsRegistry.get()) File(out, "GeneratedFixtures.kt").writeText(registryFile(screens))
    }

    // The screen AppRoot hosts: first page by id, the same rule the generator uses.
    private fun firstScreen(pkg: String, text: String): String {
        val root = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
        val pages = (root["payload"] as Map<*, *>)["pages"] as Map<*, *>
        val first = pages.entries.sortedBy { it.key as String }.first().value as Map<*, *>
        return "$pkg.screens." + (first["name"] as String) + "Screen"
    }

    private fun documentsFile(name: String, texts: Map<String, String>): String = buildString {
        appendLine("// Generated by generateFixtures. Do not edit.")
        appendLine("package dev.rotalex.lutter.generated.compile")
        appendLine()
        appendLine("public object $name {")
        appendLine("    private val byId: Map<String, String> = mapOf(")
        for ((id, text) in texts) appendLine("        \"$id\" to \"\"\"$text\"\"\",")
        appendLine("    )")
        appendLine()
        appendLine("    public val ids: List<String> = byId.keys.sorted()")
        appendLine()
        appendLine("    public fun json(id: String): String =")
        appendLine("        byId[id] ?: throw IllegalArgumentException(\"No fixture '\$id'\")")
        appendLine("}")
    }

    private fun registryFile(screens: Map<String, String>): String = buildString {
        appendLine("// Generated by generateFixtures. Do not edit.")
        appendLine("package dev.rotalex.lutter.generated.compile")
        appendLine()
        appendLine("import androidx.compose.runtime.Composable")
        appendLine("import androidx.compose.ui.Modifier")
        appendLine()
        appendLine("public object GeneratedFixtures {")
        appendLine("    public val all: Map<String, @Composable (Modifier) -> Unit> = mapOf(")
        for ((id, screen) in screens) appendLine("        \"$id\" to { modifier -> $screen(modifier) },")
        appendLine("    )")
        appendLine("}")
    }
}

val generateFixtures by tasks.register<GenerateFixtures>("generateFixtures") {
    fixtures.set(layout.projectDirectory.dir("fixtures"))
    generated.set(layout.buildDirectory.dir("generated/forge"))
    cliClasspath.from(cliRuntime)
    documentsObject.set("FixtureDocuments")
    emitsRegistry.set(true)
}

// §30's fixture, generated by the same task into a directory of its own: its screen takes the
// navigator its handler closes over, which the shared registry's lambda cannot supply.
val generateNavigatingFixtures by tasks.register<GenerateFixtures>("generateNavigatingFixtures") {
    fixtures.set(layout.projectDirectory.dir("fixtures-navigating"))
    generated.set(layout.buildDirectory.dir("generated/forge-navigating"))
    cliClasspath.from(cliRuntime)
    documentsObject.set("NavigatingFixtureDocuments")
    emitsRegistry.set(false)
}

// PLAN §36.1's `CodegenGoldenTest`, and the only thing that can write its goldens: this module
// compiles what the generator emits, which proves the output is valid Kotlin and nothing about
// whether it is the output anybody agreed to. A golden needs a writer, and the writer is a task —
// forked over `:tools:cli` exactly as `GenerateFixtures` forks it — because the only way to learn
// the bytes is to have the engine produce them, and running the engine by hand is not a step a
// review can reproduce. `CodegenGoldenTest` compares against whatever this last wrote.
//
// Forked rather than in-process for the reason `GenerateFixtures` is: the golden has to be the
// bytes the shipped command emits, and an in-process build script would have to re-assemble
// decode, analyze and generate out of the engine jars — a second copy of `GenerateCommand` that
// could agree with it today and drift from it tomorrow, and a golden that then pins the copy.
//
// Its output is a source-tree path, unlike anything else in this build: `classpathGoldenSource`
// reads a golden off the classpath, and the classpath resource root Gradle already publishes is
// `src/commonTest/resources`. The goldens are resources, so they are not wired into a source set.
abstract class GenerateCodegenGoldens : DefaultTask() {
    @get:InputDirectory
    abstract val fixtures: DirectoryProperty

    @get:InputDirectory
    abstract val navigatingFixtures: DirectoryProperty

    @get:OutputDirectory
    abstract val goldens: DirectoryProperty

    /**
     * Where the CLI writes before it is read back.
     *
     * A real output rather than a temp directory so Gradle owns its lifetime, and emptied per
     * fixture so a file a previous run left behind cannot be read as one this run generated.
     */
    @get:OutputDirectory
    abstract val staged: DirectoryProperty

    @get:Classpath
    abstract val cliClasspath: ConfigurableFileCollection

    /** The prefix `GenerateFixtures` hands the CLI; the golden is that command's output verbatim. */
    @get:Input
    abstract val packagePrefix: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val goldensDir = goldens.get().asFile
        goldensDir.mkdirs()
        val stageRoot = staged.get().asFile
        for ((id, document) in documents()) {
            val out = File(stageRoot, id)
            out.deleteRecursively()
            out.mkdirs()
            exec.javaexec {
                classpath = cliClasspath
                mainClass.set("dev.rotalex.lutter.cli.MainKt")
                args("generate", document.absolutePath, "--out", out.absolutePath, "--package", packageName(id))
            }
            val files = generatedIn(out)
            check(files.isNotEmpty()) { "Fixture '$id' generated no files, so there is nothing to pin" }
            val golden = goldenText(files)
            checkSanctions(id, files, golden)
            File(goldensDir, "${id}.golden").writeText(golden, Charsets.UTF_8)
            logger.lifecycle("generateCodegenGoldens: wrote ${id}.golden (${golden.length} characters)")
        }
    }

    /**
     * Every fixture on disk, from both directories, in a stable order and without a duplicate.
     *
     * Both directories rather than one because `GenerateFixtures` reads both and there is no
     * directory holding all eleven: the navigating fixture is separate because its screen takes a
     * receiver the shared registry cannot supply. Two same-named fixtures across them would have
     * the second silently overwrite the first's golden, so the collision is refused here.
     */
    private fun documents(): List<Pair<String, File>> {
        val found = jsonIn(fixtures.get().asFile) + jsonIn(navigatingFixtures.get().asFile)
        val ids = found.map { (id, _) -> id }
        check(ids.size == ids.toSet().size) { "The two fixture directories hold the same fixture: $ids" }
        return found.sortedBy { (id, _) -> id }
    }

    /**
     * `*.json` directly in [directory], and nothing below it.
     *
     * Non-recursive with the extension filter, which is `GenerateFixtures`' own line: the two
     * tasks have to see the same eleven documents, and the way to guarantee that is to spell the
     * selection the same way twice rather than to agree about it.
     */
    private fun jsonIn(directory: File): List<Pair<String, File>> {
        val files: List<File> = directory.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()
        return files.map { it.nameWithoutExtension to it }
    }

    /** The same derivation `GenerateFixtures` uses, and `CodegenGoldenTest` spells without. */
    private fun packageName(id: String): String =
        packagePrefix.get() + id.replace(Regex("[^A-Za-z0-9_]"), "_")

    /**
     * What the CLI wrote under [root], as `path to text`, in the order `GeneratedFiles` sorts them.
     *
     * The paths are `/`-joined and the list is sorted on the path alone, which is exactly what
     * `GeneratedFiles.files` does — so the golden's file order **is** that sort. `CodegenGoldenTest`
     * says the same thing from the other side, and both say it because a change to the sort would
     * silently reorder every golden in the repository rather than fail one.
     */
    private fun generatedIn(root: File, prefix: String = ""): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val children: List<File> = root.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (child in children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                found += generatedIn(child, path)
            } else {
                found += path to child.readText(Charsets.UTF_8)
            }
        }
        return found.sortedBy { (path, _) -> path }
    }

    /** The golden format: every file in sort order, each behind its own marker line. */
    private fun goldenText(files: List<Pair<String, String>>): String = buildString {
        for ((path, content) in files) {
            appendLine(MARKER + path)
            append(content)
        }
    }

    /**
     * What has to hold of every golden before it is written.
     *
     * `CanonicalJsonGoldenTest` keeps the committed file honest against the codec by re-reading
     * the committed bytes; these are the same move against the generator, and every one is a
     * property that makes the comparison mean something rather than a style rule. Its sorted-object
     * check becomes the file order below and its canonical-number check becomes the narrower rule
     * near the bottom — a carriage return has no counterpart there to copy, which is why this one
     * is only shaped like the other two.
     */
    private fun checkSanctions(id: String, files: List<Pair<String, String>>, golden: String) {
        val paths = files.map { (path, _) -> path }
        check(paths == paths.sorted()) {
            "Fixture '$id' emitted files out of path order: $paths. The golden's order IS that sort."
        }
        val unterminated = files.filter { (_, content) -> !content.endsWith("\n") }.map { (path, _) -> path }
        check(unterminated.isEmpty()) {
            "Fixture '$id' emitted $unterminated, which do not end in a newline, so the next file's " +
                "marker would be written onto their last line"
        }
        // The marker cannot appear inside generated Kotlin: every line the printer breaks is a
        // header comment, a package or import line, an annotation, a declaration that opens with
        // `public`/`private`/`suspend`, an indented continuation, or a closing brace — and the
        // only document-authored text that can reach output goes through `LiteralPrinter.escape`,
        // which writes every newline as two characters, so no document string opens a line. This
        // check is the same claim made mechanical, so a future emitter that breaks it fails where
        // it is introduced rather than as an unreadable golden.
        val clashing = files.filter { (_, content) -> content.lineSequence().any { it.startsWith(MARKER) } }
        check(clashing.isEmpty()) {
            "Fixture '$id' emitted ${clashing.map { (path, _) -> path }}, which hold a line beginning " +
                "'$MARKER' — the marker this format frames files with"
        }
        check('\r' !in golden) {
            "Golden for '$id' holds a carriage return. Golden bytes are compared verbatim, so a " +
                "CRLF checkout would fail every row of this test for a reason no generator change explains."
        }
        // The canonical-number rule, not the canonical-number regex. `CanonicalJsonGoldenTest`
        // matches `-?\d+(\.\d{1,4})?` against a whole JSON number, which generated Kotlin cannot
        // use: a colour is `0xFF00695C` and a Long is `9007199254740993L`, and both are numbers a
        // document legitimately writes. What §10.6 forbids is narrower and is what is checked — a
        // widened Float, and the scientific notation `Double.toString()` reaches for. Both are the
        // bug `LiteralPrinter.floatText` exists because it shipped.
        val widened: List<String> = WIDENED_FLOAT.findAll(golden).map { it.value }.toList()
        val exponents: List<String> = EXPONENT.findAll(golden).map { it.value }.toList()
        val nonCanonical: List<String> = (widened + exponents).distinct()
        check(nonCanonical.isEmpty()) {
            "Golden for '$id' holds ${nonCanonical.size} non-canonical numbers: $nonCanonical"
        }
    }

    private companion object {
        /** Shared with `CodegenGoldenTest` by spelling, because `test-support` cannot be a build input. */
        const val MARKER: String = "### file: "

        /** More than §5.4's four fractional digits: `0.10000000149011612` and not `0.1`. */
        val WIDENED_FLOAT: Regex = Regex("""\d+\.\d{5,}""")

        /**
         * Scientific notation, anchored on a word boundary so a hex literal is not read as one:
         * in `0xFF00E5` only the leading `0` follows a word boundary, and what comes after it is
         * `x`, not a digit.
         */
        val EXPONENT: Regex = Regex("""\b\d+\.?\d*E[+-]?\d""")
    }
}

val generateCodegenGoldens by tasks.register<GenerateCodegenGoldens>("generateCodegenGoldens") {
    group = "build"
    description = "Writes the codegen goldens CodegenGoldenTest compares against, for all eleven " +
        "fixtures. Run it and commit the result after any generator change: " +
        "./gradlew :integration:generated-compile:generateCodegenGoldens"
    fixtures.set(layout.projectDirectory.dir("fixtures"))
    navigatingFixtures.set(layout.projectDirectory.dir("fixtures-navigating"))
    goldens.set(layout.projectDirectory.dir("src/commonTest/resources/goldens"))
    staged.set(layout.buildDirectory.dir("tmp/codegen-goldens"))
    cliClasspath.from(cliRuntime)
    packagePrefix.set("dev.rotalex.lutter.generated.")
}

// PLAN §28.5's nightly batch. `GenerateFixtures` forks once per hand-written document; this forks
// once over the whole seed range, because a fork per seed would spend the count in process
// start-ups alone. Its exit code is the verdict: recounting what it wrote here would be a second
// answer to a question the entry point already answered, and the one that could disagree with it.
abstract class GenerateFuzzBatch : DefaultTask() {
    @get:Input
    abstract val documentCount: Property<Int>

    /** What each document's own subpackage hangs off; see `FuzzBatch.kt` for why it is per document. */
    @get:Input
    abstract val basePackage: Property<String>

    @get:OutputDirectory
    abstract val generated: DirectoryProperty

    @get:Classpath
    abstract val batchClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val out = generated.get().asFile
        out.mkdirs()
        exec.javaexec {
            classpath = batchClasspath
            mainClass.set("dev.rotalex.lutter.testsupport.generator.FuzzBatchKt")
            args(out.absolutePath, documentCount.get().toString(), basePackage.get())
        }
    }
}

val generateFuzzBatch by tasks.register<GenerateFuzzBatch>("generateFuzzBatch") {
    group = "build"
    description = "Generates seeded documents for the nightly fuzz batch."
    generated.set(layout.buildDirectory.dir("generated/fuzz"))
    batchClasspath.from(fuzzRuntime)
    basePackage.set("dev.rotalex.lutter.fuzz")
    // A starting point the first nightly run measures: nobody can time this from the repository,
    // so the count is tuned from the elapsed time that run reports rather than guessed at here.
    documentCount.set(
        providers.gradleProperty("forge.fuzzCount").orElse("200").map { it.toInt() },
    )
}

// `-Pforge.fuzzBatch=true` is the whole of the wiring, and it is read at configuration time
// because a source directory is a configuration-time fact. Configuration-cache safe for the reason
// `forge.warningsAsErrors` is: the property read is recorded as a build input and replayed.
val fuzzBatchRequested = providers.gradleProperty("forge.fuzzBatch").orNull.toBoolean()

kotlin {
    // Passing the task wires both the directory and the dependency: compilation waits for it.
    sourceSets.named("commonMain") {
        kotlin.srcDir(generateFixtures)
        kotlin.srcDir(generateNavigatingFixtures)
        // On request only. Every document would otherwise land in
        // `api/desktop/generated-compile.api` and make a pull request's `checkKotlinAbi` a
        // function of the nightly's document count.
        if (fuzzBatchRequested) {
            kotlin.srcDir(generateFuzzBatch)
        }
    }

    android {
        // Robolectric loads the merged resources of the test graph, which is the documented
        // prerequisite for running Compose activity tests on the host runner: the rule
        // launches an activity, and the activity needs resource-backed infrastructure even
        // when the fixture itself draws no resource. Only one host test compilation may exist,
        // and the `forge.kmp.library` convention's `withHostTest {}` already created it, so the
        // flag goes on that existing compilation rather than through a second `withHostTest`
        // call — the API would refuse a second one.
        compilations.withType(KotlinMultiplatformAndroidHostTestCompilation::class.java) {
            isIncludeAndroidResources = true
        }
    }
}
