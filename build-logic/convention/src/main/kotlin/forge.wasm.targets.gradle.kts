plugins {
    id("forge.kmp.library")
}

// WebAssembly, for the pure engine modules.
//
// The build's supported target set is exactly three: Android, Desktop (JVM) and Wasm. This
// plugin is what puts Wasm on the pure modules, which is where it belongs — their commonMain
// has to stay free of platform APIs, and the Wasm compiler is what proves that. The
// Kotlin/Native and Wasm compilers reject JVM types in commonMain, so a `java.` import
// fails the build here instead of surviving review until somebody announces iOS support.
//
// Applied to the pure modules only, never to Compose modules: the runtime targets Android
// and Desktop, and a Compose module on Wasm is a dependency-resolution problem waiting to
// happen.
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
}
