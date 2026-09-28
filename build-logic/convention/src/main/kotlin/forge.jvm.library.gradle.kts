import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // :tools:architecture-tests is a library even though it has one consumer, so it gets
    // the same explicit API discipline as the engine modules. Whether a test module has
    // an "API" is a question about intent, not about who calls it.
    explicitApi()

    jvmToolchain(libs.versions.javaVersion.get().toInt())

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

// Konsist is a JVM-only tool: it is an IntelliJ PSI consumer, not a compiler plugin. That
// is why the architecture rules live here and in no other module.
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

tasks.withType<Test>().configureEach {
    // JUnit 5, not Kotest: Konsist derives an assertion's test name by reflecting over the
    // enclosing test function, and that only resolves under JUnit. Kotest is used for the
    // assertions themselves, where the ergonomics are worth it.
    useJUnitPlatform()
}
