package dev.rotalex.lutter.architecture

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

/**
 * PLAN §23.4: no `Map<String, Any>` in engine sources.
 *
 * An untyped string map is where the type system stops: once a document value crosses into
 * `Any`, the analyzer cannot check it, the serializer cannot infer a schema for it, and the
 * failure surfaces at runtime. `Value` and `TypeRef` in `:engine:model` exist to make that
 * representation impossible rather than discouraged.
 */
class NoUntypedStringMapTest {

    @Test
    fun `engine sources do not use an untyped string map`() {
        val violations = RepositoryRoot
            .engineSourceDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        SourceRules
                            .untypedStringMapOccurrences(file.text)
                            .map { occurrence -> "${file.name} in $directory uses $occurrence" }
                    }
            }

        violations.shouldBeEmpty()
    }
}
