import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The shared Rotalex JVM convention, not a local one. It owns the JDK configuration
    // this module should not restate: Java's target, from the catalog's `jvmTarget`.
    alias(rootLibs.plugins.convention.jvm.library)
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
//
// JUnit and Konsist come from `libs`, the two-entry gap-filler at
// gradle/libs.versions.toml, because neither is in the shared catalog yet. `rootLibs` is
// the shared catalog and is used by name everywhere else.
dependencies {
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // The launcher is what Gradle uses to talk to the JUnit Platform. It is a runtime
    // dependency; adding it to the compile classpath would be a lie about what the tests
    // need.
    testRuntimeOnly(libs.junit.platform.launcher)

    // Konsist's own assertions (assertTrue / assertFalse) are the only assertion API here.
    // A second assertion library would mean a second test-name convention, and Konsist
    // derives a test name by reflecting over the enclosing function, which only resolves
    // under JUnit.
    testImplementation(libs.konsist)
}

// The shared convention sets Java's target from the catalog's `jvmTarget` and leaves
// Kotlin's alone, so Kotlin defaults to the JDK running Gradle — 21 in CI — and the
// plugin's own target validation rejects the pair:
//
//   Inconsistent JVM-target compatibility detected for tasks
//   'compileTestJava' (17) and 'compileTestKotlin' (21)
//
// Both sides now read the catalog's single `jvmTarget`. One number, two compilers.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(rootLibs.versions.jvmTarget.get()))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
