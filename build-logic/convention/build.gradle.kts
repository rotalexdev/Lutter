
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `kotlin-dsl`
}

// The generated `rootLibs` accessor is NOT a `VersionCatalog`: it is a generated class with
// one property per alias, and it has no `findLibrary`. The catalog object itself comes from
// the extension, which is why NIA-shaped builds reach it this way. Taking the alias as a
// string also means this file never depends on the rule that turns dashes into dots when
// generating an accessor — a rule this build cannot test locally.
val rootCatalog = extensions.getByType<VersionCatalogsExtension>().named("rootLibs")

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
    compileOnly(rootCatalog.findLibrary("kotlin-gradlePlugin").get())
    compileOnly(rootCatalog.findLibrary("compose-compiler-gradlePlugin").get())
    compileOnly(rootCatalog.findLibrary("compose-gradlePlugin").get())
    compileOnly(rootCatalog.findLibrary("android-gradlePlugin").get())
}
