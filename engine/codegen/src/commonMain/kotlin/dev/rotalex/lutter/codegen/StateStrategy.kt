package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Which §12.1 list a declaration was written in, and so what a read of it reaches through.
 *
 * §6.2 makes an id stable across a rename and the name the thing that changes, so this is
 * what an emitter resolves an id to before it writes anything: the id says *which* state, and
 * only the name says what the generated code calls it.
 */
public enum class StateScope {

    /** §12.1's component-local list: a `var` the composable declares, so a read is bare. */
    Component,

    /** §12.1's page list: the screen's own `state` parameter. */
    Page,

    /** §12.1's app list: the composition local `AppRoot` provides. */
    App,
}

/**
 * PLAN §12.2: the only place that knows *how* state is emitted.
 *
 * Three questions and no others — what a read reaches through, what a held property delegates
 * to, and whether a persistence has an emission yet. A later strategy (ViewModel, Molecule,
 * Circuit) answers them differently and touches nothing else, which is the whole claim.
 */
public interface StateStrategy {

    /**
     * The receiver a read of [scope]'s declaration carries, or null when the name is read bare.
     *
     * [appState] is the generated `LocalAppState` symbol, because the app scope's receiver
     * names a generated declaration and this is the one place allowed to know that.
     */
    public fun receiver(scope: StateScope, appState: KotlinSymbol): KtExpr?

    /**
     * The delegate a held property of [scope] carries, wrapping its [initial] value.
     *
     * The page and app classes are constructed once and remembered, so their properties
     * delegate straight to a holder; a component composable's local is re-entered on every
     * composition and so remembers the holder itself.
     */
    public fun holder(scope: StateScope, initial: KtExpr): KtExpr

    /**
     * Whether [persistence] has an emission under this strategy.
     *
     * False is a refusal, not a silent downgrade: emitting `mutableStateOf` for a declaration
     * that asked to be saved is a file that compiles and drops data on restart.
     */
    public fun supports(persistence: Persistence): Boolean
}

/** The one strategy that exists: snapshot state, which is §12.2's default and §15.3's runtime. */
public data object ComposeSnapshotState : StateStrategy {

    private val mutableStateOf: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "mutableStateOf")
    private val remember: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "remember")

    override fun receiver(scope: StateScope, appState: KotlinSymbol): KtExpr? = when (scope) {
        // A component's declaration is a local of the composable that declares it.
        StateScope.Component -> null
        StateScope.Page -> KtExpr.Name("state")
        StateScope.App -> KtExpr.Member(KtExpr.Ref(KtSymbolRef(appState)), "current")
    }

    override fun holder(scope: StateScope, initial: KtExpr): KtExpr {
        val state: KtExpr = KtExpr.Call(KtExpr.Ref(KtSymbolRef(mutableStateOf)), listOf(KtArg(null, initial)))
        if (scope != StateScope.Component) return state
        return KtExpr.Call(
            KtExpr.Ref(KtSymbolRef(remember)),
            emptyList(),
            KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(state))),
        )
    }

    /**
     * `Persistence.None` only, and the reason is `PageStateStore`.
     *
     * §12.1 asks for `rememberSaveable` where the type allows, and no engine code reads a
     * non-`None` value on either side: `PageStateHost` seeds `mutableStateOf` for every held
     * declaration. Emitting the saveable form on this side alone would make the generated
     * screen restore what the runtime drops, which is the one comparison §28.4 cannot make.
     */
    override fun supports(persistence: Persistence): Boolean = persistence == Persistence.None
}