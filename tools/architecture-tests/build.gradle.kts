import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("forge.jvm.library")
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
//
// Konsist is the one exception to "this project has no catalog of its own": it is absent
// from the shared `rootLibs` catalog, so it comes from the local gap-filler, which Gradle
// imports as `libs` because gradle/libs.versions.toml is its default location.
val gapCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Konsist's own assertions (assertTrue / assertFalse) are the only assertion API here.
    // A second assertion library would mean a second test-name convention, and Konsist
    // derives a test name by reflecting over the enclosing function, which only resolves
    // under JUnit.
    testImplementation(gapCatalog.findLibrary("konsist").get())
}
