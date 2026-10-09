plugins {
    id("forge.kmp.library")
    // Bindings carry @Serializable and their tags are contract (§7.2), so the
    // compiler plugin is applied here, as in :engine:model.
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(rootLibs.kotlinx.serialization.json)
}
