package dev.rotalex.lutter.scaffold

import dev.rotalex.lutter.codegen.ComposeSnapshotState
import dev.rotalex.lutter.codegen.NavigationStrategy
import dev.rotalex.lutter.codegen.SimpleBackStack
import dev.rotalex.lutter.codegen.StateStrategy
import dev.rotalex.lutter.schema.component.PlatformTag

/**
 * A generated application as a project on disk, rather than as a set of Kotlin files.
 *
 * PLAN §33's post-MVP row pairs the two halves of generation and says which is which:
 * `:engine:codegen` turns a `UiDocument` into Kotlin, and this turns a project into the
 * Gradle structure that Kotlin is compiled in — "project/build-file scaffolding from
 * GeneratedFiles + ProjectSpec". They are separate because they fail differently: a codegen
 * mistake produces quarrelling Kotlin, and a scaffold mistake produces a project with the
 * wrong modules, which no amount of correct Kotlin can repair.
 *
 * Deliberately small, and deliberately not `CodegenOptions`. Everything a scaffold needs is
 * project-level — a name, a package, which runners exist — and everything a generator needs is
 * document-level. Merging them would make the generator's knob list look like project shape.
 *
 * ### Why the strategies are here after all
 *
 * §15's seams are not only a codegen fact: a strategy decides which libraries the emitted Kotlin
 * needs, so a project scaffolded from `NavigationCompose` or `ViewModelState` files that does not
 * depend on `navigation-compose` or `lifecycle-viewmodel-compose` fails on its first build with
 * an unresolved import. The strategies travel as themselves — not as an enum — so the flag that
 * chose one and the project that compiles its output cannot disagree, and the scaffold reads only
 * [NavigationStrategy.libraries] / [StateStrategy.libraries] from them: no emission, no Kotlin.
 * The defaults are the documented default generated app, which depends on nothing.
 *
 * @property appName the application's display name; also the Gradle project name of each app
 *   module, so it must be usable as a directory name.
 * @property basePackage the Kotlin package the generated sources live in, which the modules'
 *   `namespace` and source directories are derived from.
 * @property targetPlatforms the runners this project is generated for — the app modules this
 *   spec emits. A project with only [PlatformTag.Desktop] declares no `androidApp`.
 * @property navigation the strategy the UI was generated under, which decides the catalog
 *   entries and `shared` dependencies the project needs. Defaults to [SimpleBackStack], the
 *   dependency-free default, and its package is [basePackage] because that is where the
 *   generated `Route` and `AppNavigator` live.
 * @property state the state strategy the UI was generated under. Defaults to
 *   [ComposeSnapshotState], whose output needs no library beyond Compose's own.
 */
public data class ProjectSpec(
    public val appName: String,
    public val basePackage: String,
    public val targetPlatforms: Set<PlatformTag> = PlatformTag.ALL,
    public val navigation: NavigationStrategy = SimpleBackStack(basePackage),
    public val state: StateStrategy = ComposeSnapshotState,
)
