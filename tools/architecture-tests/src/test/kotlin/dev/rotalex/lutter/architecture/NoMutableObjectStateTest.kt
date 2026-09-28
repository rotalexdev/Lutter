package dev.rotalex.lutter.architecture

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

/**
 * PLAN §23.4: no `object` declaration with a `var` property.
 *
 * An object is a singleton for the lifetime of its classloader, so a `var` in one is global
 * mutable state that leaks between tests and between the runtime and a worker. It also
 * cannot be made safe with a lock that the reader of the code will remember to take. State
 * belongs in a class that can be constructed, or in an `Atomic` whose name says so.
 */
class NoMutableObjectStateTest {

    @Test
    fun `no object holds mutable state`() {
        val violations = RepositoryRoot
            .engineSourceDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        SourceRules
                            .mutableObjectStateOccurrences(file.text)
                            .map { occurrence -> "${file.name} in $directory: $occurrence" }
                    }
            }

        violations.shouldBeEmpty()
    }
}
