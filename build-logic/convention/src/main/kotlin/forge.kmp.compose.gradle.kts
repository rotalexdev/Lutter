plugins {
    id("forge.kmp.library")
    // Bare `id(...)` for the reason explained in forge.kmp.library: the `plugins` block of
    // a precompiled script plugin is compiled separately from the version-catalog
    // accessors. Versions still flow from gradle/libs.versions.toml.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

compose {
    dependencies {
        // The plugin's own dependency handler, not a raw coordinate. A raw
        // `org.jetbrains.compose.ui:ui-test` would carry no version and simply fail to
        // resolve; going through the handler means the test API always matches the Compose
        // Multiplatform version this module compiles against, with nothing to keep in sync.
        //
        // `commonTestImplementation` rather than `testImplementation` because the UI test
        // API is common code: the same golden and semantics test has to run on the desktop
        // and Android runs, not only on the JVM.
        commonTestImplementation(compose.uiTest)
    }
}
