import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("forge.jvm.library")
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
dependencies {
    // Konsist's own assertions (assertTrue / assertFalse) are the only assertion API here.
    // A second assertion library would mean a second test-name convention, and Konsist
    // derives a test name by reflecting over the enclosing function, which only resolves
    // under JUnit.
    testImplementation(gapCatalog.findLibrary("konsist").get())
}
