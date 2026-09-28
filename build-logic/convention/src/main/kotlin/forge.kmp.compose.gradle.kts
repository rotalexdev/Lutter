plugins {
    id("forge.kmp.library")
    // Bare `id(...)` for the reason explained in forge.kmp.library: the `plugins` block of
    // a precompiled script plugin is compiled separately from the version-catalog
    // accessors. The versions still flow from the shared catalog, through the
    // compileOnly coordinates in build-logic/convention/build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The Compose runtime is a hard requirement of a module that applies the Compose compiler,
// not an optional convenience. Without it the compiler fails with
// "The Compose Compiler requires the Compose Runtime to be on the class path, but no
// compatible version was found" — which is what happened while these modules were still
// empty shells.
//
// foundation and material3 are deliberately absent: they arrive with the components that
// use them, in Phase 5. A module that pulls the whole Compose surface now has a classpath
// nobody asked for and a version conflict nobody can trace.
compose {
    dependencies {
        commonMainImplementation(compose.runtime)
    }
}

// No compose.uiTest dependency yet. It is annotated ExperimentalComposeLibrary, so adding
// it means opting in to an unstable API — and Phase 0 has no Compose UI test to run. It
// comes back with the conformance suite in Phase 4, where the first runtime-versus-generated
// comparison actually needs it.
