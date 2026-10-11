plugins {
    id("forge.kmp.library")
    // `PatchOp`, `Patch`, `Record` and `RecordRef` are persisted types: §26.1's rejected
    // "full snapshot per step" is what the patch replaces, and a patch that cannot cross a
    // process boundary cannot be logged, shipped to a collaborating editor or replayed in a
    // test. The compiler plugin is what turns the `@Serializable` annotations into serializers,
    // so it is applied here for the same reason `:engine:model` applies it rather than leaving
    // the plugin to the one module that happens to encode a document.
    //
    // This adds no dependency: the runtime library already arrives transitively from
    // `:engine:model`, which exposes it as `api`.
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))

    // PLAN §23.3 and `ModuleGraphRules.ALLOWED`: `:engine:editing` may reach the model and
    // the schema and nothing else. No `project(...)` is added here without a reason recorded
    // in this file.
}
