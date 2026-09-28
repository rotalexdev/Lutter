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
// The same `forge.jvmTarget` the KMP conventions use aligns Kotlin's side. It mirrors the
// catalog's `jvmTarget`, and gradle.properties says so, so the two are compared
// deliberately rather than by luck.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(providers.gradleProperty("forge.jvmTarget").get()))
    }
}

application {
    mainClass.set("dev.rotalex.lutter.cli.MainKt")
}

dependencies {
    implementation(project(":engine:model"))
    implementation(project(":engine:serialization"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:codegen"))
    implementation(project(":engine:builtins"))
}
