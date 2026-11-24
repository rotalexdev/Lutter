// Named explicitly rather than relying on default imports: a missing import here is a red build,
// and one line is cheaper than a CI round trip.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Phase 17 P5: the desktop host. `compose.desktop.application` is what creates `run`, so the
    // entry point is named here once and nowhere else.
    id("lutter.kmp.compose")
}

dependencies {
    // The editor itself: session, palette, inspector, preview. `:app:editor` re-exports the
    // engine modules it composes, so this host declares the editor and the one module that owns
    // the builtins pack — the runtime, schema and analysis edges arrive through it.
    commonMainApi(project(":app:editor"))
    commonMainApi(project(":engine:builtins-compose"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:serialization"))

    // What the panes are built from.
    commonMainImplementation(rootLibs.jetbrains.compose.ui)
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
    commonMainImplementation(rootLibs.jetbrains.compose.material3)

    // Skiko's native library and the desktop runtime: without it `Window` never paints, and the
    // failure is a `LibraryLoadException` at run time rather than a compile error.
    desktopMainImplementation(compose.desktop.currentOs)
    // The host's own scope: the preview follows the session, and that collection has to be
    // cancelled with the window.
    desktopMainImplementation(rootLibs.kotlinx.coroutines.core)

    // Desktop-only: a window is a desktop thing. `uiTestJUnit4` carries the test API and not the
    // rendering backend, so `currentOs` comes too.
    desktopTestImplementation(compose.desktop.uiTestJUnit4)
    desktopTestImplementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.rotalex.lutter.desktopapp.MainKt"

        // The same shape the sample uses, for the same reason: `AppImage` is the folder form and
        // its `targetOS` is `currentOS`, so it is the one format every runner can build.
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "LutterEditor"
            packageVersion = "1.0.0"
            description = "Lutter desktop editor"
            vendor = "Lutter"
            modules(
                "jdk.unsupported", // sun.misc.Unsafe: Skiko native interop
                "java.naming",
                "java.management",
                "jdk.zipfs",
            )
        }
    }
}
