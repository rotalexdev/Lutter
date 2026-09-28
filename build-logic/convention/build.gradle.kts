plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly, not implementation: these are the *host* build's plugins, and the host
    // build puts them on the script classpath when a module applies its plugins. Bundling
    // them here would give the convention plugins a second, independently resolved copy of
    // the Kotlin and Compose Gradle plugins, which is how a class-cast failure appears at
    // execution time rather than at compile time.
    //
    // `rootLibs` is the shared catalog, imported in build-logic/settings.gradle.kts under
    // that exact name. It is referenced directly: the catalog is named, and an included
    // build gets no automatic access to the main build's catalogs, so the import in that
    // settings file is what makes the accessor exist here.
    compileOnly(rootLibs.kotlin.gradlePlugin)
    compileOnly(rootLibs.compose.compiler.gradlePlugin)
    compileOnly(rootLibs.compose.gradlePlugin)
    compileOnly(rootLibs.android.gradlePlugin)
}
