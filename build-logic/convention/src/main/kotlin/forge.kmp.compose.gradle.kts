plugins {
    id("forge.kmp.library")
    // Bare `id(...)` for the reason explained in forge.kmp.library: the `plugins` block of
    // a precompiled script plugin is compiled separately from the version-catalog
    // accessors. The versions still flow from the shared catalog, through the
    // compileOnly coordinates in build-logic/convention/build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// No compose.uiTest dependency yet. It is annotated ExperimentalComposeLibrary, so adding
// it means opting in to an unstable API — and Phase 0 has no Compose UI test to run. It
// comes back with the conformance suite in Phase 4, where the first runtime-versus-generated
// comparison actually needs it.
