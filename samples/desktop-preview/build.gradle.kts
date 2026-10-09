// Named explicitly rather than relying on the default imports the Compose plugin contributes:
// a missing import here is a red build, and one line is cheaper than a CI round trip.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

        // `AppImage` is the folder form of the app-image, and its `targetOS` is `currentOS`, so
        // it is the one format every runner can build. `Msi` and `Deb` are the real installers,
        // and each is declared for its own OS only — jpackage refuses to build them anywhere
        // else, which is why the workflow is a matrix and not one job.
        //
        // Without a `targetFormats` declaration `packageDistributionForCurrentOS` has no format
        // to package for the current OS and resolves to UP-TO-DATE having written nothing. That
        // is what the first version of this workflow did, and it looked like success.
        //
        // The name and version are not decoration: jpackage names the launcher and the installer
        // after them, and the defaults would put `desktop-preview` and `unspecified` in every
        // file the maintainer downloads.
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LutterPreview"
            packageVersion = "1.0.0"
            description = "Lutter desktop preview"
            vendor = "Lutter"

            // dpkg wants a maintainer and Compose's default is empty, which shows up as a
            // `lintian` warning in a `.deb` nobody asked for but everyone downloads.
            linux {
                debMaintainer = "Lutter"
            }

            // The jlink runtime is four modules by default — `java.base`, `java.desktop`,
            // `java.logging`, `jdk.crypto.ec` — and that set is jpackage's `--runtime-image`.
            // Anything an app needs beyond those has to be named here or it is simply absent
            // from the packaged `runtime/`, and the absence is a launch-time failure rather
            // than a compile error, so nothing in this build would ever report it.
            //
            // `jdk.unsupported` is `sun.misc.Unsafe`, which Skiko's native interop reaches
            // for. It is the one Compose Desktop applications are known to need and the one
            // its four-module default omits.
            //
            // The rest are named for what breaks without them, and the CI launch probe in
            // `.github/workflows/preview-artifacts.yml` is what proves the list is sufficient
            // — it runs the packaged launcher and fails on a non-zero exit, so a module that
            // goes missing from this list is a red run rather than a bug report.
            modules(
                "jdk.unsupported", // sun.misc.Unsafe: Skiko native interop
                "java.naming", // DNS, and therefore any network call
                "java.management", // JMX; pulled in by tooling that reads the JVM
                "jdk.zipfs", // zip/jar file systems, used by classpath scanners
            )
        }
    }
}
