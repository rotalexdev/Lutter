plugins {
    // P5/P6: the panes are Compose, and two hosts draw them — the desktop window and the
    // Android activity. `forge.kmp.compose` applies `forge.kmp.library` and the Compose compiler,
    // so the panes stay platform-neutral and neither host owns a copy.
    id("lutter.kmp.compose")
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

    // `EditorSessionRecord` (P4) is a `@Serializable` value the host writes through the storage
    // seam. The compiler plugin is already applied above; this is the runtime it needs.
    commonMainApi(rootLibs.kotlinx.serialization.json)

    // `EditorSession.selection` is a `StateFlow`, the same type `DocumentController.state` is, so
    // an editor host can collect both the same way.
    commonMainApi(rootLibs.kotlinx.coroutines.core)

    // The panes draw the runtime's `UiApp` and name `UiRuntime` in their public signatures, so
    // this is an api edge and not an implementation detail.
    commonMainApi(project(":engine:runtime"))
    commonMainImplementation(rootLibs.jetbrains.compose.ui)
    commonMainImplementation(rootLibs.jetbrains.compose.foundation)
    commonMainImplementation(rootLibs.jetbrains.compose.material3)

    // The frame's icons: the plus, the back arrow, the overflow's three dots and the two
    // destinations. `material3` does not ship them, and the alternative was a file of hand-drawn
    // paths that guessed at Material's own geometry — an icon set is a dependency, not a drawing.
    commonMainImplementation(rootLibs.jetbrains.compose.material.icons.extended)

    // Navigation3 is what turns "which screen is this" into a back stack, so Settings can be
    // pushed over the editor and the platform's back gesture can pop it. `navigation3-ui` brings
    // `navigation3-runtime` with it (`rememberNavBackStack`, `NavKey`), which is why the local
    // catalog carries only the UI artifact.
    commonMainImplementation(libs.navigation3.ui)

    // The settings the editor remembers — theme, code size, autosave — live in a DataStore
    // Preferences file both hosts read. The `-core` artifact carries no Android dependency, so
    // the desktop window and the activity share one format and one implementation, and a bug in
    // the way a value is written is one bug rather than two.
    commonMainImplementation(libs.datastore.preferences.core)

    // No `:engine:test-support` edge, and that stays true: this module's tests build the
    // documents they need, the way `:engine:editing`'s planner fixtures do, because a harness
    // that generated documents would make each assertion a statement about the harness.
    //
    // P7's acceptance makes three claims and the third one generates code, which is a
    // per-component binding the synthetic fixture schema deliberately carries none of, so the
    // builtin schema is a test-scope edge and `ModuleGraphRules.ALLOWED_IN_TESTS` holds it with
    // that reason.
    commonTestImplementation(project(":engine:builtins-compose"))

    // The Code screen (`GeneratedCode`, `ui/CodeScreen`) shows what the generator emits for the
    // document on screen, so this one is `commonMain` and not test scope: it is read, never run,
    // and it is the same generator the acceptance test asserts.
    commonMainImplementation(project(":engine:codegen"))
}
