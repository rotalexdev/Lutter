// An included build, like `build-logic`: the packaging plugin has to exist before modules can
// apply it by id, so it is built ahead of the main build and gets no automatic access to its
// catalogs. Both are imported here explicitly — `rootLibs` (the shared catalog, which owns the
// kotlinx-serialization version) and `libs` (the gap file, in case anything else is needed).
//
// This build deliberately does **not** depend on any `:tools:*` or `:engine:*` module. An included
// build cannot reach the main build's projects without a cycle (root → this build → root), which is
// why the container format is written out again below rather than imported. `PluginContainerRoundTrip`
// in `:integration:fixture-plugin` reads what this packager produces with the real
// `PluginContainerReader`, and that test is the alarm if the two ever drift.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("rootLibs") {
            from("io.github.alexanderrotela20.catalog:version-catalog:1.2.7")
        }

        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "lutter-plugin"
