// This is an included build, so it gets no automatic access to the main build's catalogs.
// Both have to be imported here explicitly, or `rootLibs` does not exist in build-logic and
// every convention plugin fails to resolve it.
//
// The shape below is the same one rotalex-root-conventions uses for its own catalog, which
// is why the shared catalog's own convention plugins can resolve their internal versions.
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
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
