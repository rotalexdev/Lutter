package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionPrecedence

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
 * One scalar type as text on both sides of a `PersistentStore`.
 *
 * The store holds text because an engine-owned store must outlive a Kotlin version without
 * knowing the document's types, so each row says which Kotlin type it is and how the generated
 * property moves it in and out. [decode] answers null for text that is not a value of the type,
 * and the caller keeps its `initial` in that case: a store holding garbage must not make a
 * declaration unreadable.
 */
public data class StoreCodec(
    /** The scalar's Kotlin type, which the backing state is a `MutableState` of. */
    public val type: KtExpr,
    /** `value -> text`, the write side. */
    public val encode: (KtExpr) -> KtExpr,
    /** `text -> value?`, the read side. */
    public val decode: (KtExpr) -> KtExpr,
)

/**
 * PLAN §12.2: the only place that knows *how* state is emitted.
 *
 * Three questions and no others — what a read reaches through, what a held property delegates
 * to, and whether a persistence has an emission yet. A later strategy (ViewModel, Molecule,
 * Circuit) answers them differently and touches nothing else, which is the whole claim.
 */
public interface StateStrategy {

    /**
     * The top-level names this strategy's emission puts in the generated app, which a document
     * may therefore not declare.
     *
     * The same contract as `NavigationStrategy.reservedTopLevelNames`, for the same reason: the
     * answer has to exist before anything is emitted, so it is stated by the strategy that emits.
     */
    public val reservedTopLevelNames: Set<String>

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

    /**
     * The two members a `Saveable` declaration of [scope] is emitted as: the private backing
     * state, seeded from [store] under [key], and the property that reads it and writes both.
     *
     * Two members rather than a delegate because the write side is the whole row: a delegate
     * hands the value to whoever asked and the store never hears about it, while a property with
     * a setter is the one shape where "held and saved" is a single fact. [codec] carries the
     * text conversion, so the strategy names no document type.
     */
    public fun saveable(
        scope: StateScope,
        name: String,
        key: String,
        initial: KtExpr,
        codec: StoreCodec,
        store: KtExpr,
    ): List<KtDeclaration.Property>
}

/** The one strategy that exists: snapshot state, which is §12.2's default and §15.3's runtime. */
public data object ComposeSnapshotState : StateStrategy {

    /**
     * §16.5's `state/AppState.kt`. Read off [StateEmitter]'s constants rather than spelled again,
     * so a rename of the holder cannot leave this set claiming a name nothing emits.
     */
    override val reservedTopLevelNames: Set<String> = setOf(
        StateEmitter.AppStateName,
        StateEmitter.LocalAppStateName,
        StateEmitter.PersistentStoreName,
        StateEmitter.LocalPersistentStoreName,
    )

    private val mutableStateOf: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "mutableStateOf")
    private val mutableState: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "MutableState")
    private val remember: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "remember")

    // `by` resolves through these, extensions on `MutableState` outside Kotlin's default
    // imports, so a file that delegates without naming them delegates to nothing. Both, because
    // every delegate this strategy emits is a `var` and only the property knows which it is.
    private val operators: List<KotlinSymbol> = listOf(
        KotlinSymbol("androidx.compose.runtime", "getValue"),
        KotlinSymbol("androidx.compose.runtime", "setValue"),
    )

    override fun receiver(scope: StateScope, appState: KotlinSymbol): KtExpr? = when (scope) {
        // A component's declaration is a local of the composable that declares it.
        StateScope.Component -> null
        StateScope.Page -> KtExpr.Name("state")
        StateScope.App -> KtExpr.Member(KtExpr.Ref(KtSymbolRef(appState)), "current")
    }

    override fun holder(scope: StateScope, initial: KtExpr): KtExpr {
        val state: KtExpr = KtExpr.Call(KtExpr.Ref(KtSymbolRef(mutableStateOf)), listOf(KtArg(null, initial)))
        if (scope != StateScope.Component) return KtExpr.Delegate(state, operators)
        return KtExpr.Delegate(
            KtExpr.Call(
                KtExpr.Ref(KtSymbolRef(remember)),
                emptyList(),
                KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(state))),
            ),
            operators,
        )
    }

    /**
     * Both rows, because §12.1's persistent row is emitted here rather than refused.
     *
     * [persistence] alone cannot say whether a declaration is emittable — a `Saveable` one also
     * needs a store codec for its type and a scope that has a store — so those refusals are the
     * emitter's, which is the only caller holding the declaration.
     */
    override fun supports(persistence: Persistence): Boolean = true

    override fun saveable(
        scope: StateScope,
        name: String,
        key: String,
        initial: KtExpr,
        codec: StoreCodec,
        store: KtExpr,
    ): List<KtDeclaration.Property> {
        val backing: String = StateEmitter.StoredPrefix + name
        val text: KtExpr = KtExpr.Call(
            KtExpr.Member(store, "read", safe = true),
            listOf(KtArg(null, KtExpr.Literal("\"" + key + "\""))),
        )
        // The store wins only where it has a value of the declared type; `initial` is what the
        // document says the value is before anything happened, so it is the fallback and not
        // the seed a stale entry could overwrite.
        val seeded: KtExpr = KtExpr.PatternCall(
            "{0} ?: {1}",
            listOf(codec.decode(text), initial),
            precedence = FunctionPrecedence.Elvis,
        )
        val held: KtDeclaration.Property = KtDeclaration.Property(
            name = backing,
            annotations = emptyList(),
            type = KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(mutableState)), listOf(codec.type)),
            mutable = false,
            initializer = KtExpr.Call(KtExpr.Ref(KtSymbolRef(mutableStateOf)), listOf(KtArg(null, seeded))),
            delegate = null,
            getter = null,
            visibility = "private",
        )
        val written: KtStmt = KtStmt.Expr(
            KtExpr.Call(
                KtExpr.Member(store, "write", safe = true),
                listOf(
                    KtArg(null, KtExpr.Literal("\"" + key + "\"")),
                    KtArg(null, codec.encode(KtExpr.Name("value"))),
                ),
            ),
        )
        val property: KtDeclaration.Property = KtDeclaration.Property(
            name = name,
            annotations = emptyList(),
            type = codec.type,
            mutable = true,
            initializer = null,
            delegate = null,
            getter = KtExpr.Member(KtExpr.Name(backing), "value"),
            setter = listOf(
                KtStmt.Assign(KtExpr.Member(KtExpr.Name(backing), "value"), KtExpr.Name("value")),
                written,
            ),
        )
        return listOf(held, property)
    }
}
