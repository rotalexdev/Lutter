package dev.rotalex.lutter.scaffold

import dev.rotalex.lutter.codegen.GeneratedFile
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.schema.component.PlatformTag
import dev.rotalex.lutter.testsupport.Golden
import dev.rotalex.lutter.testsupport.classpathGoldenSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §32 Phase 14's shape, and the two properties the phase asks a test to hold: a golden of the
 * scaffolded project, and regeneration that is byte-stable.
 *
 * The paths are asserted individually and not as a count: a scaffold's failure mode is a file
 * that is missing or in the wrong module (`shared/` code in `androidApp`, an app module for a
 * platform the project does not target), and a count would go green on a swap.
 */
class ScaffoldGeneratorTest {

    private val spec: ProjectSpec = ProjectSpec(appName = "Sample App", basePackage = "com.example.sample")

    private val codegen: GeneratedFiles = GeneratedFiles(
        listOf(
            GeneratedFile("App.kt", "package com.example.sample.generated\n\n// App\n"),
            GeneratedFile("AppHost.kt", "package com.example.sample.generated\n\n// AppHost\n"),
        ),
    )

    private fun generated(spec: ProjectSpec = this.spec): List<String> =
        ScaffoldGenerator.generate(spec, codegen).files.map { it.path }

    private fun content(path: String, spec: ProjectSpec = this.spec): String =
        ScaffoldGenerator.generate(spec, codegen).files.single { it.path == path }.content

    /**
     * D28: per-platform app modules, `shared/` kept because the generated project *is* a
     * generated app, and `composeApp/` gone because AGP 9 refuses `com.android.application`
     * beside `kotlin.multiplatform` in one module.
     */
    @Test
    fun `the project has the shape D28 decided`() {
        val paths = generated()

        listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/libs.versions.toml",
            "shared/build.gradle.kts",
            "androidApp/build.gradle.kts",
            "androidApp/src/main/AndroidManifest.xml",
            "desktopApp/build.gradle.kts",
        ).forEach { assertTrue(it in paths, "missing '$it'; got $paths") }

        assertFalse(paths.any { it.startsWith("composeApp/") }, "composeApp/ is the shape AGP 9 forbids: $paths")
    }

    /** A project that targets only the desktop declares no Android module at all. */
    @Test
    fun `a desktop-only project has no android module`() {
        val paths = generated(spec.copy(targetPlatforms = setOf(PlatformTag.Desktop)))

        assertFalse(paths.any { it.startsWith("androidApp/") }, "got $paths")
        assertTrue("desktopApp/build.gradle.kts" in paths, "got $paths")
        assertFalse(
            content("gradle/libs.versions.toml", spec.copy(targetPlatforms = setOf(PlatformTag.Desktop)))
                .contains("com.android.application"),
            "a desktop-only project must not pin a plugin it never applies",
        )
    }

    /** §33: scaffolding is separate from UI codegen, and the two meet in `shared/`. */
    @Test
    fun `generated ui lands in the shared module`() {
        val paths = generated()

        assertTrue("shared/src/commonMain/kotlin/App.kt" in paths, "got $paths")
        assertTrue("shared/src/commonMain/kotlin/AppHost.kt" in paths, "got $paths")
    }

    /** The catalog is emitted, and it pins the versions this build resolved — not its own. */
    @Test
    fun `the catalog carries the versions the build was tested with`() {
        val catalog = content("gradle/libs.versions.toml")

        assertTrue(catalog.contains("kotlin = \"${ScaffoldVersions.KOTLIN}\""), catalog)
        assertTrue(catalog.contains("agp = \"${ScaffoldVersions.AGP}\""), catalog)
        assertTrue(catalog.contains("compose = \"${ScaffoldVersions.COMPOSE}\""), catalog)
        assertTrue(catalog.contains("androidCompileSdk = \"${ScaffoldVersions.ANDROID_COMPILE_SDK}\""), catalog)
    }

    /** §32: "regeneration is byte-stable" — same input, same bytes, no timestamps, no ordering luck. */
    @Test
    fun `emitting twice is byte-identical`() {
        val first = ScaffoldGenerator.generate(spec, codegen)
        val second = ScaffoldGenerator.generate(spec, codegen)

        assertEquals(first, second)
        assertEquals(render(first), render(second))
    }

    /**
     * The whole tree, committed. `render` is the marker format every golden in this repo
     * already uses, so a reviewer reads one dialect.
     */
    @Test
    fun `the scaffolded shape matches its golden`() {
        Golden.assertEquals(
            "goldens/scaffold_sample_app.golden",
            render(ScaffoldGenerator.generate(spec, codegen)),
            classpathGoldenSource(),
        )
    }

    private fun render(files: GeneratedFiles): String = buildString {
        files.files.forEach { file ->
            appendLine(MARKER + file.path)
            appendLine(file.content.trimEnd('\n'))
            appendLine()
        }
    }

    private companion object {
        /** Same marker as the codegen goldens: `### file: <path>`. */
        const val MARKER: String = "### file: "
    }
}
