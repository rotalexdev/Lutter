package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedState
import dev.rotalex.lutter.model.diagnostic.Diagnostic
import dev.rotalex.lutter.model.diagnostic.DiagnosticCode
import dev.rotalex.lutter.model.doc.Persistence
import dev.rotalex.lutter.model.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.registry.Registry

/**
 * PLAN §12.1's four kinds as the generator writes them: a page's holder, the app's, a component's
 * locals, and the derived row that hangs off whichever list a declaration is in.
 *
 * Built over one [ResolvedDocument] and one [CodegenOptions], so §12.2's claim is structural
 * rather than promised: the strategy it reads off [CodegenOptions.state] answers every spelling
 * question below, and nothing outside this file reaches for a Compose name on state's account.
 *
 * ### A derived declaration is emitted inside the class it belongs to
 *
 * §12.1's `val doubled: Int get() = count * 2` is a member, and a member reads its sibling
 * bare — `state.count` is the screen's spelling and not this file's. A component's declarations
 * have no such class, which is why a derived one there is refused rather than re-spelled.
 */
public class StateEmitter(
    private val document: ResolvedDocument,
    private val options: CodegenOptions,
    private val functions: Registry<FunctionId, FunctionSpec>,
) {

    private val strategy: StateStrategy = options.state
    private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
    private val stable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Stable")
    private val remember: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "remember")
    private val staticCompositionLocalOf: KotlinSymbol =
        KotlinSymbol("androidx.compose.runtime", "staticCompositionLocalOf")
    // The declared type of `LocalAppState`, and the subtype rather than its `CompositionLocal`
    // supertype: `AppRoot` provides through `LocalAppState.provides(state)`, a member only
    // `ProvidableCompositionLocal` has.
    private val compositionLocal: KotlinSymbol =
        KotlinSymbol("androidx.compose.runtime", "ProvidableCompositionLocal")
    private val provider: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "CompositionLocalProvider")

    /** §12.1's app row: `class AppState` and the composition local `AppRoot` provides. */
    public fun appState(): List<KtDeclaration>? {
        if (document.appState.isEmpty()) return null
        val holder: KtDeclaration.Class =
            stateClass(AppStateName, StateScope.App, document.appState, AppScopeKey) ?: return null
        return listOf(holder, localProperty())
    }

    /**
     * `AppRoot`'s app-state statements around [content]: the remembered holder, then the
     * provider. Null when the document declares no app state, so `AppRoot` composes its screen
     * directly exactly as it did before either scope existed.
     */
    public fun appProvider(content: KtStmt, store: KtExpr? = null): List<KtStmt>? {
        if (document.appState.isEmpty() && store == null) return null
        val provided: MutableList<KtArg> = mutableListOf()
        val locals: MutableList<KtStmt> = mutableListOf()
        if (store != null) {
            provided += KtArg(
                null,
                KtExpr.Call(
                    KtExpr.Member(KtExpr.Ref(KtSymbolRef(localPersistentStoreSymbol())), "provides"),
                    listOf(KtArg(null, store)),
                ),
            )
        }
        if (document.appState.isNotEmpty()) {
            locals += appStateLocal(store)
            provided += KtArg(
                null,
                KtExpr.Call(
                    KtExpr.Member(KtExpr.Ref(KtSymbolRef(localAppStateSymbol)), "provides"),
                    listOf(KtArg(null, KtExpr.Name("state"))),
                ),
            )
        }
        val wrapped: KtStmt.Expr = KtStmt.Expr(
            KtExpr.Call(
                KtExpr.Ref(KtSymbolRef(provider)),
                provided,
                KtExpr.Lambda(emptyList(), listOf(content)),
            ),
        )
        return locals + wrapped
    }

    /** `remember { AppState(store = store) }`, the holder [appProvider] provides. */
    private fun appStateLocal(store: KtExpr?): KtStmt.LocalProperty =
        KtStmt.LocalProperty(
            name = "state",
            type = KtExpr.Ref(KtSymbolRef(appStateSymbol)),
            mutable = false,
            initializer = KtExpr.Call(
                KtExpr.Ref(KtSymbolRef(remember)),
                emptyList(),
                KtExpr.Lambda(
                    emptyList(),
                    listOf(
                        KtStmt.Expr(
                            KtExpr.Call(
                                KtExpr.Ref(KtSymbolRef(appStateSymbol)),
                                store?.let { listOf(KtArg("store", it)) } ?: emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
            delegate = null,
        )

    /**
     * §12.1's page row: `@Stable class HomeScreenState` and whatever the strategy puts around it
     * — `rememberHomeScreenState()` under the default, `class HomeViewModel : ViewModel()` under
     * the one that hands the row to the platform.
     *
     * Null when the page declares nothing: a state class with no members and a layer with
     * nothing in it are declarations that prove nothing and change every golden.
     */
    public fun pageState(page: ResolvedPage): List<KtDeclaration>? {
        if (page.state.isEmpty()) return null
        val name: String = classNameOf(page)
        val holder: KtDeclaration.Class =
            stateClass(name, StateScope.Page, page.state, page.id.value, stable) ?: return null
        // What the row adds is the strategy's: a factory a plain class needs, a ViewModel a
        // platform-owned one gets instead, or nothing at all when neither is called for.
        return listOf(holder) + strategy.pageExtras(page.name, holderSymbol(name), screensPackage())
    }

    /**
     * The parameter §12.1 gives [page]'s screen. Its name is the strategy's — `state` where the
     * holder is a state, `viewModel` where it is a ViewModel — and so is what it defaults to.
     */
    public fun pageParameter(page: ResolvedPage): KtParam =
        strategy.pageParameter(page.name, screensPackage())

    /**
     * §12.1's component row: `var x by remember { mutableStateOf(init) }`, as composable locals.
     *
     * Returned rather than written into a file because the composable is the caller's: these
     * statements go at the top of a component's body, and that body is not this class's to own.
     */
    public fun componentLocals(declarations: List<ResolvedState>): List<KtStmt>? {
        val locals: MutableList<KtStmt> = mutableListOf()
        for (state in declarations) {
            val type: KtExpr = spelledType(state) ?: return null
            val initial: KtExpr = initialOf(state) ?: return null
            locals += KtStmt.LocalProperty(
                name = state.decl.name,
                type = type,
                mutable = true,
                initializer = null,
                delegate = strategy.holder(StateScope.Component, initial),
            )
        }
        return locals
    }

    /**
     * The finding [declaration] raises at [at], or null when it can be emitted.
     *
     * The code is here rather than at the call site because the fact is: a type nothing can
     * spell is `codegen.no_type_spelling` and everything else this class declines is a strategy
     * the option does not have. §16.7's rule is why none of them is a throw — a `TypeRef` and a
     * `Persistence` both arrive from the user's document.
     */
    public fun refusal(declaration: ResolvedState, at: DiagnosticLocation): Diagnostic? {
        val spelling: TypeSpelling = KotlinTypes.spellingFor(declaration.decl.type)
        if (spelling is TypeSpelling.Unspelled) {
            return finding(
                DiagnosticCodes.CodegenNoTypeSpelling,
                at,
                "State '" + declaration.decl.name + "' is typed '" + declaration.decl.type.serialTag +
                    "', which has no Kotlin spelling: " + spelling.reason,
            )
        }
        if (!strategy.supports(declaration.decl.persistence)) {
            return finding(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "State '" + declaration.decl.name + "' asks to be " + declaration.decl.persistence +
                    ", which the selected state strategy does not emit",
            )
        }
        // §12.1's persistent row is a property whose setter writes, so the two shapes it cannot
        // take are refusals by name rather than a declaration that compiles and forgets.
        if (declaration.decl.persistence == Persistence.Saveable) {
            if (scopeOf(declaration.decl.id) == StateScope.Component) {
                return finding(
                    DiagnosticCodes.CodegenStrategyUnsupported,
                    at,
                    "State '" + declaration.decl.name +
                        "' asks to be Saveable, and §12.1's component row is remembered, not stored",
                )
            }
            if (codecOf(declaration.decl.type) == null) {
                return finding(
                    DiagnosticCodes.CodegenStrategyUnsupported,
                    at,
                    "State '" + declaration.decl.name + "' asks to be Saveable, and '" +
                        declaration.decl.type.serialTag + "' has no store codec",
                )
            }
        }
        if (declaration.derived != null && scopeOf(declaration.decl.id) == StateScope.Component) {
            return finding(
                DiagnosticCodes.CodegenStrategyUnsupported,
                at,
                "State '" + declaration.decl.name +
                    "' is derived, and §12.1's derived shape is a class member",
            )
        }
        val initial: Value = declaration.decl.initial ?: return null
        if (LiteralPrinter.emit(initial) != null) return null
        return finding(
            DiagnosticCodes.CodegenStrategyUnsupported,
            at,
            "State '" + declaration.decl.name + "' starts as a value with no Kotlin literal",
        )
    }

    private fun finding(code: DiagnosticCode, at: DiagnosticLocation, message: String): Diagnostic =
        Diagnostic(Severity.Error, code, at, message)

    /**
     * The read of [id] as a screen composable spells it: `state.count` for the page's own
     * declaration, `LocalAppState.current.count` for the app's, and the bare name for a
     * component's.
     *
     * Null when the document declares no such state. Pass 5 refuses an unresolved
     * `RefTarget.State` as `expr.unresolved_ref`, so a caller reaching null has a binding table
     * that lost a declaration rather than a document at fault.
     */
    public fun readInScreen(id: StateId): KtExpr? {
        val bound: ResolvedState = bindings[id] ?: return null
        val scope: StateScope = scopeOf(id) ?: return null
        val receiver: KtExpr? = strategy.receiver(scope, localAppStateSymbol)
        return receiver?.let { KtExpr.Member(it, bound.decl.name) } ?: KtExpr.Name(bound.decl.name)
    }

    /** Every §12.1 declaration in the document, keyed by the id a `RefTarget.State` carries. */
    private val bindings: Map<StateId, ResolvedState> = buildMap {
        for (state in document.appState) put(state.decl.id, state)
        for (page in document.pages.values) {
            for (state in page.state) put(state.decl.id, state)
        }
        for (declarations in document.componentState.values) {
            for (state in declarations) put(state.decl.id, state)
        }
    }

    /** Which list each declaration was written in, which is what picks its receiver. */
    private val scopes: Map<StateId, StateScope> = buildMap {
        for (state in document.appState) put(state.decl.id, StateScope.App)
        for (page in document.pages.values) {
            for (state in page.state) put(state.decl.id, StateScope.Page)
        }
        for (declarations in document.componentState.values) {
            for (state in declarations) put(state.decl.id, StateScope.Component)
        }
    }

    private val appStateSymbol: KotlinSymbol = KotlinSymbol(options.basePackage + ".state", AppStateName)

    private val localAppStateSymbol: KotlinSymbol =
        KotlinSymbol(options.basePackage + ".state", LocalAppStateName)

    private val storeSymbol: KotlinSymbol = KotlinSymbol(options.basePackage + ".state", PersistentStoreName)

    private fun localPersistentStoreSymbol(): KotlinSymbol =
        KotlinSymbol(options.basePackage + ".state", LocalPersistentStoreName)

    private fun scopeOf(id: StateId): StateScope? = scopes[id]

    private fun classNameOf(page: ResolvedPage): String = page.name + ReservedCodegenNames.PageStateSuffix

    /** The package a page's file lands in, where the symbols a strategy emits for it live. */
    private fun screensPackage(): String = options.basePackage + ".screens"

    private fun holderSymbol(name: String): KotlinSymbol = KotlinSymbol(screensPackage(), name)

    /**
     * One state class, its members in declaration order.
     *
     * [scope] is the class's own list rather than each member's, because a class body reads its
     * siblings bare whichever list they came from — and a derived page state naming an app
     * declaration is a document pass 5 has already refused, §12.1's separate stores being the
     * reason it reads as unresolved.
     */
    private fun stateClass(
        name: String,
        scope: StateScope,
        declarations: List<ResolvedState>,
        keyPrefix: String,
        annotated: KotlinSymbol? = null,
    ): KtDeclaration.Class? {
        val siblings: StateRead = { id ->
            declarations.firstOrNull { it.decl.id == id }?.decl?.name?.let { held -> KtExpr.Name(held) }
        }
        val reader: ExprEmitter = ExprEmitter(functions, siblings)
        val saves: Boolean = declarations.any { it.decl.persistence == Persistence.Saveable }
        val members: MutableList<KtDeclaration> = mutableListOf()
        for (state in declarations) {
            members += member(state, scope, reader, keyPrefix, saves) ?: return null
        }
        val annotations: List<KotlinSymbol> = if (annotated == null) emptyList() else listOf(annotated)
        // The store arrives as a constructor property rather than as a parameter on every member,
        // and defaults to the composition local so a screen's `remember…State()` keeps its shape.
        val constructor: List<KtDeclaration.Property> = if (saves) listOf(storeProperty()) else emptyList()
        return KtDeclaration.Class(
            name,
            annotations,
            members,
            constructor,
            null,
        )
    }

    /**
     * `private val store: PersistentStore? = LocalPersistentStore.current`.
     *
     * Private because the declaration it holds is the class's own business; a default because
     * every construction site the emitter already had is inside the composition that provides
     * the local, so the shape of `rememberHomeScreenState()` does not change.
     */
    private fun storeProperty(): KtDeclaration.Property = KtDeclaration.Property(
        name = "store",
        annotations = emptyList(),
        type = KtExpr.Nullable(KtExpr.Ref(KtSymbolRef(storeSymbol))),
        mutable = false,
        initializer = KtExpr.Member(KtExpr.Ref(KtSymbolRef(localPersistentStoreSymbol())), "current"),
        delegate = null,
        getter = null,
        visibility = "private",
    )

    /**
     * A held declaration delegates; a derived one is computed on read, as §12.1 puts it; a
     * `Saveable` one is two members, because the write side is what the store is for.
     */
    private fun member(
        state: ResolvedState,
        scope: StateScope,
        reader: ExprEmitter,
        keyPrefix: String,
        saves: Boolean,
    ): List<KtDeclaration>? {
        val type: KtExpr = spelledType(state) ?: return null
        val derived = state.derived
        if (derived != null) {
            return listOf(
                KtDeclaration.Property(
                    name = state.decl.name,
                    annotations = emptyList(),
                    type = type,
                    mutable = false,
                    initializer = null,
                    delegate = null,
                    getter = reader.emit(derived),
                ),
            )
        }
        val initial: KtExpr = initialOf(state) ?: return null
        if (saves && state.decl.persistence == Persistence.Saveable) {
            val codec: StoreCodec = codecOf(state.decl.type) ?: return null
            return strategy.saveable(
                scope = scope,
                name = state.decl.name,
                key = keyPrefix + "/" + state.decl.id.value,
                initial = initial,
                codec = codec,
                store = KtExpr.Name("store"),
            )
        }
        return listOf(
            KtDeclaration.Property(
                name = state.decl.name,
                annotations = emptyList(),
                type = type,
                mutable = true,
                initializer = null,
                delegate = strategy.holder(scope, initial),
                getter = null,
            ),
        )
    }

    /**
     * How one scalar type moves through the store, or null when it cannot.
     *
     * Text on both sides and nothing else: the store outlives a Kotlin version without knowing
     * the document's types, and a type this table has no row for is refused by the caller rather
     * than silently held in memory — a saved declaration that does not save is worse than one
     * the document was told could not be emitted.
     */
    private fun codecOf(spelled: TypeRef): StoreCodec? = when (spelled) {
        TypeRef.Bool -> numericOrBoolCodec("Boolean", "toBooleanStrictOrNull")
        TypeRef.Int32 -> numericOrBoolCodec("Int", "toIntOrNull")
        TypeRef.Int64 -> numericOrBoolCodec("Long", "toLongOrNull")
        TypeRef.Float32 -> numericOrBoolCodec("Float", "toFloatOrNull")
        TypeRef.Float64 -> numericOrBoolCodec("Double", "toDoubleOrNull")
        // A string is already the store's own spelling, so its codec is the identity on both
        // sides: `toString()` would be the same call and the read would be a needless copy.
        TypeRef.Str -> StoreCodec(KtExpr.Name("String"), { it }, { it })
        else -> null
    }

    private fun numericOrBoolCodec(kotlinName: String, decodeName: String): StoreCodec = StoreCodec(
        KtExpr.Name(kotlinName),
        encode = { value -> KtExpr.Call(KtExpr.Member(value, "toString"), emptyList()) },
        // A bare `?.toIntOrNull` is a property read, not the function the type declares, so the
        // member access is the callee of an empty call and not the whole expression.
        decode = { text ->
            KtExpr.Call(KtExpr.Member(text, decodeName, safe = true), emptyList())
        },
    )

    /** The initial value as a literal, or null when the value kind has no Kotlin spelling. */
    private fun initialOf(state: ResolvedState): KtExpr? {
        val initial: Value = state.decl.initial ?: return null
        val emitted: EmittedLiteral = LiteralPrinter.emit(initial) ?: return null
        return KtExpr.Literal(emitted.text, emitted.symbols)
    }

    /**
     * The declared type as Kotlin, or null when the document named a type nothing can spell.
     *
     * Null rather than a guess: the generator reports it as `codegen.no_type_spelling`, which is
     * what §16.7's "never throws for domain errors" buys — a `TypeRef` arrives from the user's
     * document, and a guessed type compiles into a file that is wrong where it is read.
     */
    private fun spelledType(state: ResolvedState): KtExpr? {
        val declared: TypeRef = state.decl.type
        return when (val spelled = KotlinTypes.spellingFor(declared)) {
            is TypeSpelling.Spelled -> spelled.expr
            is TypeSpelling.Unspelled -> null
        }
    }

    /**
     * `val LocalAppState: ProvidableCompositionLocal<AppState> = staticCompositionLocalOf { AppState() }`.
     *
     * A lambda, not a no-argument call: `staticCompositionLocalOf` takes a `defaultFactory` and
     * has no empty overload, so `()` does not compile. The factory exists only to satisfy the
     * signature — Compose throws if it is ever read, which is the half of §12.1 the runtime's
     * `ScreenEvalScope` cannot paper over either.
     */
    private fun localProperty(): KtDeclaration.Property = KtDeclaration.Property(
        name = LocalAppStateName,
        annotations = emptyList(),
        type = KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(compositionLocal)), listOf(KtExpr.Name(AppStateName))),
        mutable = false,
        initializer = KtExpr.Call(
            KtExpr.TypeApplication(
                KtExpr.Ref(KtSymbolRef(staticCompositionLocalOf)),
                listOf(KtExpr.Name(AppStateName)),
            ),
            emptyList(),
            KtExpr.Lambda(
                emptyList(),
                listOf(KtStmt.Expr(KtExpr.Call(KtExpr.Ref(KtSymbolRef(appStateSymbol)), emptyList()))),
            ),
        ),
        delegate = null,
        getter = null,
    )

    /** Whether this document asks for a store: [Companion.savesAnything] over the one it holds. */
    public fun savesAnything(): Boolean = Companion.savesAnything(document)

    /**
     * `state/PersistentStore.kt`: the hook and the local `AppRoot` provides it through.
     *
     * Both declarations and no implementation: what a store is made of (a file, a preferences
     * file, a database) is the host application's, and the generated code only has to agree on
     * the two calls and on the key shape, which is the same `<scope>/<stateId>` the runtime uses.
     */
    public fun persistentStore(): List<KtDeclaration> = listOf(storeInterface(), localPersistentStore())

    private fun storeInterface(): KtDeclaration.Interface = KtDeclaration.Interface(
        name = PersistentStoreName,
        annotations = emptyList(),
        sealed = false,
        members = listOf(
            KtDeclaration.Function(
                name = "read",
                annotations = emptyList(),
                type = KtExpr.Nullable(KtExpr.Name("String")),
                params = listOf(KtParam("key", KtExpr.Name("String"), null)),
                body = emptyList(),
            ),
            KtDeclaration.Function(
                name = "write",
                annotations = emptyList(),
                type = null,
                params = listOf(
                    KtParam("key", KtExpr.Name("String"), null),
                    KtParam("value", KtExpr.Name("String"), null),
                ),
                body = emptyList(),
            ),
        ),
    )

    /** `val LocalPersistentStore = staticCompositionLocalOf<PersistentStore?> { null }`. */
    private fun localPersistentStore(): KtDeclaration.Property {
        val storeType: KtExpr = KtExpr.Nullable(KtExpr.Ref(KtSymbolRef(storeSymbol)))
        return KtDeclaration.Property(
            name = LocalPersistentStoreName,
            annotations = emptyList(),
            type = KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(compositionLocal)), listOf(storeType)),
            mutable = false,
            initializer = KtExpr.Call(
                KtExpr.TypeApplication(KtExpr.Ref(KtSymbolRef(staticCompositionLocalOf)), listOf(storeType)),
                emptyList(),
                KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(KtExpr.Literal("null")))),
            ),
            delegate = null,
            getter = null,
        )
    }

    public companion object {
        /** §16.5's `state/AppState.kt` names, which the plan, the planner and the emitter share. */
        public const val AppStateName: String = "AppState"
        public const val LocalAppStateName: String = "LocalAppState"

        /** The generated store's names, and the backing-state prefix a `Saveable` property keeps. */
        public const val PersistentStoreName: String = "PersistentStore"
        public const val LocalPersistentStoreName: String = "LocalPersistentStore"
        public const val StoredPrefix: String = "_"

        /**
         * The scope a saveable app declaration is stored under.
         *
         * Pages key by their page id, which is unique per document; app state has no page, and a
         * name it shares with one would otherwise collide in a store that serves both.
         */
        public const val AppScopeKey: String = "app"

        /**
         * Whether [document] declares anything asking to be saved, which is what a store is for.
         *
         * The planner and the emitter both read it: a document without one emits no
         * `state/PersistentStore.kt`, no `AppRoot` parameter and no provider, so every existing
         * golden stays byte-identical.
         */
        public fun savesAnything(document: ResolvedDocument): Boolean =
            (document.appState + document.pages.values.flatMap { it.state })
                .any { it.derived == null && it.decl.persistence == Persistence.Saveable }
    }
}
