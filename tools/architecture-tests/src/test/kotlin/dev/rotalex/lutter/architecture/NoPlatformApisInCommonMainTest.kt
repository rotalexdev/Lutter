package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
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
                            .filter { imported -> forbiddenPrefixes.any(imported::startsWith) }
                            .map { imported -> "${file.name} in $directory imports $imported" }
                    }
            }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    /**
     * The rule is about what a module compiles, not about the characters it contains.
     *
     * `:engine:scaffold` emits Kotlin that imports `android.app.Activity` as text. Counting
     * that as a platform-API import would forbid every generator in the repository from
     * emitting platform code, which is the opposite of §23.4's point.
     */
    @Test
    fun `an import inside a string literal is not an import`() {
        val template = KotlinFile(
            name = "Template.kt",
            text = """
                package dev.rotalex.lutter.template

                private fun emit(): String = "import android.app.Activity"
            """.trimIndent(),
            relativePath = "engine/template/src/commonMain/kotlin/Template.kt",
        )

        assertTrue(template.imports.isEmpty()) { "got ${template.imports}" }
    }
}
