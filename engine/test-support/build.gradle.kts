plugins {
    id("forge.kmp.library")
}

// Test support is allowed to see every engine module, which is the point of it: a fake
// environment needs the interpreter's contracts, a golden test needs the model, and a
// render test needs the runtime. It is also the module that would notice a cycle forming,
// because the graph checker and the compilation classpath now see the same shape.
dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:serialization"))
    commonMainApi(project(":engine:interpreter"))
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:editing"))
    commonMainApi(project(":engine:codegen"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins"))
    commonMainApi(project(":engine:builtins-compose"))
}

// The §29 harness is opt-in by design (`PerformanceHarnessTest`'s KDoc): an unset
// property is the harness's own `Off` spelling, so no check build pays the five-row
// budget. `-Pforge.benchmark=measure|record|assert` is the whole of the wiring, which
// is what the nightly job and a developer's own machine both drive it through.
tasks.withType<Test>().configureEach {
    systemProperty("forge.benchmark", providers.gradleProperty("forge.benchmark").orNull.orEmpty())
}
