import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.canary.targets")
}

// The `rootLibs` accessor generated for this project has no `findLibrary`; the catalog
// object does. Resolving through the extension also keeps the alias a string, so no build
// file depends on how Gradle turns dashes into dots when generating accessors.
val rootCatalog = extensions.getByType<VersionCatalogsExtension>().named("rootLibs")

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))

    commonMainApi(rootCatalog.findLibrary("kotlinx-coroutines-core").get())
}
