// `RepositoriesMode` is used below and needs no import: it is part of the Kotlin DSL's
// default imports. Importing it is not an option either, because `pluginManagement` has to
// be the first block in a settings script.
pluginManagement {
    // Convention plugins live in an included build so they can be written in Kotlin DSL
    // and still be applied by id from any module, without a classpath hack.
    includeBuild("build-logic")

    // Phase 16 S9's packager: a Gradle plugin in its own included build, beside `build-logic`.
    // It has to be resolvable at configuration time, which is what an included build gives a
    // plugin applied by id without a classpath hack. Unlike `build-logic` it is only ever applied
    // by a module that ships a plugin, so it stays out of the main build's plugin classpath until
    // something asks for it.
    includeBuild("plugins/lutter-plugin")

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
include(":engine:scaffold")
include(":engine:runtime")
include(":engine:builtins")
include(":engine:builtins-compose")
include(":engine:test-support")
include(":tools:cli")
// ADR-018's container lives in the tools, not the engine: it opens a ZIP and will host the
// loader's payload selection. `:engine:*` stays free of `java.util.zip` and `ClassLoader`.
include(":tools:plugin-container")
include(":tools:architecture-tests")
include(":integration:generated-compile")
// ADR-018 / phase 16 P5: a plugin written outside `:engine:*`, as a third party writes one. It
// ships in a normal module so the packaging plugin (P6) and the CLI (P7) have something real to
// package and load; the container is what makes it a plugin, not the module it lives in.
include(":integration:fixture-plugin")
include(":samples:desktop-preview")
include(":app:editor")
