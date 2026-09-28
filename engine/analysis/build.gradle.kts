plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.canary.targets")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
}
