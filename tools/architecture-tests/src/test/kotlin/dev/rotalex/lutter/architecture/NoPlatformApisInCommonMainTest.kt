package dev.rotalex.lutter.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §23.3 / §23.4, and ADR-018 for the last entry: no `java.`, `javax.`, `android.` or
 * `kotlin.reflect` import may appear in any `commonMain` source set, in any module.
 *
 * `java.util.zip` arrives with `java.`, and `java.lang.ClassLoader`/`java.lang.reflect` with the
 * same prefix; `kotlin.reflect` is the one spelling the format contract names that a platform
 * prefix would miss. The host — a JVM tool — is where a container is opened and a classloader
 * named, and it has no `commonMain` for this rule to inspect.
 */
class NoPlatformApisInCommonMainTest {

    private val forbiddenPrefixes = listOf("java.", "javax.", "android.", "kotlin.reflect")

    private fun forbiddenImports(file: KotlinFile): List<String> =
        file.imports.filter { imported -> forbiddenPrefixes.any(imported::startsWith) }

    @Test
    fun `common code does not reach for a platform api`() {
        val violations = RepositoryRoot
            .commonMainDirectories()
            .flatMap { directory ->
                RepositoryRoot
                    .kotlinFilesIn(directory)
                    .flatMap { file ->
                        forbiddenImports(file)
                            .map { imported -> "${file.name} in $directory imports $imported" }
                    }
            }

        assertTrue(violations.isEmpty()) { "unexpected violations:\n" + violations.joinToString("\n") }
    }

    /** §28.1: the rule has a positive case, so a green run means it looked, not that it slept. */
    @Test
    fun `a kotlin reflect import is a platform api`() {
        val template = KotlinFile(
            name = "Reflect.kt",
            text = """
                package dev.rotalex.lutter.template

                import kotlin.reflect.KClass
            """.trimIndent(),
            relativePath = "engine/template/src/commonMain/kotlin/Reflect.kt",
        )

        assertTrue(forbiddenImports(template) == listOf("kotlin.reflect.KClass")) {
            "got ${forbiddenImports(template)}"
        }
    }

    @Test
    fun `a java util zip import is a platform api`() {
        val template = KotlinFile(
            name = "Container.kt",
            text = """
                package dev.rotalex.lutter.template

                import java.util.zip.ZipFile
            """.trimIndent(),
            relativePath = "engine/template/src/commonMain/kotlin/Container.kt",
        )

        assertTrue(forbiddenImports(template) == listOf("java.util.zip.ZipFile")) {
            "got ${forbiddenImports(template)}"
        }
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
