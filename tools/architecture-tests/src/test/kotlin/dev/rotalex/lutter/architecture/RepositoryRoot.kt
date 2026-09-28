package dev.rotalex.lutter.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * One Kotlin source file, as the rules see it.
 *
 * The rules need three things from a file: its name, its text, and its import list. That is
 * all this holds.
 */
internal class KotlinFile(val name: String, val text: String) {

    /**
     * Every imported name, in declaration order and without duplicates.
     *
     * Parsed from the text rather than from a compiler front end. These rules are text
     * scanners on purpose — see `SourceRules` — and an import list is the one piece of
     * structure they do need, which a regular expression reads exactly.
     */
    val imports: List<String> = IMPORT
        .findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    private companion object {
        val IMPORT = Regex("""(?m)^\s*import\s+([\w.]+(?:\.\*)?)""")
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
                .filterNot { it.toString().contains("/build/") || it.toString().contains("/.gradle/") }
                .sorted(compareBy { it.toString() })
                .map { KotlinFile(it.fileName.toString(), Files.readString(it)) }
                .collect(Collectors.toList())
        } finally {
            files.close()
        }
    }

    /** Source root of every engine module: the `src` directory of each one. */
    fun engineSourceDirectories(): List<Path> = childDirectoriesOf("engine").map { it.resolve("src") }

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
                .filterNot { it.toString().contains("/build/") || it.toString().contains("/.gradle/") }
                .sorted(compareBy { it.toString() })
                .collect(Collectors.toList())
        } finally {
            candidates.close()
        }
    }

    private fun childDirectoriesOf(relativeDirectory: String): List<Path> {
        val parent = path.resolve(relativeDirectory)
        if (!Files.isDirectory(parent)) return emptyList()

        return Files.newDirectoryStream(parent).use { stream ->
            stream.filter { Files.isDirectory(it) }.sortedBy { it.toString() }
        }
    }
}
