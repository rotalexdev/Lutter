plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.wasm.targets")
    // Bindings carry @Serializable and their tags are contract (§7.2), so the
    // compiler plugin is applied here, as in :engine:model.
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(rootLibs.kotlinx.serialization.json)
}
