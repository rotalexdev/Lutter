package dev.rotalex.lutter.architecture

import com.lemonappdev.konsist.api.Konsist
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Locates the sources these rules inspect.
 *
 * The rules run in a test JVM whose working directory is the module directory
 * (`tools/architecture-tests`), not the repository root, and Gradle offers no supported way
 * to move a test's working directory without threading a system property through every
 * workflow. So the root is found by walking up to the first ancestor that contains
 * `settings.gradle.kts`: a file that exists only at the repository root, which makes the
 * search self-correcting if the layout ever changes.
 *
 * Kotlin file discovery goes through Konsist, because Konsist is the architecture enforcer
 * the plan mandates and it owns the parsing. Directory enumeration goes through
 * `java.nio.file`, because Konsist has no "tell me where the source sets are" API and
 * hand-rolling one over PSI would mean a second file walker to keep in sync.
 *
 * Note on inferred return types below: this build cannot be compiled locally, so the
 * declaration type of `KonsistScope.files` is deliberately left to the compiler instead of
 * being named in an import. Naming the wrong type would be a build failure in CI with no
 * local signal, whereas inference cannot be wrong.
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
     * Every Kotlin file under [relativeDirectory], as Konsist declarations.
     *
     * A directory that does not exist, or that holds no Kotlin file, yields an empty list:
     * a rule over an empty module is a no-op, not a failure.
     */
    fun kotlinFilesIn(relativeDirectory: String) = kotlinFilesIn(path.resolve(relativeDirectory))

    /**
     * Every Kotlin file under [directory]. The result type is left to the compiler on
     * purpose: see the note on inferred types in the KDoc above.
     *
     * No configuration block. Konsist already filters to `.kt` files by default, and the
     * one knob that looked configurable — `ktExtensionFilter` — does not exist in 0.17.3.
     * A build that cannot be compiled locally should not carry an API call whose only
     * purpose is to restate a default.
     */
    fun kotlinFilesIn(directory: Path) = directory
        .takeIf { Files.isDirectory(it) }
        ?.let { Konsist.scopeFromDirectory(it.toString()).files }
        .orEmpty()

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
                // Build and IDE output can hold copies of the sources; scanning those would
                // report the same violation once per build directory.
                .filter { !it.toString().contains("/build/") && !it.toString().contains("/.gradle/") }
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
