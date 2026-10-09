plugins {
    id("forge.kmp.library")
}

// The `rootLibs` accessor generated for this project has no `findLibrary`; the catalog
// object does. Resolving through the extension also keeps the alias a string, so no build
// file depends on how Gradle turns dashes into dots when generating accessors.
dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))

    commonMainApi(rootLibs.kotlinx.coroutines.core)
}
