plugins {
    id("forge.kmp.library")
    // `EditorSessionStore` (P4) persists a session record, and the session's own selection is a
    // value that crosses a process boundary. The compiler plugin is what makes that a
    // `@Serializable` class rather than a hand-written encoder.
    alias(rootLibs.plugins.kotlin.serialization)
}

dependencies {
    // PLAN §32 Phase 17 and `ModuleGraphRules.ALLOWED`: the editor composes the engine and the
    // engine never names the editor. `:engine:editing` is the mutation gateway — a session holds
    // a `DocumentController` and never a copy of the document (§25.1) — and `:engine:schema` is
    // what the palette and the inspector read.
    commonMainApi(project(":engine:model"))
    commonMainApi(project(":engine:schema"))
    commonMainApi(project(":engine:editing"))
    // P3's preview: `decode` is `:engine:serialization`'s, `ResolvedDocument` and the refusal
    // diagnostics are `:engine:analysis`'s. Neither edge draws anything, so the module stays
    // platform-neutral; only the resolver is injected.
    commonMainApi(project(":engine:analysis"))
    commonMainApi(project(":engine:serialization"))

    // `EditorSession.selection` is a `StateFlow`, the same type `DocumentController.state` is, so
    // an editor host can collect both the same way.
    commonMainApi(rootLibs.kotlinx.coroutines.core)

    // No `:engine:test-support` edge: this module's tests build the one document and the one
    // schema they need, the way `:engine:editing`'s planner fixtures do, and a harness that
    // generated documents would make an assertion about the harness rather than about the
    // session. `ModuleGraphRules.ALLOWED_IN_TESTS` has no line for `:app:editor`, and
    // `forge.kmp.library` already wires the test framework the engine modules use.
}
