plugins {
    id("forge.jvm.library")
}

// PLAN §23.2 gives this module no project dependencies: an architecture checker that
// depends on the architecture would only be able to see what it already believes.
dependencies {
    testImplementation(libs.konsist)

    // Kotest for assertions only. The runner is JUnit 5, because Konsist derives an
    // assertion's test name by reflecting over the enclosing test function and that only
    // resolves under JUnit.
    testImplementation(libs.kotest.assertions.core)
}
