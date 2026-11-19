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

    /**
     * The names the snapshot strategy emits, because this strategy emits the same files.
     *
     * `PersistentStore` is in the set although this strategy refuses to use one: the refusal is a
     * diagnostic about a declaration, not a promise that the file could not be emitted, and a
     * reserved name that depends on which declarations a document happens to hold would refuse
     * two documents differently for the same name.
     */
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
        state: KotlinSymbol,
        pkg: String,
    ): List<KtDeclaration> = listOf(
        KtDeclaration.Class(
            name = pageName + ViewModelSuffix,
            annotations = emptyList(),
            members = listOf(
                KtDeclaration.Property(
                    name = "state",
                    annotations = emptyList(),
                    type = KtExpr.Ref(KtSymbolRef(state)),
                    mutable = false,
                    initializer = KtExpr.Call(KtExpr.Ref(KtSymbolRef(state)), emptyList()),
                    delegate = null,
                    getter = null,
                ),
            ),
            supertype = viewModel,
        ),
    )

    // The parameter is the ViewModel, and the read of a page declaration goes through it to the
    // state: `viewModel.state.count`, one hop that names which layer answers.
    override fun pageParameter(pageName: String, pkg: String): KtParam = KtParam(
        name = "viewModel",
        type = KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, pageName + ViewModelSuffix))),
        // No type argument: the parameter's own type is the expected type, and `viewModel()`
        // reads the same either way while spelling less.
        default = KtExpr.Call(KtExpr.Ref(KtSymbolRef(viewModelOf)), emptyList()),
    )

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
