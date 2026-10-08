plugins {
    // Phase 10: a runnable Compose Multiplatform desktop app. `compose.desktop.application` is
    // what creates `run`, so the entry point is named here once and nowhere else.
    id("forge.kmp.compose")
}

dependencies {
    // What the window's own code imports. `BuiltinEngine` carries the pack's assembly, so the
    // sample depends on the module that owns it rather than on five registries it never names.
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:serialization"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins-compose"))
    // The same three artifacts :integration:generated-compile declares: the ui artifact carries
    // `Window` and `application`, and the document on screen is built from foundation and M3.
    commonMainImplementation(rootLibs.jetbrains.compose.ui)
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
    commonMainImplementation(rootLibs.jetbrains.compose.material3)
    // Skiko's native library and the desktop runtime: without it `Window` never paints, and the
    // failure is a `LibraryLoadException` at run time rather than a compile error.
    desktopMainImplementation(compose.desktop.currentOs)
    // Desktop-only: a window is a desktop thing, and Skiko must not reach `commonTest`.
    // `uiTestJUnit4` carries the test API and not the rendering backend, so `currentOs` comes too.
    desktopTestImplementation(compose.desktop.uiTestJUnit4)
    desktopTestImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.rotalex.lutter.samples.desktoppreview.MainKt"
    }
}
