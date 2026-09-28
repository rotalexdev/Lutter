// `RepositoriesMode` is used below and needs no import: it is part of the Kotlin DSL's
// default imports. Importing it is not an option either, because `pluginManagement` has to
// be the first block in a settings script.
pluginManagement {
    // Convention plugins live in an included build so they can be written in Kotlin DSL
    // and still be applied by id from any module, without a classpath hack.
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // A module that declares its own repository can silently shadow the catalog's
    // resolution and pin a version somewhere the catalog cannot see.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }

    versionCatalogs {
        // The shared, org-wide catalog. This is the single source of truth for versions
        // across Rotalex projects; do not fork it here and do not add a second copy of any
        // version it already owns.
        //
        // The name MUST be `rootLibs`: the convention plugins in
        // rotalex-rootconventions resolve their own internal dependencies and SDK versions
        // through that exact catalog name.
        create("rootLibs") {
            from("io.github.alexanderrotela20.catalog:version-catalog:1.2.7")
        }

        // There is deliberately no `create("libs")` here. Gradle already imports
        // gradle/libs.versions.toml as `libs` because that is its default location, and
        // declaring it a second time is the "too-many-import-invocation" catalog error.
    }
}

rootProject.name = "forge-engine"

// Typesafe project accessors are deliberately off (decision A2). They are a Gradle
// feature preview, and this build is verified by CI alone: a cold, unproven build should
// not also carry a preview. Module references use project(":engine:model"), which is what
// the module-graph task needs anyway. Re-enable once CI has been green for a while.

include(":engine:model")
include(":engine:schema")
include(":engine:serialization")
include(":engine:interpreter")
include(":engine:analysis")
include(":engine:editing")
include(":engine:codegen")
include(":engine:runtime")
include(":engine:builtins")
include(":engine:builtins-compose")
include(":engine:test-support")
include(":tools:cli")
include(":tools:architecture-tests")
include(":integration:generated-compile")
include(":samples:desktop-preview")
