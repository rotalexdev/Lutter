import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // AGP's own KMP library plugin. It provides `kotlin { android { } }`, which replaced
    // the deprecated `androidLibrary { }` block. `androidTarget()` is not this API either.
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val javaVersion = libs.versions.javaVersion.get().toInt()
val jvmTargetVersion = libs.versions.jvmTarget.get()
val androidCompileSdk = libs.versions.androidCompileSdk.get().toInt()
val androidMinSdk = libs.versions.androidMinSdk.get().toInt()

// A property, not an environment variable, so it is greppable and shows up in the CI log
// next to the command that set it.
val warningsAsErrors = providers.gradleProperty("forge.warningsAsErrors").orNull.toBoolean()

kotlin {
    // Every public declaration in every library module must state its visibility and its
    // return type. This is what makes the ABI dump readable as documentation.
    explicitApi()

    jvmToolchain(javaVersion)

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

    iosSimulatorArm64()

    // No iOS or Wasm target here. Canary targets are applied by `forge.canary.targets`,
    // and only to the pure modules: PLAN §21.1 scopes them to modules whose `commonMain`
    // has to stay platform-free, and §21.2 keeps Compose modules on Android + Desktop
    // until iOS/Wasm *runtime* support lands post-MVP (roadmap item 17). A Compose module
    // that inherited these targets would fail to compile, and one that inherited them by
    // accident would be the kind of coupling this build is supposed to prevent.

    // KGP's built-in ABI validation (KEEP-0440), which replaced
    // binary-compatibility-validator. It produces `checkKotlinAbi`.
    //
    // `keepLocallyUnsupportedTargets` is left at its default on purpose: it is what lets a
    // Linux runner infer the iOS and Wasm ABI instead of failing on targets it cannot
    // build. Setting it to false would trade a green CI for a check that only works on a
    // Mac.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }

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
