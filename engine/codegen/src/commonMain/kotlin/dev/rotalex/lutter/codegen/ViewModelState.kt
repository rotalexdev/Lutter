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
 * `remember { mutableStateOf(…) }`. Both answers are `holderSupertype` returning null: a
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

    override fun holderSupertype(scope: StateScope): KotlinSymbol? =
        if (scope == StateScope.Page) viewModel else null

    override fun pageFactory(name: String): List<KtDeclaration> = emptyList()

    override fun pageDefault(holder: KotlinSymbol): KtExpr = KtExpr.Call(
        KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(viewModelOf)), listOf(KtExpr.Ref(KtSymbolRef(holder)))),
        emptyList(),
    )

    override fun receiver(scope: StateScope, appState: KotlinSymbol): KtExpr? =
        ComposeSnapshotState.receiver(scope, appState)

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
}
