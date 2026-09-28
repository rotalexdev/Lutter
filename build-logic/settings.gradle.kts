// The catalog is imported from the parent build so the convention plugins and the
// modules resolve versions from exactly one file. `gradle/libs.versions.toml` is
// deliberately *not* duplicated here.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
