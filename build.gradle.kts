// Plugin aliases come from the shared catalog so every module resolves the same plugin
// marker version as every other Rotalex project. This project adds no version of its own.
//
// The `apply false` declarations keep one classpath for the whole build: a subproject that
// applied an unlisted plugin would append its dependencies instead of replacing them, and
// that is how two copies of the Kotlin plugin end up in one build.
//
// Build settings — jvmTarget, compileSdk, minSdk — are not here and not in
// gradle.properties either. They come from the shared catalog, read by key through
// forge/catalogs.kt's catalogVersion(...), so that every Rotalex project resolves the same
// numbers. This file used to say gradle.properties, which was wrong: nothing there defines
// them, and a reader chasing that comment finds nothing at all.
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

allprojects {
    group = "dev.rotalex.lutter"
    version = "0.1.0-SNAPSHOT"
}
