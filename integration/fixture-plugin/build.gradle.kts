import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // A JVM library, like `:tools:cli`: a plugin is loaded by a JVM host from a jar, so the
    // module that holds one must compile to the platform the host runs on.
    alias(rootLibs.plugins.convention.jvm.library)
}

kotlin {
    // Explicit API mode. A plugin's entry point is found by name at load time, so it has to be a
    // `public` declaration whether or not anything imports it — the compiler cannot see the caller
    // that will reflectively reach it, which is exactly the case explicit visibility is for.
    explicitApi()

    // ABI tracking, for the same reason `:tools:cli` and `:tools:plugin-container` carry it: the
    // packaging plugin and the CLI both link this module, so a change to a spec it exports is a
    // change to a surface someone else builds against.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(rootLibs.versions.jvmTarget.get()))
    }
}

dependencies {
    // `ModuleGraphRules.ALLOWED`'s row for this module, and no other: the spec vocabulary it
    // contributes and the manifest's identity. No `:engine:analysis`, no `:engine:codegen` — a
    // plugin contributes to a schema, it does not read one.
    implementation(project(":engine:model"))
    implementation(project(":engine:schema"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
