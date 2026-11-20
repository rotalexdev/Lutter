package dev.rotalex.lutter.scaffold

import dev.rotalex.lutter.codegen.GeneratedFile
import dev.rotalex.lutter.codegen.GeneratedFiles
import dev.rotalex.lutter.codegen.Navigation3
import dev.rotalex.lutter.codegen.NavigationCompose
import dev.rotalex.lutter.codegen.ViewModelState
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

    /**
     * §15's default pair needs no library, so the catalog has no `[libraries]` table at all.
     *
     * Not cosmetic: an empty table would rewrite every scaffold golden for a feature that
     * states nothing about the default project, and "the default app depends on nothing" is
     * exactly what the absent table says.
     */
    @Test
    fun `the default strategies add no dependency`() {
        val catalog = content("gradle/libs.versions.toml")
        val shared = content("shared/build.gradle.kts")

        assertFalse(catalog.contains("[libraries]"), catalog)
        assertFalse(catalog.contains("navigation3"), catalog)
        assertFalse(shared.contains("libs.navigation"), shared)
        assertFalse(shared.contains("libs.lifecycle"), shared)
    }

    /**
     * A generated `Navigation3` app calls `NavDisplay`, so the project has to resolve
     * `navigation3-ui` — the pairing this feature exists to make automatic.
     *
     * The version asserted is the one `ScaffoldVersions` carries, which the build generated from
     * the catalogs it resolved against: a test that spelled `1.2.0` would pass on a machine
     * whose catalog said something else.
     */
    @Test
    fun `a navigation3 project depends on navigation3-ui`() {
        val navigation3 = spec.copy(navigation = Navigation3("com.example.sample"))
        val catalog = content("gradle/libs.versions.toml", navigation3)
        val shared = content("shared/build.gradle.kts", navigation3)

        assertTrue(catalog.contains("[libraries]"), catalog)
        assertTrue(
            catalog.contains("navigation3 = \"${ScaffoldVersions.NAVIGATION3}\""),
            catalog,
        )
        assertTrue(
            catalog.contains(
                "navigation3-ui = { module = \"org.jetbrains.androidx.navigation3:navigation3-ui\", " +
                    "version.ref = \"navigation3\" }",
            ),
            catalog,
        )
        assertTrue(shared.contains("implementation(libs.navigation3.ui)"), shared)
        assertFalse(shared.contains("libs.lifecycle"), shared)
    }

    /** The `viewModel()` a `ViewModelState` app calls comes from `lifecycle-viewmodel-compose`. */
    @Test
    fun `a viewmodel project depends on lifecycle-viewmodel-compose`() {
        val viewModel = spec.copy(state = ViewModelState)
        val catalog = content("gradle/libs.versions.toml", viewModel)
        val shared = content("shared/build.gradle.kts", viewModel)

        assertTrue(
            catalog.contains(
                "lifecycle-viewmodel-compose = " +
                    "{ module = \"org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose\", " +
                    "version.ref = \"lifecycleViewmodelCompose\" }",
            ),
            catalog,
        )
        assertTrue(shared.contains("implementation(libs.lifecycle.viewmodel.compose)"), shared)
        assertFalse(shared.contains("libs.navigation3"), shared)
    }

    /**
     * Both strategies at once, and each library exactly once: the set is a union, and a
     * dependency emitted twice is a catalog Gradle refuses to build.
     */
    @Test
    fun `the two strategies contribute their libraries without duplication`() {
        val both = spec.copy(
            navigation = NavigationCompose("com.example.sample"),
            state = ViewModelState,
        )
        val catalog = content("gradle/libs.versions.toml", both)
        val shared = content("shared/build.gradle.kts", both)

        assertTrue(catalog.contains("navigation-compose = "), catalog)
        assertTrue(catalog.contains("lifecycle-viewmodel-compose = "), catalog)
        assertEquals(
            1,
            shared.windowed("implementation(libs.navigation.compose)".length)
                .count { it == "implementation(libs.navigation.compose)" },
            shared,
        )
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
