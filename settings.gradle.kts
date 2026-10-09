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
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS. Settings repositories win and a module adding
    // its own only earns a warning — which is the guarantee worth keeping, and it is now the
    // only thing here that matters. It used to be there for a second reason: the Kotlin Gradle
    // plugin registers the Node.js and Yarn download repositories on the *project* for the Wasm
    // and JS targets, and FAIL_ON_PROJECT_REPOS rejected the build outright rather than
    // resolving it ("repository 'Distributions at https://nodejs.org/dist' was added by
    // unknown code"). That workaround, and the two `ivy` blocks that went with it, are gone
    // with the canary targets.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

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

// Lets `project(":engine:model")` be written as `projects.engine.model`, with the
// accessor regenerated when the module graph changes. Nothing uses it yet — the
// module-graph task needs the string path in order to report it — so it is enabled now,
// before the first `projects.` reference, rather than retrofitted after a rename.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
