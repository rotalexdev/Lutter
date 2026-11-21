package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PlatformTag

/**
 * Hilt: a container that exists only on Android, and the strictest of the three this seam emits.
 *
 * ### Why the refusal has a second axis
 *
 * Koin and Metro refuse a *holder* they cannot build; Hilt refuses a *document* it cannot serve.
 * Hilt has no Kotlin Multiplatform story — its ViewModel integration is an Android artifact — so a
 * document that targets iOS, desktop or web cannot compile `hiltViewModel()` at all. That is not a
 * fact about the state strategy, and it is not something a document node can be pointed at: the
 * fix is a different target set or a different container, which is why [refusal] carries the
 * targets as well as the holder.
 *
 * ### What this slice does, and what it does not
 *
 * The lookup and its artifacts, exactly as [Koin] and [Metro] were sliced. The decorator that the
 * generated holder needs — `@HiltViewModel` on the class and `@Inject` on its constructor — plus
 * the `@HiltAndroidApp` application and the `@AndroidEntryPoint` activity, arrive in the next
 * slice along with the two Gradle plugins: a project that applies `hilt-android` without the
 * compiler does not build, and a holder without `@HiltViewModel` does not resolve either. Emitting
 * half of that pair would be a generated project that fails, which this seam must never produce.
 */
public data object Hilt : InjectionStrategy {

    /**
     * `androidx.hilt:hilt-android`'s lookup, plus the navigation-compose integration that spells
     * `hiltViewModel()`.
     */
    override val libraries: Set<GeneratedLibrary> = setOf(
        GeneratedLibrary.HiltAndroid,
        GeneratedLibrary.HiltNavigationCompose,
    )

    /** `hiltViewModel()` for [page]'s screen parameter; the holder's type is the state's. */
    override fun holderParameter(
        page: String,
        state: StateStrategy,
        screensPackage: String,
        external: List<ExternalAdapter>,
    ): KtParam {
        val declared: KtParam = state.pageParameter(page, screensPackage, external)
        return KtParam(
            declared.name,
            declared.type,
            KtExpr.Call(KtExpr.Ref(KtSymbolRef(hiltViewModel)), emptyList()),
        )
    }

    /**
     * Android-only, and a `ViewModel`, or nothing.
     *
     * Both halves are load-bearing: `hiltViewModel()` is an Android artifact, so a portable target
     * set cannot carry it, and a holder the composition remembers is not a `ViewModel` Hilt could
     * inject in the first place.
     */
    override fun refusal(state: StateStrategy, targets: Set<PlatformTag>): String? {
        if (targets != setOf(PlatformTag.Android)) {
            return "Injection 'hilt' is Android-only: Hilt has no Kotlin Multiplatform ViewModel " +
                "integration, so a document that targets any other platform cannot compile its " +
                "lookup. Select an Android-only target set to use Hilt, or drop the injection " +
                "strategy to keep this project's platforms."
        }
        return refusal(state)
    }

    override fun refusal(state: StateStrategy): String? =
        if (isPlatformHolder(state)) {
            null
        } else {
            "Injection 'hilt' resolves a ViewModel, whose lifetime a ViewModelStore owns; this " +
                "state strategy remembers its holder inside the page's composition instead. Select " +
                "the ViewModel state strategy to use Hilt, or drop the injection strategy to keep " +
                "this one."
        }

    /**
     * `@HiltViewModel class HomeViewModel @Inject constructor() : ViewModel()`.
     *
     * Only the classes a platform owns: the decoration is what `hiltViewModel()` reads, and a class
     * the composition remembers is refused before emission, so nothing else reaches here.
     */
    override fun decorate(declarations: List<KtDeclaration>): List<KtDeclaration> =
        declarations.map { declaration ->
            if (declaration is KtDeclaration.Class && declaration.supertype == viewModelType) {
                declaration.copy(
                    annotations = declaration.annotations + hiltViewModelAnnotation.toAnnotation(),
                    constructorAnnotation = inject,
                )
            } else {
                declaration
            }
        }

    /**
     * A scaffolded project cannot host Hilt, and the reason is where the sources land.
     *
     * `@HiltViewModel` is resolved by Hilt's own compiler, which runs in an Android compilation.
     * This scaffold writes every generated file into `shared/src/commonMain` — a KMP common source
     * set — and Hilt has no Kotlin Multiplatform story, so there is no module in the emitted
     * project where that compiler could see the classes it must process. Applying the plugin
     * anyway would produce a project that resolves no graph and fails on its first build, which is
     * the one outcome this seam exists to prevent.
     *
     * The emission itself is sound and stays available: `forge generate --injection hilt
     * --platforms android` writes the annotated Kotlin, which is what a hand-written Android
     * module can compile.
     */
    override val scaffoldRefusal: String? =
        "injection 'hilt' cannot be scaffolded: Hilt's compiler only runs in an Android " +
            "compilation, and every generated file lands in shared/src/commonMain, where no " +
            "Android compiler sees it. Use 'koin' or 'metro' for a scaffolded project, or " +
            "'forge generate --injection hilt --platforms android' for the Kotlin alone."

    /** `androidx.hilt.navigation.compose.hiltViewModel`: the composable lookup. */
    /** `dagger.hilt.android.lifecycle.HiltViewModel`: what `hiltViewModel()` resolves. */
    private val hiltViewModelAnnotation: KotlinSymbol =
        KotlinSymbol("dagger.hilt.android.lifecycle", "HiltViewModel")

    /** `javax.inject.Inject`: the constructor `@HiltViewModel` requires. */
    private val inject: KotlinSymbol = KotlinSymbol("javax.inject", "Inject")

    /** `androidx.lifecycle.ViewModel`: the supertype that marks a class as a holder. */
    private val viewModelType: KotlinSymbol = KotlinSymbol("androidx.lifecycle", "ViewModel")

    private val hiltViewModel: KotlinSymbol =
        KotlinSymbol("androidx.hilt.navigation.compose", "hiltViewModel")
}
