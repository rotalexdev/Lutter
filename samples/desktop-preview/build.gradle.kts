plugins {
    // Phase 0 is a shell. The `application` plugin, the desktop `main` and the Compose
    // window are all Phase 10: this becomes a runnable Compose Multiplatform desktop app
    // that renders a JSON document once there is a document to render, and until then a
    // runnable app would only prove that a window can open.
    id("forge.kmp.compose")
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:serialization"))
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:runtime"))
    commonMainApi(project(":engine:builtins"))
    commonMainApi(project(":engine:builtins-compose"))
    commonMainApi(project(":engine:test-support"))
}
