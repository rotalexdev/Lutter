plugins {
    id("forge.kmp.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
