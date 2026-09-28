import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // The shared Rotalex JVM convention, not a local one. It already does the JDK work
    // correctly: `jvmTarget` from the shared catalog applied to both Java and Kotlin, which
    // is what a hand-rolled version kept getting wrong here.
    alias(rootLibs.plugins.convention.jvm.library)
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
//
// Konsist is the one exception to "this project has no catalog of its own": it is absent from
// the shared catalog, so it comes from the local gap-filler, which Gradle imports as `libs`
// because gradle/libs.versions.toml is its default location.
val gapCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val junit = gapCatalog.findLibrary("junit-jupiter").get()
val junitApi = gapCatalog.findLibrary("junit-jupiter-api").get()
val junitEngine = gapCatalog.findLibrary("junit-jupiter-engine").get()
val junitLauncher = gapCatalog.findLibrary("junit-platform-launcher").get()
val konsist = gapCatalog.findLibrary("konsist").get()

dependencies {
    testImplementation(kotlin("test"))

    testImplementation(platform(junit))
    testImplementation(junitApi)
    testRuntimeOnly(junitEngine)

    // The launcher is what Gradle uses to talk to the JUnit Platform. It is a runtime
    // dependency; adding it to the compile classpath would be a lie about what the tests
    // need.
    testRuntimeOnly(junitLauncher)

    // Konsist's own assertions (assertTrue / assertFalse) are the only assertion API here.
    // A second assertion library would mean a second test-name convention, and Konsist
    // derives a test name by reflecting over the enclosing function, which only resolves
    // under JUnit.
    testImplementation(konsist)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
