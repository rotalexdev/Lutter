package dev.rotalex.lutter.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * One Kotlin source file, as the rules see it.
 *
 * The rules need its name, its text, its import list and where it lives. That is all this holds.
 *
 * [relativePath] is the fourth because a rule keyed on which file is carrying a declaration needs
 * to say *which*, and two modules can hold a file of the same name.
 */
internal class KotlinFile(
    val name: String,
    val text: String,
    val relativePath: String,
) {

    /**
     * Every imported name, in declaration order and without duplicates.
     *
     * Read from the file header only — the region before the first declaration, which is the
     * only place Kotlin accepts an import. Reading the whole text would count an import that
     * a module *emits* as one it *compiles*: a scaffolder writing `import android.app.Activity`
     * into a generated file carries those characters in a string literal, imports nothing from
     * the Android SDK, and is not the rule's target. The header is what the compiler sees.
     *
     * Parsed from the text rather than from a compiler front end. These rules are text
     * scanners on purpose — see `SourceRules` — and an import list is the one piece of
     * structure they do need, which the header reads exactly.
     */
    val imports: List<String> = headerImports()

    private fun headerImports(): List<String> {
        val imports = mutableListOf<String>()
        var inBlockComment = false
        var annotationDepth = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (inBlockComment) {
                if (line.contains("*/")) inBlockComment = false
                continue
            }
            if (annotationDepth > 0) {
                annotationDepth = (annotationDepth + line.count { it == '(' } - line.count { it == ')' })
                    .coerceAtLeast(0)
                continue
            }
            when {
                line.isEmpty() || line.startsWith("//") -> Unit
                line.startsWith("/*") -> inBlockComment = !line.contains("*/")
                line.startsWith("package ") -> Unit
                line.startsWith("@") -> annotationDepth =
                    (line.count { it == '(' } - line.count { it == ')' }).coerceAtLeast(0)
                else -> {
                    val imported = IMPORT.matchEntire(line)?.groupValues?.get(1)
                        ?: return imports.distinct()
                    imports += imported
                }
            }
        }
        return imports.distinct()
    }

    private companion object {
        val IMPORT = Regex("""import\s+([\w.]+(?:\.\*)?)\s*""")
    }
}

/**
 * Locates and reads the sources these rules inspect.
 *
 * The rules run in a test JVM whose working directory is the module directory
 * (`tools/architecture-tests`), not the repository root, and Gradle offers no supported way
 * to move a test's working directory without threading a system property through every
 * workflow. So the root is found by walking up to the first ancestor that contains
 * `settings.gradle.kts`: a file that exists only at the repository root, which makes the
 * search self-correcting if the layout ever changes.
 *
 * File discovery is plain `java.nio.file`, not Konsist.
 *
 * PLAN §23.4 nominates Konsist, and it was used here first. It turned out to be the wrong
 * tool twice over: `Konsist.scopeFromDirectory` refuses any path outside the project it
 * detects, and this test JVM's project is `tools/architecture-tests`, so every scan of
 * `engine/…` died with `IllegalArgumentException` before a single rule ran. It was also
 * never doing the work — Konsist supplied file names, text and imports, and the rules
 * themselves were already the text scanners in `SourceRules`. Removing it deletes a
 * dependency whose last release predates the Kotlin version this build uses, and changes no
 * rule's behaviour. If a future rule genuinely needs a syntax tree, that is when Konsist
 * belongs, and the test task's working directory is the thing to fix first.
 */
internal object RepositoryRoot {

    val path: Path = locateRepositoryRoot()

    private fun locateRepositoryRoot(): Path {
        val start: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath()

        var candidate: Path? = start
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }

        error(
            "Could not find settings.gradle.kts in any ancestor of $start. " +
                "These rules only run from inside the repository.",
        )
    }

    /**
     * Every Kotlin file under [relativeDirectory], relative to the repository root.
     *
     * A directory that does not exist, or that holds no Kotlin file, yields an empty list:
     * a rule over an empty module is a no-op, not a failure.
     */
    fun kotlinFilesIn(relativeDirectory: String): List<KotlinFile> =
        kotlinFilesIn(path.resolve(relativeDirectory))

    /** Every Kotlin file under [directory]. */
    fun kotlinFilesIn(directory: Path): List<KotlinFile> {
        if (!Files.isDirectory(directory)) return emptyList()

        val files = Files.walk(directory)
        return try {
            files
                .filter { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".kt") == true }
                // Generated and IDE output can hold copies of the sources, and a copy
                // scanned twice is one violation reported twice.
                //
                // `filter` with a negated predicate, not `filterNot`: this is a
                // java.util.stream.Stream, and `filterNot` is a Kotlin Iterable extension
                // that a Stream does not have.
                //
                // `.git` is here for the one rule that scans the whole repository: a walk of it
                // is slow, and nothing under it is source.
                .filter { file ->
                    val asText = file.toString()
                    !asText.contains("/build/") && !asText.contains("/.gradle/") &&
                        !asText.contains("/.git/")
                }
                .sorted(compareBy { it.toString() })
                .map { file ->
                    KotlinFile(
                        file.fileName.toString(),
                        Files.readString(file),
                        path.relativize(file).toString(),
                    )
                }
                .collect(Collectors.toList())
        } finally {
            files.close()
        }
    }

    /** Source root of every engine module: the `src` directory of each one. */
    fun engineSourceDirectories(): List<Path> = childDirectoriesOf("engine").map { it.resolve("src") }

    /**
     * Every module under `engine/`, by name, to its source root.
     *
     * The keyed form is what lets a rule classify modules instead of listing them: a name is
     * the thing a rule's failure message needs and the thing a classification is keyed on.
     */
    fun engineModules(): Map<String, Path> =
        childDirectoriesOf("engine").associate { it.fileName.toString() to it.resolve("src") }

    /** The text of [fileName] in the module directory that owns [sourceRoot], or empty. */
    fun buildFileText(sourceRoot: Path, fileName: String): String {
        val file = sourceRoot.parent.resolve(fileName)
        return if (Files.isRegularFile(file)) Files.readString(file) else ""
    }

    /**
     * Every `src/commonMain` directory in the repository.
     *
     * Matching the `src/commonMain` suffix rather than a module list is the point: a new
     * module becomes covered the moment it is created, with nothing to update here.
     */
    fun commonMainDirectories(): List<Path> {
        // java.util.stream.Stream, not an Iterable: it has `filter` and `sorted` but none of
        // Kotlin's Iterable helpers, hence the explicit comparator and collector.
        val candidates = Files.walk(path)
        return try {
            candidates
                .filter { Files.isDirectory(it) }
                .filter { it.fileName?.toString() == "commonMain" && it.parent?.fileName?.toString() == "src" }
                .filter { !it.toString().contains("/build/") && !it.toString().contains("/.gradle/") }
                .sorted(compareBy { it.toString() })
                .collect(Collectors.toList())
        } finally {
            candidates.close()
        }
    }

    private const val PLAIN_TEST_SOURCE_SET: String = "test"
    private const val TEST_SOURCE_SET_SUFFIX: String = "Test"

    /**
     * Every `src/<name>Test` directory in the repository, and every `src/test`.
     *
     * Matching the directory name rather than listing the source sets is the whole point:
     * `commonTest`, `desktopTest` and the `androidHostTest` that `withHostTest {}` configures are
     * all found by it, and so is whatever a future module adds — with nothing here to update.
     * `test` is the JVM modules' own spelling, `tools/architecture-tests` among them, and it is
     * matched because a rule that ignored it would report a code as untested on the strength of
     * where its test happens to live.
     */
    fun testSourceDirectories(): List<Path> {
        // java.util.stream.Stream, for the same reason as `commonMainDirectories` above.
        val candidates = Files.walk(path)
        return try {
            candidates
                .filter { Files.isDirectory(it) }
                .filter { isTestSourceSet(it) }
                .filter { it.parent?.fileName?.toString() == "src" }
                .filter { !it.toString().contains("/build/") && !it.toString().contains("/.gradle/") }
                // `.git` because this is the second rule to walk the whole repository, and the
                // first one's note about a walk of it being slow was written before this one.
                .filter { !it.toString().contains("/.git/") }
                .sorted(compareBy { it.toString() })
                .collect(Collectors.toList())
        } finally {
            candidates.close()
        }
    }

    /** Whether [directory]'s own name is one this build gives to a test source set. */
    private fun isTestSourceSet(directory: Path): Boolean {
        val name: String = directory.fileName?.toString() ?: return false
        return name == PLAIN_TEST_SOURCE_SET || name.endsWith(TEST_SOURCE_SET_SUFFIX)
    }

    private fun childDirectoriesOf(relativeDirectory: String): List<Path> {
        val parent = path.resolve(relativeDirectory)
        if (!Files.isDirectory(parent)) return emptyList()

        return Files.newDirectoryStream(parent).use { stream ->
            stream.filter { Files.isDirectory(it) }.sortedBy { it.toString() }
        }
    }
}
