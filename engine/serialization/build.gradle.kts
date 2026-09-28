plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.canary.targets")
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(rootLibs.findLibrary("kotlinx-serialization-json").get())

    // kotlinx-io is in the shared catalog and deliberately not wired into any
    // source set yet. Its FileSystem API is still experimental, and nothing here needs
    // storage: the codec works on strings. The first thing that will need a real
    // FileSystem is a `nonWebMain` document store, and at that point the experimental opt-in
    // is a decision made with the API in hand rather than a blanket warning suppression
    // added in advance.
}
