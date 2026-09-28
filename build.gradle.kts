// Plugin aliases are declared here with `apply false` so that every module resolves the
// same plugin marker version from the catalog. `build-logic` does *not* get its plugins
// from this block: as an included build it resolves its own `compileOnly` copies of the
// same four artifacts from the same catalog, which is why the coordinates in
// `build-logic/convention/build.gradle.kts` are written literally.

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false

    // Gives the root a `check` / `build` / `assemble` lifecycle for the graph guardrail to
    // hook into. Without it, `gradle check` would only ever match the subprojects.
    base

    id("forge.moduleGraph")
}

allprojects {
    group = "dev.rotalex.lutter"
    version = "0.1.0-SNAPSHOT"
}
