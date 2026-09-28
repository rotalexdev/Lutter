import org.gradle.api.artifacts.VersionCatalogsExtension

// Plugin aliases come from the shared catalog so every module resolves the same plugin
// marker version as every other Rotalex project. This project adds no version of its own.
//
// The `apply false` declarations keep one classpath for the whole build: a subproject that
// applied an unlisted plugin would append its dependencies instead of replacing them, and
// that is how two copies of the Kotlin plugin end up in one build.
plugins {
    alias(rootLibs.plugins.kotlin.multiplatform) apply false
    alias(rootLibs.plugins.kotlin.jvm) apply false
    alias(rootLibs.plugins.kotlin.serialization) apply false
    alias(rootLibs.plugins.compose.compiler) apply false
    alias(rootLibs.plugins.compose) apply false
    alias(rootLibs.plugins.android.kotlin.multiplatform.library) apply false

    // Gives the root a `check` / `build` / `assemble` lifecycle for the graph guardrail to
    // hook into. Without it, `gradle check` would only ever match the subprojects.
    base

    id("forge.moduleGraph")
}

// Resolved here, in the root project's own scope, and then handed to every project as a
// plain value.
//
// Two reasons, and both are load-bearing:
//
//  * A precompiled script plugin cannot see version-catalog accessors at all — its `plugins`
//    block is extracted and compiled separately, and the accessors do not exist in its body
//    either. The convention plugins read these values from project extras instead.
//  * Reading `rootLibs` *inside* `allprojects { }` looks the catalog up as an extension on
//    the child project, which does not have one: only the root does. Resolving first and
//    assigning the result avoids that entirely.
val rootCatalog = extensions.getByType<VersionCatalogsExtension>().named("rootLibs")

fun catalogVersion(alias: String): String = rootCatalog.findVersion(alias).get().requiredVersion

allprojects {
    group = "dev.rotalex.lutter"
    version = "0.1.0-SNAPSHOT"

    // Every one of these is owned by `rootLibs`. This block only carries them across a
    // boundary the type-safe accessors do not cross.
    extra["forge.jvmTarget"] = catalogVersion("jvmTarget")
    extra["forge.androidCompileSdk"] = catalogVersion("androidCompileSdk").toInt()
    extra["forge.androidMinSdk"] = catalogVersion("androidMinSdk").toInt()
}
