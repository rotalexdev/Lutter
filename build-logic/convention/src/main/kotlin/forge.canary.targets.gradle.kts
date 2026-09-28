import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("forge.kmp.library")
}

// Canary targets (PLAN §21.1). While `commonMain` stays free of platform APIs these cost
// almost nothing to declare, and they turn "someone imported java.io into the domain" into
// a build failure instead of a review comment that may not happen.
//
// Applied to the pure modules only, never to Compose modules: PLAN §21.2 keeps the runtime
// on Android + Desktop until iOS/Wasm *runtime* support lands post-MVP, and a Compose
// module on Wasm is a dependency-resolution problem waiting to happen.
//
// They are never published. PLAN §21.1: "Do not publish them until supported."
kotlin {
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
}
