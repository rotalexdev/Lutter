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
    // The four plugin artifacts are named in the shared catalog, and they are looked up with
    // `findLibrary` rather than the type-safe `rootLibs.kotlin.gradlePlugin` accessor. The
    // alias contains a dash, and the rule that turns dashes into dots when generating an
    // accessor is exactly the kind of detail that is expensive to debug from a CI log and
    // impossible to debug without one: this build has no local toolchain.
    compileOnly(rootLibs.findLibrary("kotlin-gradlePlugin").get())
    compileOnly(rootLibs.findLibrary("compose-compiler-gradlePlugin").get())
    compileOnly(rootLibs.findLibrary("compose-gradlePlugin").get())
    compileOnly(rootLibs.findLibrary("android-gradlePlugin").get())
}
