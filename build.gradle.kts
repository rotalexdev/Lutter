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

allprojects {
    group = "dev.rotalex.lutter"
    version = "0.1.0-SNAPSHOT"

    // A precompiled script plugin cannot see version-catalog accessors: its `plugins` block
    // is extracted and compiled on its own, and so is its body, which is why the
    // convention plugins read versions from here instead of from `rootLibs` directly. The
    // root project is evaluated before any child, so these values are already in place by
    // the time a convention plugin runs.
    //
    // `rootLibs` owns every one of these values. This block only carries them across a
    // boundary that the type-safe accessors do not cross.
    extra["forge.jvmTarget"] = rootLibs.versions.jvmTarget.get()
    extra["forge.androidCompileSdk"] = rootLibs.versions.androidCompileSdk.get().toInt()
    extra["forge.androidMinSdk"] = rootLibs.versions.androidMinSdk.get().toInt()

    // The one value rootLibs does not carry, because it is not a dependency version: it is
    // the JDK the toolchain provisions. AGP 9 needs 17+, Gradle 9 supports 17-27, and 21 is
    // the LTS that both are tested against. `jvmToolchain(21)` makes the build independent
    // of whichever JDK the runner happens to provide.
    extra["forge.javaVersion"] = 21
}
