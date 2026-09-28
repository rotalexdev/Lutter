package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §23.3 / §23.4: `model`, `schema`, `serialization`, `interpreter`, `analysis`,
 * `editing`, `codegen` and `builtins` must not import `androidx.compose`.
 */
class NoComposeInPureModulesTest {

    private val pureModules = listOf(
        "model",
        "schema",
        "serialization",
        "interpreter",
        "analysis",
        "editing",
        "codegen",
        "builtins",
    )

    @Test
    fun `pure engine modules do not import compose`() {
        val violations = pureModules.flatMap { module ->
            RepositoryRoot
                .kotlinFilesIn("engine/$module/src")
                .flatMap { file ->
                    file.imports
                        .filter { it.startsWith("androidx.compose") }
                        .map { imported -> "${file.name} (${module}) imports $imported" }
                }
        }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }
}
