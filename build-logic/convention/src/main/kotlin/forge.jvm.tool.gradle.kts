import forge.forgeJavaVersion
import forge.forgeJvmTarget
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(forgeJavaVersion)

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(forgeJvmTarget))
    }
}

// Java and Kotlin have to agree on the bytecode target, and the two values come from
// different places: Kotlin takes `jvmTarget` from the shared catalog, while a Java
// compilation with no explicit target falls back to the JDK toolchain. Left undefined that
// produced "Inconsistent JVM-target compatibility detected for tasks 'compileTestJava'
// (21) and 'compileTestKotlin' (17)" — the toolchain JDK and the catalog's target are two
// different numbers, and only one of them was being applied.
//
// The catalog wins, because it is the org-wide policy for what this bytecode has to run on.
// The toolchain stays a separate concern: it chooses the JDK that *runs* javac and
// kotlinc, not the version they emit.
java {
    sourceCompatibility = JavaVersion.toVersion(forgeJvmTarget)
    targetCompatibility = JavaVersion.toVersion(forgeJvmTarget)
}

// No explicitApi() here, deliberately. This convention builds applications, and
// `explicitApi()` is a statement about the stability promise a library makes to consumers.
// An executable has no consumers that link against it, so requiring explicit visibility on
// every declaration would be ceremony with no information behind it. See
// `forge.jvm.library` for the JVM library counterpart that does opt in.

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
