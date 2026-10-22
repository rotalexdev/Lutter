import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The shared Rotalex JVM convention, not a local one. It owns the JDK configuration
    // this module should not restate: Java's target, from the catalog's `jvmTarget`.
    alias(rootLibs.plugins.convention.jvm.library)
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
//
// JUnit comes from `libs`, the gap-filler at gradle/libs.versions.toml, because it is not
// in the shared catalog yet. `rootLibs` is the shared catalog and is used by name
// everywhere else.
//
// No Konsist. PLAN §23.4 nominates it, and it was here first, but
// `Konsist.scopeFromDirectory` refuses any path outside the project it detects — this test
// JVM's project is tools/architecture-tests — so every scan of engine/... died with
// IllegalArgumentException before a rule ran. It was also never doing the work: the rules
// are the text scanners in SourceRules, and Konsist only supplied file names, text and
// imports. JUnit's own assertions are enough.
dependencies {
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // The launcher is what Gradle uses to talk to the JUnit Platform. It is a runtime
    // dependency; adding it to the compile classpath would be a lie about what the tests
    // need.
    testRuntimeOnly(libs.junit.platform.launcher)
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
