package dev.rotalex.lutter.architecture

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

/**
 * PLAN §23.3 / §23.4: no `java.`, `javax.` or `android.` import may appear in any
 * `commonMain` source set, in any module.
 */
class NoPlatformApisInCommonMainTest {

    private val forbiddenPrefixes = listOf("java.", "javax.", "android.")

    @Test
    fun `common code does not reach for a platform api`() {
        val violations = RepositoryRoot
            .commonMainDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        file.imports
                            .map { it.name }
                            .filter { imported -> forbiddenPrefixes.any(imported::startsWith) }
                            .map { imported -> "${file.name} in $directory imports $imported" }
                    }
            }

        violations.shouldBeEmpty()
    }
}
