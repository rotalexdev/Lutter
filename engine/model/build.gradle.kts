plugins {
    id("forge.kmp.library")
    // PLAN §21.1 canary: proving this module's commonMain stays free of
    // platform APIs, so JVM leakage fails the build instead of passing review.
    id("forge.canary.targets")
    // Records carry @Serializable, and the envelope format is a published contract, so the
    // compiler plugin is applied here rather than only where the codec lives.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // `api`, not `implementation`: model types appear in the signature of every other
    // engine module. Marking this implementation-only would leave a downstream module
    // referencing a type it cannot see, which is a confusing failure with an obvious fix.
    commonMainApi(libs.kotlinx.serialization.json)
    commonMainApi(libs.kotlinx.collections.immutable)

    // PLAN §23.3: this module may depend on no other *module*. There is no
    // `project(...)` here on purpose, and that absence is enforced rather than merely
    // observed: ModuleGraphRules.ALLOWED records it, and `verifyModuleGraph` fails the build
    // if anyone adds one.
}
