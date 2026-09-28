plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.canary.targets")
    // Records carry @Serializable, and the envelope format is a published contract, so the
    // compiler plugin is applied here rather than only where the codec lives.
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    // `api`, not `implementation`: model types appear in the signature of every other
    // engine module. Marking this implementation-only would leave a downstream module
    // referencing a type it cannot see, which is a confusing failure with an obvious fix.
    // `findLibrary` rather than the `rootLibs.kotlinx.serialization.json` accessor. The
    // alias contains dashes, and the dash-to-dot rule that decides what the generated
    // accessor is called is not something to guess at in a build that has no local
    // toolchain to test the guess in.
    commonMainApi(rootLibs.findLibrary("kotlinx-serialization-json").get())

    // PLAN §23.3: this module may depend on no other *module*. There is no
    // `project(...)` here on purpose, and that absence is enforced rather than merely
    // observed: ModuleGraphRules.ALLOWED records it, and `verifyModuleGraph` fails the build
    // if anyone adds one.
}
