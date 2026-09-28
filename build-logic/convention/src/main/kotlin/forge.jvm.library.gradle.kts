import forge.forgeJavaVersion
import forge.forgeJvmTarget
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // :tools:architecture-tests is a library even though it has one consumer, so it gets
    // the same explicit API discipline as the engine modules. Whether a test module has
    // an "API" is a question about intent, not about who calls it.
    explicitApi()

    jvmToolchain(forgeJavaVersion)

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(forgeJvmTarget))
    }
}

// Konsist is a JVM-only tool: it is an IntelliJ PSI consumer, not a compiler plugin. That
// is why the architecture rules live here and in no other module.
//
// The catalog is reached through the `VersionCatalogsExtension` rather than the generated
// `libs` accessors. A precompiled script plugin is compiled separately from the accessor
// generation, so `libs.foo` does not resolve here; `findLibrary("...")` takes the alias as a
// string and is therefore immune to that, and to the dash-to-dot rule that decides what the
// generated accessor would even have been called.
val catalogs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val junit = catalogs.findLibrary("junit-jupiter").get()
val junitApi = catalogs.findLibrary("junit-jupiter-api").get()
val junitEngine = catalogs.findLibrary("junit-jupiter-engine").get()
val junitLauncher = catalogs.findLibrary("junit-platform-launcher").get()

dependencies {
    testImplementation(kotlin("test"))

    testImplementation(platform(junit))
    testImplementation(junitApi)
    testRuntimeOnly(junitEngine)

    // The launcher is what Gradle uses to talk to the JUnit Platform. It is a runtime
    // dependency; adding it to the compile classpath would be a lie about what the tests
    // need.
    testRuntimeOnly(junitLauncher)
}

tasks.withType<Test>().configureEach {
    // JUnit 5, not Kotest: Konsist derives an assertion's test name by reflecting over the
    // enclosing test function, and that only resolves under JUnit. Kotest is used for the
    // assertions themselves, where the ergonomics are worth it.
    useJUnitPlatform()
}
