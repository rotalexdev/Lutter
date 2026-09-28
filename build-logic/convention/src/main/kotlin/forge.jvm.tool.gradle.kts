import forge.forgeJvmTarget
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(forgeJvmTarget))
    }
}

// One JDK number, applied to both compilers. See forge.jvm.library for why there is no
// `jvmToolchain` and only one `jvmTarget`.
extensions.configure<JavaPluginExtension> {
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
