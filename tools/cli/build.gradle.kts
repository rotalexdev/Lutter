import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The shared Rotalex JVM convention, plus the standard `application` plugin for the
    // runnable entry point. The shared catalog has no `jvm.application` convention, and
    // inventing a local one is what this branch stopped doing.
    alias(rootLibs.plugins.convention.jvm.library)
    application
}

// The shared convention sets Java's target from the catalog's `jvmTarget` (17). Kotlin's
// jvmTarget is not set there, so it defaults to the JDK running Gradle — 21 in CI — and
// the plugin's own target validation rejects the pair:
//
//   Inconsistent JVM-target compatibility detected for tasks
//   'compileTestJava' (17) and 'compileTestKotlin' (21)
//
// Both sides now read the catalog's single `jvmTarget`. One number, two compilers.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(rootLibs.versions.jvmTarget.get()))
    }

    // ABI tracking. The engine modules get theirs from `forge.kmp.library`; the external
    // `convention.jvm.library` of the shared catalog does not wire it, so the two tools opt
    // in module-locally — PLAN §36's row stays red while they carry no ABI dumps. The
    // `@OptIn` plus the no-arg call is the shape the convention plugin itself uses.
    //
    // The reference dumps under `api/` do not exist yet. The first `check` run fails on
    // purpose, and its built-in recovery generates, commits, and re-verifies them; later
    // runs verify against the committed files.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

application {
    mainClass.set("dev.rotalex.lutter.cli.MainKt")
}

dependencies {
    // `ModuleGraphRules.ALLOWED`'s row for this module, and no other. PLAN §23.2 tabulates the
    // engine rows only; `:tools:cli` has no table row and is enforced by §23.4's checker
    // instead, and this list is that row. `verifyModuleGraph` fails the build on an edge that is
    // not in it — which is why `:engine:editing` is absent below, and why adding it is a
    // decision for the module graph rather than for a CLI.
    //
    // The order is the engine's own dependency order rather than anything meaningful.
    implementation(project(":engine:model"))
    implementation(project(":engine:schema"))
    implementation(project(":engine:serialization"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:codegen"))
    implementation(project(":engine:builtins"))

    // PLAN §36.3 is a claim about exit codes as much as about diagnostics, and a claim a test
    // cannot make is a claim nothing checks: `main` calls `exitProcess`, which would take the
    // test JVM with it. Every command body therefore returns its exit code, and `execute` in
    // `Main.kt` is what the tests call, so what is asserted below is the code a shell sees.
    //
    // No `project(":engine:test-support")` here, deliberately. `ALLOWED_IN_TESTS` has no line
    // for this module, so a test-scoped edge to the harness would fail `verifyModuleGraph` — and
    // a CLI whose tests reached for a seeded document generator would be asserting against
    // fixtures this module does not own. The two documents the tests need are JSON, and JSON is
    // what a user hands the CLI.
    //
    // JUnit is the same coordinate `:tools:architecture-tests` uses, from `libs` — the
    // gap-filler catalog — because the shared `rootLibs` carries no entry for it. No
    // coordinate is new to the repository, and there is still no argument-parsing library:
    // PLAN §4.3's row C6 wrote a Kotlin IR and printer rather than take KotlinPoet, and an
    // argument parser would be the same mistake one layer up.
    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // What Gradle uses to talk to the JUnit Platform. Runtime only: putting it on the compile
    // classpath would be a lie about what the tests need.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The `jvm.library` convention applies the Kotlin JVM plugin, so `src/test/kotlin` exists
// whether or not this block does. This is the half it does not do: it does not tell Gradle
// that the tests run on the JUnit Platform.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
