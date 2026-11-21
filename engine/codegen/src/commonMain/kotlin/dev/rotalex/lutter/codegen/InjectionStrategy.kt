package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PlatformTag

/**
 * Who builds a page's state holder, and what the generated project must carry for that to work.
 *
 * §12.1's holder row answers "what holds a page's state"; this answers "who constructs it". They
 * are separate questions because they are separate failures: a state strategy decides the holder's
 * shape (`@Stable` class, `ViewModel`), and an injection strategy decides whether the composition
 * calls `remember`, `viewModel`, or a container's lookup. A project can pick either independently
 * — snapshot state under Koin is a plain class the container builds, ViewModel state without a
 * container is `viewModel()`'s own factory — which is why one enum cannot stand in for both.
 *
 * ### What v1 answers, and what it deliberately does not
 *
 * Exactly the holder lookup, and the artifact list that lookup needs. App-declared dependencies —
 * use cases, repositories, anything a document would have to describe — are DI v2 and would need a
 * document concept (`UiDocument.dependencies`), a diff record and a validation rule; v1 wires only
 * what the engine itself emits, so it changes no document shape.
 *
 * ### Why `None` is not a special case
 *
 * [NoInjection] does not emit "no lookup"; it delegates to the state strategy, and the state
 * strategy already knows how its own holder is obtained. That keeps the default pair's emission
 * byte-identical to the runs before this interface existed — which is the only proof a seam
 * introduction can offer.
 *
 * ### The four other questions, and why each defaults to nothing
 *
 * A container is more than a lookup: it needs somewhere to declare its bindings ([module]), a
 * place to start ([appRoot]), and it can refuse a holder it cannot build ([refusal]). Every one of
 * them defaults to the no-op, because [NoInjection] needs none of them and a container that needs
 * only some of them should not have to spell the rest out. [emitsModule] exists separately from
 * [module] because the *plan* decides the file list before anything is emitted, and the plan
 * must not build a strategy's IR to find out whether there is a file.
 */
public interface InjectionStrategy {

    /**
     * The default expression for [page]'s screen parameter: who builds the holder, spelled.
     *
     * [state] is the strategy whose holder this is, and it is passed rather than looked up because
     * the lookup is a composition and not a constant: `viewModel()` is the state strategy's answer
     * to "how is my holder obtained", and no container in the project inherits that answer.
     * [screensPackage] qualifies the factory a plain-class holder needs; a container's lookup names
     * no generated declaration and ignores it.
     */
    public fun holderParameter(page: String, state: StateStrategy, screensPackage: String): KtParam

    /**
     * The libraries the generated project needs so this strategy's lookup resolves.
     *
     * The same contract as `NavigationStrategy.libraries` — see [GeneratedLibrary] — and empty by
     * default, which is the honest answer for [NoInjection]: the default generated app depends on
     * nothing beyond Compose.
     */
    public val libraries: Set<GeneratedLibrary>
        get() = emptySet()

    /**
     * Why [state]'s holder cannot be this container's to build, or null when it can.
     *
     * A container builds what it can construct; one whose accessor is scoped to a holder the
     * platform owns has nothing to say to a holder the composition remembers. Null is the default
     * because a container with no such requirement — and [NoInjection], which *is* the state
     * strategy — refuses nothing.
     */
    public fun refusal(state: StateStrategy): String? = null

    /**
     * Why this container cannot serve a document that targets [targets], or null when it can.
     *
     * The second axis exists because a container can be as strict about *platforms* as about
     * holders: [Hilt] is an Android artifact, so a document that also targets iOS, desktop or web
     * cannot compile its lookup no matter which state strategy it selects. Defaulting to the
     * holder-only refusal keeps the two containers that have no platform requirement — [Koin] and
     * [Metro] — spelling nothing new.
     */
    public fun refusal(state: StateStrategy, targets: Set<PlatformTag>): String? = refusal(state)

    /**
     * Whether this container puts bindings in a file of its own, which the plan places at
     * `di/Injection.kt`.
     *
     * Asked before emission, so it is a plain property rather than a nullable [module]: a plan that
     * had to build a strategy's declarations to decide the file list would emit them twice.
     */
    /**
     * The Gradle plugins the generated project must apply, not merely depend on.
     *
     * Empty for a container that is a plain library: [Koin] is on the classpath and that is the
     * whole of it. A container whose graph is *generated* — [Metro], and Hilt when it lands — needs
     * a compiler plugin, and a project that depends on the runtime without applying the plugin
     * builds into an unimplemented graph.
     */
    public val plugins: Set<GeneratedPlugin> get() = emptySet()

    public val emitsModule: Boolean
        get() = false

    /**
     * `di/Injection.kt`'s declarations: the bindings a container needs before [holderParameter]'s
     * lookup finds anything.
     *
     * [pages] are the holders this document generated, as symbols, so a strategy never has to name
     * them: it declares what the state strategy declared, in its own vocabulary.
     */
    public fun module(pages: List<KotlinSymbol>): List<KtDeclaration> = emptyList()

    /**
     * [body] with whatever this container needs around the app's content.
     *
     * Around and not inside: a lookup runs in a composable *below* the container, so a container
     * that started inside the first screen would be missing from every later one. Identity is the
     * default and the honest answer for a run that selected no container.
     *
     * [modulePackage] is where [module]'s declarations were emitted, because starting a container
     * is naming what it was given, and that name lives in another file of the run.
     */
    public fun appRoot(body: List<KtStmt>, modulePackage: String): List<KtStmt> = body
}

/**
 * No container: the state strategy's own answer, unchanged.
 *
 * The default, and the reason a project that selects nothing keeps Phase 14's zero-dependency
 * catalog. `holderParameter` forwards rather than re-deriving, so `rememberHomeScreenState()` and
 * `viewModel()` arrive here exactly as the state strategy wrote them.
 */
public data object NoInjection : InjectionStrategy {

    override fun holderParameter(page: String, state: StateStrategy, screensPackage: String): KtParam =
        state.pageParameter(page, screensPackage)
}
