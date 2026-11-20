package dev.rotalex.lutter.codegen

/**
 * The libraries a generated project has to depend on for a strategy's emission to compile.
 *
 * A strategy's Kotlin is not free-standing: `NavigationCompose`'s screens call
 * `androidx.navigation.compose`'s controller, `Navigation3`'s root renders `NavDisplay`, and
 * `ViewModelState`'s screens read `viewModel()`. Which of those a document *needs* is therefore a
 * property of the strategies it was generated under, and a scaffold that emitted the Kotlin
 * without the dependency would write a project whose first build fails on an unresolved import —
 * the one failure mode §32 Phase 14 calls out as the reason the two halves of generation exist.
 *
 * ### Keys, not coordinates
 *
 * This is a vocabulary, not a catalog. A coordinate carries a version, and the versions a
 * generated project pins come from this build's own catalog (§32 Phase 14: "so a generated app
 * never pins a version the engine did not test"); `:engine:scaffold` is the single place that
 * turns one of these keys into a `gradle/libs.versions.toml` entry. An enum rather than a string
 * so the mapping is exhaustive in the scaffold: a library added here that no catalog entry
 * answers is a compile error, not a generated project that fails to resolve.
 *
 * A strategy that needs nothing — [SimpleBackStack], [ComposeSnapshotState] — declares the empty
 * set, which is what keeps the default generated app dependency-free.
 */
public enum class GeneratedLibrary {

    /** `org.jetbrains.androidx.navigation:navigation-compose`: [NavigationCompose]'s controller and `NavHost`. */
    NavigationCompose,

    /** `org.jetbrains.androidx.navigation3:navigation3-ui`: [Navigation3]'s `NavDisplay`, `NavEntry` and `NavKey`. */
    Navigation3,

    /**
     * `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`: `viewModel()` and the
     * `ViewModel` its factory builds, which is the whole of [ViewModelState]'s runtime.
     */
    LifecycleViewModelCompose,

    /**
     * `io.insert-koin:koin-core`: [Koin]'s `module` and `koinConfiguration`, and the `viewModel`
     * declaration that comes with them. Its generated holder takes no constructor arguments in v1,
     * so nothing here reads the container's resolution — the artifact is what the *declaration*
     * needs, and a declaration compiled against nothing is a file with no imports to satisfy.
     */
    KoinCore,

    /**
     * `io.insert-koin:koin-compose-viewmodel`: `KoinApplication` and `koinViewModel()`, the two
     * composables [Koin] puts in the generated app.
     *
     * It carries `lifecycle-viewmodel-compose` of its own, at a version the catalog overrides with
     * [LifecycleViewModelCompose]'s — the generated catalog pins one, so the project resolves one.
     */
    KoinComposeViewModel,
}
