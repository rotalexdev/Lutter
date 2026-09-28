import forge.catalogVersion
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // AGP's own KMP library plugin. It provides `kotlin { android { } }`, which replaced
    // the deprecated `androidLibrary { }` block. `androidTarget()` is not this API either.
    //
    // `id(...)` and not `alias(libs.plugins...)`: a precompiled script plugin's `plugins`
    // block is extracted into a standalone file that is compiled on its own, and the
    // version-catalog accessors do not exist there. The version still comes from the
    // catalog, through the `compileOnly` coordinate in this project's build file.
    id("com.android.kotlin.multiplatform.library")
}

// Read from the shared catalog, the same value every other Rotalex project resolves.
//
// Not through the generated `rootLibs` accessors: a precompiled script plugin is compiled
// separately from accessor generation, so they do not resolve here — CI proved that twice
// with "Unresolved reference 'rootLibs'". The `VersionCatalogsExtension` is the route that
// works in any script, and it is what rotalex-root-conventions uses for its own plugin.
// Module build scripts, which are ordinary .gradle.kts, do use the accessors by name.
//
// The `plugins` blocks in this build use bare `id(...)` for the same reason: the block is
// extracted into its own file and compiled alone, where no accessor exists.
val jvmTargetVersion = catalogVersion("jvmTarget")
val androidCompileSdk = catalogVersion("androidCompileSdk").toInt()
val androidMinSdk = catalogVersion("androidMinSdk").toInt()

// A property, not an environment variable, so it is greppable and shows up in the CI log
// next to the command that set it.
val warningsAsErrors = providers.gradleProperty("forge.warningsAsErrors").orNull.toBoolean()

kotlin {
    // Every public declaration in every library module must state its visibility and its
    // return type. This is what makes the ABI dump readable as documentation.
    explicitApi()

    compilerOptions {
        // -Werror rather than the deprecated `kotlin.allWarningsAsErrors` flag: it reaches
        // every compilation in the module (Android, JVM, native, Wasm) through one
        // property, so there is no target that silently keeps warnings on.
        if (warningsAsErrors) {
            freeCompilerArgs.add("-Werror")
        }
    }

    android {
        // Dashes in a Gradle module name become package separators, so
        // `:engine:builtins-compose` -> `dev.rotalex.lutter.builtins.compose`.
        namespace = "dev.rotalex.lutter." + project.name.replace('-', '.')
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
        }

        // The Android-KMP library plugin disables tests by default, in both directions, to
        // keep builds fast. PLAN §28.7 asks for Android unit tests, so host tests are turned
        // back on here. Device/instrumentation tests stay off: nothing in this project runs
        // on a device, and an emulator in CI costs far more than it would prove.
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions {
            // Pinned to the same value as the Android target on purpose. Left to default,
            // this would follow the JDK 21 toolchain and produce the "inconsistent
            // JVM-target compatibility" failure the moment a class is shared between the
            // two compilations.
            jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
        }
    }

    // The build supports exactly three targets: Android and Desktop (JVM) here, and Wasm
    // from `forge.canary.targets`, which the pure engine modules add. Nothing else — no
    // Kotlin/Native, no JS — so there is no target a stray platform API could hide behind.

    // KGP's built-in ABI validation (KEEP-0440), which replaced
    // binary-compatibility-validator. It produces `checkKotlinAbi`.
    //
    // `keepLocallyUnsupportedTargets` is left at its default on purpose: it is what lets a
    // Linux runner infer the iOS and Wasm ABI instead of failing on targets it cannot
    // build. Setting it to false would trade a green CI for a check that only works on a
    // Mac.
    // Called with no arguments: that is what enables it. Kotlin 2.4 removed the `enabled`
    // property, and the no-arg function is the supported way to switch validation on. It is
    // still `@ExperimentalAbiValidation`, so the opt-in stays.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// PLAN §32 requires an `embedFixtures` task. It copies the shared fixtures out of
// src/commonTest/resources into the build directory so they can be inspected, diffed and
// attached to a bug report without unpacking a klib.
//
// It is deliberately NOT fed back through `resources.srcDir(...)`. That wiring makes the
// resource directory depend on the output of a task that depends on the compile tasks that
// consume the resources, and Gradle resolves the cycle by failing the build or by
// disabling the configuration cache. Classpath inclusion is left to KMP's own per-target
// resource handling (decision A6).
val fixturesDirectory = layout.projectDirectory.dir("src/commonTest/resources")
if (fixturesDirectory.asFile.isDirectory) {
    val embedFixtures = tasks.register<Sync>("embedFixtures") {
        group = "verification"
        description = "Materialises commonTest fixtures under build/ for inspection."
        from(fixturesDirectory)
        into(layout.buildDirectory.dir("embedded-fixtures"))
    }

    tasks.withType<AbstractTestTask>().configureEach {
        dependsOn(embedFixtures)
    }
}
