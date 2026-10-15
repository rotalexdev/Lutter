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
}
