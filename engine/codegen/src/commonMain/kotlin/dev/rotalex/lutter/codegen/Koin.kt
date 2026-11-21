package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Koin: a runtime container that builds the holder a screen asks for, in a shared source set.
 *
 * Koin is the one of the three that is a plain library rather than a compiler plugin, so it is the
 * one that can be wired with nothing but source: a `module` that declares the holders, an
 * `KoinApplication` that starts it, and `koinViewModel()` where the screen would otherwise call
 * `viewModel()`. That is why it is the first container this seam emits rather than the last.
 *
 * ### Why only a ViewModel holder
 *
 * `koinViewModel()` resolves a `ViewModel` and gives it the `ViewModelStore`'s lifetime. Koin can
 * also resolve a plain class with `koinInject()`, and doing so here would be a silent change of
 * meaning: §12.1 gives a page's state to the page's composition, `remember` re-runs per screen, and
 * a container-resolved instance is either shared by every screen or rebuilt under a key nothing
 * chose. A container that quietly moves a page's state out of the page's lifetime is not opting the
 * document into Koin; it is opting the document into a different lifetime policy. So a plain-class
 * holder is refused by name ([refusal]) and the fix is a state strategy, not a different lookup.
 *
 * ### What it costs
 *
 * Two artifacts, both pinned by the generated catalog: `koin-core` for `module` and
 * `koinConfiguration` (`viewModel` comes with it), and `koin-compose-viewmodel` for
 * `KoinApplication` and `koinViewModel`. `koin-compose-viewmodel` also carries
 * `lifecycle-viewmodel-compose`; the catalog pins the newer of the two, so the generated project
 * builds against one ViewModel API and not two.
 */
public data object Koin : InjectionStrategy {

    /** `:engine:scaffold` turns these into catalog entries; see [GeneratedLibrary]. */
    override val libraries: Set<GeneratedLibrary> = setOf(
        GeneratedLibrary.KoinCore,
        GeneratedLibrary.KoinComposeViewModel,
    )

    override val emitsModule: Boolean = true

    /**
     * `org.koin.core.module.dsl.viewModel`: the container's declaration for one holder.
     *
     * Reached as an extension on `Module`, with the holder as its type argument so the container
     * keys by exactly the class the screen asks for.
     */
    private val viewModel: KotlinSymbol = KotlinSymbol("org.koin.core.module.dsl", "viewModel")

    /** `org.koin.dsl.module`: the declaration block [module] fills. */
    private val moduleOf: KotlinSymbol = KotlinSymbol("org.koin.dsl", "module")

    /** `org.koin.dsl.koinConfiguration`: what [`appRoot`]'s root hands `KoinApplication`. */
    private val koinConfiguration: KotlinSymbol = KotlinSymbol("org.koin.dsl", "koinConfiguration")

    /** `org.koin.compose.KoinApplication`: starts the container and provides it to the content. */
    private val koinApplication: KotlinSymbol = KotlinSymbol("org.koin.compose", "KoinApplication")

    /** `org.koin.core.module.Module`: the block's type, which explicit API mode requires. */
    private val moduleType: KotlinSymbol = KotlinSymbol("org.koin.core.module", "Module")

    /** `org.koin.compose.viewmodel.koinViewModel`: the lookup a screen's parameter defaults to. */
    private val koinViewModel: KotlinSymbol = KotlinSymbol("org.koin.compose.viewmodel", "koinViewModel")

    /** Refused for a holder the composition remembers: see [isPlatformHolder]. */
    override fun refusal(state: StateStrategy): String? =
        if (isPlatformHolder(state)) {
            null
        } else {
            "Injection 'koin' resolves a ViewModel, whose lifetime a ViewModelStore owns; this " +
                "state strategy remembers its holder inside the page's composition instead. Select " +
                "the ViewModel state strategy to use Koin, or drop the injection strategy to keep " +
                "this one."
        }

    /**
     * The state strategy's parameter, with the container's lookup as its default.
     *
     * The name and the type stay the state strategy's — the screen still takes
     * `viewModel: HomeViewModel`, and its reads still hop through `viewModel.state` — because the
     * container changes who *constructs* the holder, not what the screen is given.
     */
    override fun holderParameter(page: String, state: StateStrategy, screensPackage: String): KtParam {
        val declared: KtParam = state.pageParameter(page, screensPackage)
        return KtParam(
            declared.name,
            declared.type,
            KtExpr.Call(KtExpr.Ref(KtSymbolRef(koinViewModel)), emptyList()),
        )
    }

    /**
     * `viewModel<HomeViewModel> { HomeViewModel() }` for every page, in one `module`.
     *
     * The constructor call and not `viewModelOf(::HomeViewModel)`: a reference to a generated class
     * would need the IR to spell a callable reference, and the lambda says the same thing with the
     * vocabulary already here. The holder takes no arguments today because v1 declares no
     * dependencies of its own; DI v2 is where a constructor gains some and this lambda gains `get()`s.
     */
    override fun module(pages: List<KotlinSymbol>): List<KtDeclaration> {
        val declarations: List<KtStmt> = pages.map { holder ->
            KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(viewModel)), listOf(KtExpr.Ref(KtSymbolRef(holder)))),
                    emptyList(),
                    KtExpr.Lambda(
                        emptyList(),
                        listOf(KtStmt.Expr(KtExpr.Call(KtExpr.Ref(KtSymbolRef(holder)), emptyList()))),
                    ),
                ),
            )
        }
        return listOf(
            KtDeclaration.Property(
                name = ModuleName,
                annotations = emptyList(),
                // Spelled, because the project compiles under explicit API mode and a `val`
                // without a type is refused there. It is also what makes `LutterModule` a `Module`
                // at the next file's use site rather than an inferred type that file cannot name.
                type = KtExpr.Ref(KtSymbolRef(moduleType)),
                mutable = false,
                initializer = KtExpr.Call(
                    KtExpr.Ref(KtSymbolRef(moduleOf)),
                    emptyList(),
                    KtExpr.Lambda(emptyList(), declarations),
                ),
                delegate = null,
                getter = null,
            ),
        )
    }

    /**
     * `KoinApplication(configuration = koinConfiguration { modules(LutterModule) }) { body }`.
     *
     * `KoinApplication` is what starts a container in Compose without an `Application` class, which
     * is what a generated library can do: it owns a composable, not a process.
     */
    override fun appRoot(body: List<KtStmt>, modulePackage: String): List<KtStmt> {
        // A document with no pages has nothing that could look anything up, and an empty
        // `KoinApplication` would start a container for content that never asks for one.
        if (body.isEmpty()) return body
        val configuration: KtExpr = KtExpr.Call(
            KtExpr.Ref(KtSymbolRef(koinConfiguration)),
            emptyList(),
            KtExpr.Lambda(
                emptyList(),
                listOf(
                    KtStmt.Expr(
                        KtExpr.Call(
                            KtExpr.Name("modules"),
                            listOf(KtArg(null, KtExpr.Ref(KtSymbolRef(KotlinSymbol(modulePackage, ModuleName))))),
                        ),
                    ),
                ),
            ),
        )
        return listOf(
            KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.Ref(KtSymbolRef(koinApplication)),
                    listOf(KtArg("configuration", configuration)),
                    KtExpr.Lambda(emptyList(), body),
                ),
            ),
        )
    }

    /**
     * `di/Injection.kt`'s one top-level name.
     *
     * Prefixed with the engine rather than called `AppModule`: a host's own modules are the first
     * thing it writes, and a generated name it may not take is one it cannot have.
     */
    internal const val ModuleName: String = "LutterModule"
}
