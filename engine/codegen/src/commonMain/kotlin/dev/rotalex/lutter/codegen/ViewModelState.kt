package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * PLAN §32 Phase 15, S3: a page's state holder is a `ViewModel` the platform owns.
 *
 * The page row is the only one whose lifetime a platform type can improve on. §12.1 gives a page
 * state to the page's composition, and Compose throws that composition away on a configuration
 * change; a `ViewModelStore` does not, so the holder and everything a declaration in it was
 * holding survive a rotation without a `rememberSaveable` for every type the document can spell.
 *
 * ### What it does not change
 *
 * The app row's lifetime is already the process, which nothing outlives, so it keeps the
 * remembered holder the composition local provides. A component's local is scoped to one
 * composable and there is no `ViewModelStoreOwner` at that depth worth asking for, so it keeps
 * `remember { mutableStateOf(…) }`. Both answers come from the snapshot strategy: a
 * strategy that wrapped every scope for symmetry would be a strategy that changed two rows
 * nobody asked it to change.
 *
 * ### What it refuses
 *
 * A `ViewModel` is constructed by the platform, outside the composition that provides
 * `LocalPersistentStore`, so a `Saveable` declaration has no way to reach the store and is
 * refused by name instead of emitted as a property that compiles and forgets. Persistence stays
 * §12.1's page row under the snapshot strategy; the two seams compose by picking one holder, and
 * a document that wants both is a later decision, not a silent downgrade.
 *
 * ### What it costs
 *
 * The generated project needs `lifecycle-viewmodel-compose`, which the snapshot strategy does
 * not. That is why this is a strategy and not the default: §32's S6 keeps `SimpleBackStack` and
 * the zero-dependency path as the default, and the selection point that opts in is the CLI's.
 */
public data object ViewModelState : StateStrategy {

    private val viewModel: KotlinSymbol = KotlinSymbol("androidx.lifecycle", "ViewModel")

    // The `viewModel()` accessor, not `viewModelScope` or a factory: reified on the holder's type
    // so the screen's default names the class the same file declares, and no key is needed
    // because one screen asks for one holder.
    private val viewModelOf: KotlinSymbol =
        KotlinSymbol("androidx.lifecycle.viewmodel.compose", "viewModel")

    // An external declaration's four names: the stream, the value it holds, the scope that
    // outlives a composition change, and the collector. `mutableStateOf`/`MutableState` come
    // from the snapshot strategy because the property they back is the same kind of state.
    private val flow: KotlinSymbol = KotlinSymbol("kotlinx.coroutines.flow", "Flow")
    private val mutableStateOf: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "mutableStateOf")
    private val mutableState: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "MutableState")
    private val viewModelScope: KotlinSymbol = KotlinSymbol("androidx.lifecycle", "viewModelScope")
    private val launch: KotlinSymbol = KotlinSymbol("kotlinx.coroutines", "launch")

    /**
     * The names the snapshot strategy emits, because this strategy emits the same files.
     *
     * `PersistentStore` is in the set although this strategy refuses to use one: the refusal is a
     * diagnostic about a declaration, not a promise that the file could not be emitted, and a
     * reserved name that depends on which declarations a document happens to hold would refuse
     * two documents differently for the same name.
     */
    /**
     * `lifecycle-viewmodel-compose`: `viewModel()` reads it, and the `ViewModel` a page's holder
     * extends is the same artifact's.
     */
    override val libraries: Set<GeneratedLibrary> = setOf(
        GeneratedLibrary.LifecycleViewModelCompose,
        GeneratedLibrary.Coroutines,
    )

    override val reservedTopLevelNames: Set<String> = ComposeSnapshotState.reservedTopLevelNames

    /**
     * `class HomeViewModel : ViewModel() { public val state: HomeScreenState = HomeScreenState() }`.
     *
     * A ViewModel that *holds* the state rather than one that *is* the state. The state class is
     * §12.1's row unchanged — its members, its name, its reads — and this layer adds the only
     * thing a strategy is asked for: something the platform keeps across a configuration change
     * to own it. Naming both facts the same word is how a reader loses track of which one the
     * platform is holding.
     */
    override fun pageExtras(
        pageName: String,
        state: KotlinSymbol?,
        pkg: String,
        external: List<ExternalAdapter>,
    ): List<KtDeclaration> = listOf(
        KtDeclaration.Class(
            name = pageName + ViewModelSuffix,
            annotations = emptyList(),
            constructorProperties = external.map { adapter ->
                KtDeclaration.Property(
                    name = adapter.adapter,
                    annotations = emptyList(),
                    type = KtExpr.TypeApplication(
                        KtExpr.Ref(KtSymbolRef(flow)),
                        listOfNotNull(adapter.type),
                    ),
                    mutable = false,
                    initializer = null,
                    delegate = null,
                    getter = null,
                    visibility = "private",
                )
            },
            // The state class is the page's held declarations; a page whose every declaration is
            // external has none, and the holder is then only what folds the streams.
            members = listOfNotNull(
                state?.let { held ->
                    KtDeclaration.Property(
                        name = "state",
                        annotations = emptyList(),
                        type = KtExpr.Ref(KtSymbolRef(held)),
                        mutable = false,
                        initializer = KtExpr.Call(KtExpr.Ref(KtSymbolRef(held)), emptyList()),
                        delegate = null,
                        getter = null,
                    )
                },
            ) + external.flatMap { adapter -> externalProperty(adapter) },
            supertype = viewModel,
        ),
    )

    /**
     * One external declaration, as the pair of members a `ViewModel` can hold.
     *
     * `stateIn` would give a `StateFlow`, and a read of the declaration also happens inside an
     * event lambda — where `collectAsState()` is not callable, because it is `@Composable`. So the
     * stream is folded into a `MutableState`, readable from both places, and the public property is
     * a getter over it so nothing outside can write a value the host owns.
     */
    private fun externalProperty(adapter: ExternalAdapter): List<KtDeclaration> {
        val element: KtExpr = adapter.type ?: return emptyList()
        val seed: KtExpr = adapter.initial ?: return emptyList()
        val backing: String = adapter.state + "State"
        // `backing` is the `.also` parameter, not the property: the initializer must not read the
        // property it is initializing, and the parameter is the same `MutableState` it just built.
        val collected: KtExpr = KtExpr.Member(KtExpr.Name("backing"), "value")
        val assignment: KtStmt = KtStmt.Assign(collected, KtExpr.Name("value"))
        val collector: KtExpr = KtExpr.Call(
            KtExpr.Member(KtExpr.Name(adapter.adapter), "collect"),
            emptyList(),
            KtExpr.Lambda(listOf("value"), listOf(assignment)),
        )
        // `launch` is a top-level extension on `CoroutineScope`, so it is imported and not a
        // member of `viewModelScope`: a `Member(…, "launch")` reads the same and resolves nothing,
        // which is a lambda the compiler does not treat as a coroutine and a `collect` it refuses.
        val started: KtExpr = KtExpr.Call(
            KtExpr.Snippet("viewModelScope.launch", listOf(viewModelScope, launch)),
            emptyList(),
            KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(collector))),
        )
        val seeded: KtExpr = KtExpr.Call(
            KtExpr.Member(
                KtExpr.Call(KtExpr.Ref(KtSymbolRef(mutableStateOf)), listOf(KtArg(null, seed))),
                "also",
            ),
            emptyList(),
            KtExpr.Lambda(listOf("backing"), listOf(KtStmt.Expr(started))),
        )
        return listOf(
            KtDeclaration.Property(
                name = backing,
                annotations = emptyList(),
                type = KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(mutableState)), listOf(element)),
                mutable = false,
                initializer = seeded,
                delegate = null,
                getter = null,
                visibility = "private",
            ),
            KtDeclaration.Property(
                name = adapter.state,
                annotations = emptyList(),
                type = element,
                mutable = false,
                initializer = null,
                delegate = null,
                getter = KtExpr.Member(KtExpr.Name(backing), "value"),
            ),
        )
    }

    // The parameter is the ViewModel, and the read of a page declaration goes through it to the
    // state: `viewModel.state.count`, one hop that names which layer answers.
    override fun pageParameter(
        pageName: String,
        pkg: String,
        external: List<ExternalAdapter>,
    ): KtParam {
        val holder: KtExpr = KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, pageName + ViewModelSuffix)))
        if (external.isEmpty()) {
            // No type argument: the parameter's own type is the expected type, and `viewModel()`
            // reads the same either way while spelling less.
            return KtParam("viewModel", holder, KtExpr.Call(KtExpr.Ref(KtSymbolRef(viewModelOf)), emptyList()))
        }
        // An external declaration cannot arrive from the platform's no-argument constructor, so
        // the default becomes the initializer form: the screen still takes one `ViewModel` and the
        // wiring stays in the screen's signature rather than moving into the navigation strategy.
        val arguments: List<KtArg> = external.map { adapter ->
            KtArg(null, KtExpr.Call(KtExpr.Member(KtExpr.Name("host"), adapter.adapter), emptyList()))
        }
        val constructed: KtExpr = KtExpr.Call(holder, arguments)
        return KtParam(
            "viewModel",
            holder,
            KtExpr.Call(
                KtExpr.Ref(KtSymbolRef(viewModelOf)),
                listOf(KtArg("initializer", KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(constructed))))),
            ),
        )
    }

    /**
     * The one strategy whose row is a class: the holder has a constructor property to receive the
     * adapter and a coroutine scope to collect it in, so the declaration has an emission here.
     */
    override val supportsExternal: Boolean
        get() = true

    override val pageParameterName: String = ParameterName

    override fun pageReservedNames(pageName: String): Set<String> = setOf(
        pageName + ReservedCodegenNames.PageStateSuffix,
        pageName + ViewModelSuffix,
    )

    override fun receiver(scope: StateScope, appState: KotlinSymbol): KtExpr? =
        if (scope == StateScope.Page) {
            KtExpr.Member(KtExpr.Name(ParameterName), "state")
        } else {
            ComposeSnapshotState.receiver(scope, appState)
        }

    override fun holder(scope: StateScope, initial: KtExpr): KtExpr =
        ComposeSnapshotState.holder(scope, initial)

    override fun supports(persistence: Persistence): Boolean = persistence == Persistence.None

    override fun saveable(
        scope: StateScope,
        name: String,
        key: String,
        initial: KtExpr,
        codec: StoreCodec,
        store: KtExpr,
    ): List<KtDeclaration.Property> = throw CodegenBug(
        "ViewModelState emits no store-backed property; `supports` refuses the declaration first",
    )

    /** `class HomeViewModel`: the layer's own name, so the file never calls it a state. */
    internal const val ViewModelSuffix: String = "ViewModel"

    /** The screen's parameter, the same word [receiver] hops through. */
    internal const val ParameterName: String = "viewModel"
}
