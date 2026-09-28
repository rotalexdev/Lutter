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
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS, and the reason is specific: the Kotlin
    // Gradle plugin registers the Node.js and browser download repositories on the *project*
    // for the Wasm and JS targets, and FAIL_ON_PROJECT_REPOS rejects the build outright
    // rather than resolving it ("repository 'Distributions at https://nodejs.org/dist' was
    // added by unknown code"). PREFER_SETTINGS keeps the guarantee that actually matters —
    // settings repositories win, and a module adding its own only earns a warning.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        google()
        mavenCentral()

        // The Kotlin Gradle plugin resolves the Node.js distribution as the ivy module
        // `org.nodejs:node` from https://nodejs.org/dist, and it declares that repository on
        // each project rather than in settings. With PREFER_SETTINGS the project declaration
        // is ignored, so the distribution has to be declared here or resolution fails with
        // "Could not find org.nodejs:node:25.0.0". Only the wasmJs canary target needs it,
        // but it is needed for the whole build to configure, so it is unconditional.
        ivy("https://nodejs.org/dist") {
            name = "Node Distributions at https://nodejs.org/dist"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
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
