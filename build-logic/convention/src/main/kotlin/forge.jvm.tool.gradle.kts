import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(libs.versions.javaVersion.get().toInt())

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

// No explicitApi() here, deliberately. This convention builds applications, and
// `explicitApi()` is a statement about the stability promise a library makes to consumers.
// An executable has no consumers that link against it, so requiring explicit visibility on
// every declaration would be ceremony with no information behind it. See
// `forge.jvm.library` for the JVM library counterpart that does opt in.

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
