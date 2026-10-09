plugins {
    id("forge.kmp.library")
    // Records carry @Serializable, and the envelope format is a published contract, so the
    // compiler plugin is applied here rather than only where the codec lives.
    alias(rootLibs.plugins.kotlin.serialization)
}

// `rootLibs` is the shared catalog, named in settings.gradle.kts.
dependencies {
    // `api`, not `implementation`: model types appear in the signature of every other
    // engine module. Marking this implementation-only would leave a downstream module
    // referencing a type it cannot see, which is a confusing failure with an obvious fix.
    commonMainApi(rootLibs.kotlinx.serialization.json)

    // PLAN §33.1: the persistent map `NodeTable` wraps. `implementation` and not `api`,
    // because the map is an internal detail of `NodeTable` and appears in no public
    // signature — the same reasoning that keeps `NodeTableSerializer` internal.
    //
    // From `libs` and not `rootLibs`, which §33.1:2405 asks for. Catalog 1.2.7 carries no
    // entry for this library, so the accessor does not exist; `libs` is the file whose own
    // header says it carries "the coordinates that catalog does not have yet".
    //
    // 0.5.x renamed every copy-returning method to KEEP-0459's participial form and kept
    // the old names as warnings: `putting`, `removing`, `cleared`. `NodeTable.with` and
    // `without` are written directly on that API, so a missed rename is a warning here
    // and a compile error in 0.6.
    commonMainImplementation(libs.kotlinx.immutable)

    // PLAN §23.3: this module may depend on no other *module*. There is no
    // `project(...)` here on purpose, and that absence is enforced rather than merely
    // observed: ModuleGraphRules.ALLOWED records it, and `verifyModuleGraph` fails the build
    // if anyone adds one.
}
