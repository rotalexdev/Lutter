import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The same shared JVM convention `:tools:cli` uses. No `application` here: this module is a
    // library the CLI links against, which is also why it may be swapped for the packaging
    // plugin's own build without touching a command.
    alias(rootLibs.plugins.convention.jvm.library)
}

kotlin {
    // Explicit API mode, for the reason `:tools:cli` states: PLAN §36 asks for it everywhere, and
    // the shared JVM convention does not set it.
    explicitApi()

    // ABI tracking. `:tools:plugin-container` is a published surface in the sense that matters —
    // the packaging plugin and the CLI both link it — so its dumps are committed and checked like
    // any engine module's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(rootLibs.versions.jvmTarget.get()))
    }
}

dependencies {
    // ADR-018: the container reads a manifest, so it needs the manifest's own module and nothing
    // else. `ModuleGraphRules.ALLOWED` carries that row; this list is the same row.
    implementation(project(":engine:model"))

    // The JSON codec the manifest is written with. `rootLibs`'s catalog entry, the same one
    // `:engine:model` exposes as `api`; this module keeps it `implementation` because no
    // signature here names a codec type.
    implementation(rootLibs.kotlinx.serialization.json)

    // `kotlin.test` for the assertion vocabulary — `assertFailsWith` is the one this module
    // needs and JUnit's own assertions do not have — and JUnit from the gap-filler catalog as
    // the runner, the pair `:tools:architecture-tests` settled on. `kotlin.test` resolves its
    // JUnit 5 variant because `useJUnitPlatform()` and the Jupiter engine are both below.
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.junit.jupiter))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
