package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Metro: a compiler-plugin container whose graph is an interface the plugin implements.
 *
 * Metro is the second kind of container this seam emits, and the first that is not a plain library:
 * the graph a screen resolves against is an interface, and the plugin generates the code behind it.
 * That is why [plugins] exists at all — `metro-runtime` on the classpath proves nothing if
 * `dev.zacsweers.metro` is not applied, and the project then fails on an unimplemented graph.
 *
 * ### Why the graph carries the contributions
 *
 * `metroViewModel()` reads `LocalMetroViewModelFactory`, whose factory is built from
 * `ViewModelGraph`'s `viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>`. That map is
 * a `@Multibinds(allowEmpty = true)` multibinding, so a graph that contributes nothing compiles
 * perfectly and resolves nothing at runtime — the worst failure this seam can produce, and the
 * reason every holder is contributed here rather than relying on a constructor annotation the
 * generated holders do not carry.
 *
 * ### Why only a ViewModel holder
 *
 * The same reason as [Koin]'s: a lookup that moved a page's state out of the page's composition
 * would be a different lifetime policy, not a different container. A plain-class holder is refused
 * by name and the fix is a state strategy.
 */
public data object Metro : InjectionStrategy {

    override val libraries: Set<GeneratedLibrary> = setOf(
        GeneratedLibrary.MetroRuntime,
        GeneratedLibrary.MetroxViewModelCompose,
    )

    override val plugins: Set<GeneratedPlugin> = setOf(GeneratedPlugin.Metro)

    override val emitsModule: Boolean = true

    /** `dev.zacsweers.metro.DependencyGraph`: the annotation the plugin implements. */
    private val dependencyGraph: KotlinSymbol = KotlinSymbol("dev.zacsweers.metro", "DependencyGraph")

    /** `dev.zacsweers.metro.Provides`: one binding per holder. */
    private val provides: KotlinSymbol = KotlinSymbol("dev.zacsweers.metro", "Provides")

    /** `dev.zacsweers.metro.IntoMap`: the contribution this binding is. */
    private val intoMap: KotlinSymbol = KotlinSymbol("dev.zacsweers.metro", "IntoMap")

    /** `dev.zacsweers.metro.createGraph`: builds the implementation in the app root. */
    private val createGraph: KotlinSymbol = KotlinSymbol("dev.zacsweers.metro", "createGraph")

    /** `dev.zacsweers.metrox.viewmodel.ViewModelGraph`: where the ViewModel map is declared. */
    private val viewModelGraph: KotlinSymbol = KotlinSymbol("dev.zacsweers.metrox.viewmodel", "ViewModelGraph")

    /** `dev.zacsweers.metrox.viewmodel.ViewModelKey`: what keys one contributed holder. */
    private val viewModelKey: KotlinSymbol = KotlinSymbol("dev.zacsweers.metrox.viewmodel", "ViewModelKey")

    /** `dev.zacsweers.metrox.viewmodel.metroViewModel`: the lookup a screen's parameter defaults to. */
    private val metroViewModel: KotlinSymbol = KotlinSymbol("dev.zacsweers.metrox.viewmodel", "metroViewModel")

    /** `dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory`: what the root has to provide. */
    private val localFactory: KotlinSymbol =
        KotlinSymbol("dev.zacsweers.metrox.viewmodel", "LocalMetroViewModelFactory")

    /** `androidx.compose.runtime.CompositionLocalProvider`: how the root hands the factory down. */
    /** `dev.zacsweers.metro.provides`: the infix that installs a value into a composition local. */
    private val providesInfix: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "provides")

    /** `dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory`: the local `metroViewModel()` reads. */
    private val localMetroViewModelFactory: KotlinSymbol =
        KotlinSymbol("dev.zacsweers.metrox.viewmodel", "LocalMetroViewModelFactory")

    private val compositionLocalProvider: KotlinSymbol =
        KotlinSymbol("androidx.compose.runtime", "CompositionLocalProvider")

    private fun isPlatformHolder(state: StateStrategy): Boolean =
        GeneratedLibrary.LifecycleViewModelCompose in state.libraries

    override fun refusal(state: StateStrategy): String? =
        if (isPlatformHolder(state)) {
            null
        } else {
            "Injection 'metro' resolves a ViewModel, whose lifetime a ViewModelStore owns; this " +
                "state strategy remembers its holder inside the page's composition instead. Select " +
                "the ViewModel state strategy to use Metro, or drop the injection strategy to keep " +
                "this one."
        }

    override fun holderParameter(page: String, state: StateStrategy, screensPackage: String): KtParam {
        val declared: KtParam = state.pageParameter(page, screensPackage)
        return KtParam(
            declared.name,
            declared.type,
            KtExpr.Call(KtExpr.Ref(KtSymbolRef(metroViewModel)), emptyList()),
        )
    }

    /**
     * `@DependencyGraph interface LutterGraph : ViewModelGraph` with one contribution per holder.
     *
     * An interface and not a class: the plugin implements it, so the declaration it starts from is
     * the abstract half. The provider returns `() -> HomeViewModel` because that is the map's value
     * type — the container stores builders, not instances, and a holder shared by two screens would
     * be exactly the lifetime change this seam refuses to make.
     */
    override fun module(pages: List<KotlinSymbol>): List<KtDeclaration> {
        // A document with no holder still gets the graph: `appRoot` names it unconditionally, and a
        // graph with no contribution is a graph that resolves nothing — not a missing file.
        val members: List<KtDeclaration> = pages.map { holder ->
            val keyed: KtAnnotation = KtAnnotation(
                viewModelKey,
                listOf(KtExpr.Snippet(holder.name + "::class", listOf(holder))),
            )
            KtDeclaration.Function(
                name = providerName(holder),
                annotations = listOf(provides.toAnnotation(), intoMap.toAnnotation(), keyed),
                // The ViewModel, not `() -> ViewModel`: Metro treats a parameter-less function
                // type as a provider type of its own and refuses it as a unique binding, so the
                // wrapper it wants is the one it builds.
                type = KtExpr.Ref(KtSymbolRef(holder)),
                params = emptyList(),
                body = listOf(
                    KtStmt.Return(KtExpr.Call(KtExpr.Ref(KtSymbolRef(holder)), emptyList())),
                ),
            )
        }
        return listOf(
            KtDeclaration.Interface(
                name = GraphName,
                annotations = listOf(dependencyGraph.toAnnotation()),
                sealed = false,
                members = members,
                supertypes = listOf(KtExpr.Ref(KtSymbolRef(viewModelGraph))),
            ),
        )
    }

    /**
     * `val graph = createGraph<LutterGraph>()` then the factory around the content.
     *
     * The graph is built above the content for the same reason a container is started there: a
     * lookup runs in a composable *below* the root, so a graph created inside the first screen
     * would be missing from every later one.
     */
    override fun appRoot(body: List<KtStmt>, modulePackage: String): List<KtStmt> {
        if (body.isEmpty()) return body
        val graphType: KtExpr = KtExpr.Ref(KtSymbolRef(KotlinSymbol(modulePackage, GraphName)))
        val graph: KtStmt = KtStmt.LocalProperty(
            name = "graph",
            type = graphType,
            mutable = false,
            initializer = KtExpr.Call(
                KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(createGraph)), listOf(graphType)),
                emptyList(),
            ),
            delegate = null,
        )
        return listOf(
            graph,
            KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.Ref(KtSymbolRef(compositionLocalProvider)),
                    listOf(
                        KtArg(
                            null,
                            KtExpr.Snippet(
                                "LocalMetroViewModelFactory provides graph.metroViewModelFactory",
                                listOf(localMetroViewModelFactory, providesInfix),
                            ),
                        ),
                    ),
                    KtExpr.Lambda(emptyList(), body),
                ),
            ),
        )
    }

    private fun providerName(holder: KotlinSymbol): String = "provide" + holder.name

    /** `di/Injection.kt`'s one top-level name, prefixed for the same reason [Koin]'s is. */
    internal const val GraphName: String = "LutterGraph"
}
