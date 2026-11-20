package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol

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
