plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly, not implementation: these are the *host* build's plugins, and the host
    // build puts them on the script classpath when it applies a module's plugins. Bundling
    // them here would give the convention plugins a second, independently resolved copy of
    // the Kotlin and Compose Gradle plugins, which is how a class-cast failure appears at
    // execution time rather than at compile time.
    //
    // The coordinates are written literally on purpose. The version catalog defines
    // *library* aliases; these four are plugin artifacts, so there is no alias to refer
    // to. Their versions still come from the catalog.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:${libs.versions.composeMultiplatform.get()}")
}
