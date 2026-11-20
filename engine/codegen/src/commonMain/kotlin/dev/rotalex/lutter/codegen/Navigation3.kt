package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Navigation 3: `NavDisplay` over a back stack the app owns.
 *
 * The third strategy, and the one whose premise is closest to [SimpleBackStack]'s: Navigation 3
 * does not keep a stack, it *renders* one. So the generated `AppNavigator` stays — same name, same
 * `navigate`, same `back` — and what changes is the type a screen receives (nothing), the route
 * hierarchy (`NavKey`s) and the root (`NavDisplay` instead of a `when`).
 *
 * ### Why the stack is still ours
 *
 * The library's own `rememberNavBackStack` would restore the stack across process death, and it
 * costs a serialization plugin in the generated project plus a `SavedStateConfiguration` per
 * document. This strategy keeps the navigator the document's platform entry already constructs
 * ([SimpleBackStack]'s shape) and hands its list to `NavDisplay`, which is the "user-owned back
 * stack" the library is built around. Restoration is a later decision, not a silent downgrade:
 * nothing is restored today under any strategy.
 *
 * ### The dispatch has no `else`
 *
 * Routes are a sealed hierarchy over the document's pages, so the `entryProvider`'s `when` is
 * exhaustive: a page the document declares is an arm, and a page an arm cannot reach is a compile
 * error rather than a screen that renders nothing at runtime.
 *
 * [basePackage] is what the strategy cannot be asked for, for [SimpleBackStack]'s reason:
 * `emitNavigateCall` writes into a screen's file and is handed no context.
 */
public data class Navigation3(public val basePackage: String) : NavigationStrategy {

    /**
     * `Route`, `AppNavigator` and `AppRoot`, the three names this strategy emits whole.
     *
     * The same three [SimpleBackStack] reserves, because it emits the same navigator and a caller
     * that swapped one strategy for the other keeps its platform entry point.
     */
    override val reservedTopLevelNames: Set<String> =
        setOf(SimpleBackStack.RouteName, SimpleBackStack.NavigatorName, SimpleBackStack.RootName)

    /** `navigation3-ui`, which carries `NavDisplay`, `NavEntry` and `NavKey`. */
    override val libraries: Set<GeneratedLibrary> = setOf(GeneratedLibrary.Navigation3)

    /** What a screen receives: the generated stack, which is also what the display observes. */
    override val navigatorType: KotlinSymbol = KotlinSymbol(basePackage, SimpleBackStack.NavigatorName)

    /**
     * `sealed interface Route : NavKey` then the navigator over it.
     *
     * Pages arrive in the plan's order, by id, and the first seeds the stack — the same list the
     * root builds its entries from, so the two files cannot disagree about which routes exist.
     */
    override fun emitRoutes(pages: List<ResolvedPage>, ctx: FileContext): List<KtDeclaration> =
        listOf(route(pages), navigator(pages.first(), ctx.pkg))

    /** `navigator.navigate(Route.Profile)`, the same statement the other strategies emit. */
    override fun emitNavigateCall(target: ResolvedPage, args: List<KtExpr>): KtStmt = KtStmt.Expr(
        KtExpr.Call(
            KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "navigate"),
            listOf(
                KtArg(
                    null,
                    KtExpr.Ref(KtSymbolRef(KotlinSymbol(basePackage, SimpleBackStack.RouteName), target.name)),
                ),
            ),
        ),
    )

    /**
     * `App.kt`: one `NavDisplay` whose `entryProvider` resolves a key to its page's screen.
     *
     * The stack is the navigator's list, passed unchanged, because the display observes it: a copy
     * would render the start route forever. `onBack` pops through the navigator so a back press
     * and a handler that calls `back` move the same list.
     */
    override fun emitAppRoot(document: ResolvedDocument, ctx: FileContext): KtFile {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        val body: List<KtStmt> = if (pages.isEmpty()) {
            emptyList()
        } else {
            ctx.rootBody(
                KtStmt.Expr(
                    KtExpr.Call(
                        KtExpr.Ref(KtSymbolRef(navDisplay)),
                        listOf(
                            KtArg(
                                "backStack",
                                KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "backStack"),
                            ),
                            KtArg("onBack", onBack()),
                            KtArg("modifier", KtExpr.Name("modifier")),
                        ),
                        KtExpr.Lambda(
                            listOf("key"),
                            listOf(entries(pages, ctx)),
                        ),
                    ),
                ),
            )
        }
        return KtFile(
            ctx.pkg,
            ctx.header,
            listOf(
                KtDeclaration.Function(
                    name = SimpleBackStack.RootName,
                    annotations = listOf(composable),
                    type = null,
                    params = ctx.appRootParameters(),
                    body = body,
                ),
            ),
        )
    }

    /** `{ navigator.back() }`, the pop the display runs before it asks the stack for anything. */
    private fun onBack(): KtExpr = KtExpr.Lambda(
        emptyList(),
        listOf(
            KtStmt.Expr(
                KtExpr.Call(KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "back"), emptyList()),
            ),
        ),
    )

    /**
     * `when (key) { Route.Home -> NavEntry(key) { HomeScreen(…) } … }`, one arm per page.
     *
     * Exhaustive over the sealed hierarchy, so no `else` arm exists to hide a page the document
     * declares and the root forgot.
     */
    private fun entries(pages: List<ResolvedPage>, ctx: FileContext): KtStmt = KtStmt.When(
        subject = KtExpr.Name("key"),
        branches = pages.map { page ->
            KtStmt.When.Branch(
                KtExpr.Ref(KtSymbolRef(KotlinSymbol(ctx.pkg, SimpleBackStack.RouteName), page.name)),
                listOf(KtStmt.Expr(entry(page, ctx))),
            )
        },
        otherwise = null,
    )

    /** `NavEntry(key) { HomeScreen(navigator = navigator) }`: the key it was asked for, and that screen. */
    private fun entry(page: ResolvedPage, ctx: FileContext): KtExpr = KtExpr.Call(
        KtExpr.Ref(KtSymbolRef(navEntry)),
        listOf(KtArg(null, KtExpr.Name("key"))),
        KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(screenCall(page, ctx)))),
    )

    /** The call to [page]'s own screen, handing down exactly the receivers it declared. */
    private fun screenCall(page: ResolvedPage, ctx: FileContext): KtExpr = KtExpr.Call(
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(ctx.pkg + ".screens", page.name + ReservedCodegenNames.ScreenSuffix))),
        ctx.receiversOf(page).map { KtArg(it.member, KtExpr.Name(it.member)) },
    )

    /**
     * One `data object` per page, all of them `NavKey`s.
     *
     * `data` because the display compares keys, and `sealed` because the page list is the whole
     * hierarchy — a handler cannot name a route that is not a page of this document.
     */
    private fun route(pages: List<ResolvedPage>): KtDeclaration.Interface = KtDeclaration.Interface(
        name = SimpleBackStack.RouteName,
        annotations = emptyList(),
        sealed = true,
        members = pages.map { page ->
            KtDeclaration.Object(
                name = page.name,
                annotations = emptyList(),
                data = true,
                supertypes = listOf(KtExpr.Name(SimpleBackStack.RouteName)),
            )
        },
        supertypes = listOf(KtExpr.Ref(KtSymbolRef(navKey))),
    )

    /**
     * The stack, seeded with the start route and read as its last element.
     *
     * Public, unlike [SimpleBackStack]'s: the display has to observe the very list this navigator
     * pushes onto, and a private one would leave the root rendering a stack nothing updates.
     */
    private fun navigator(start: ResolvedPage, pkg: String): KtDeclaration.Class {
        return KtDeclaration.Class(
            name = SimpleBackStack.NavigatorName,
            annotations = emptyList(),
            members = listOf(current(pkg), navigate(pkg), back()),
            constructorProperties = listOf(
                KtDeclaration.Property(
                    name = "backStack",
                    annotations = emptyList(),
                    type = KtExpr.TypeApplication(
                        KtExpr.Ref(KtSymbolRef(snapshotStateList)),
                        listOf(KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, SimpleBackStack.RouteName)))),
                    ),
                    mutable = false,
                    initializer = KtExpr.Call(
                        KtExpr.Ref(KtSymbolRef(stateList)),
                        listOf(
                            KtArg(
                                null,
                                KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, SimpleBackStack.RouteName), start.name)),
                            ),
                        ),
                    ),
                    delegate = null,
                    getter = null,
                ),
            ),
        )
    }

    /** `current` is the top of the stack, which is where a push lands. */
    private fun current(pkg: String): KtDeclaration.Property = KtDeclaration.Property(
        name = "current",
        annotations = emptyList(),
        type = KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, SimpleBackStack.RouteName))),
        mutable = false,
        initializer = null,
        delegate = null,
        getter = KtExpr.Call(KtExpr.Member(KtExpr.Name("backStack"), "last"), emptyList()),
    )

    /** A push, so the key the handler named is the one the display shows next. */
    private fun navigate(pkg: String): KtDeclaration.Function = KtDeclaration.Function(
        name = "navigate",
        annotations = emptyList(),
        type = null,
        params = listOf(KtParam("route", KtExpr.Ref(KtSymbolRef(KotlinSymbol(pkg, SimpleBackStack.RouteName))), null)),
        body = listOf(
            KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.Member(KtExpr.Name("backStack"), "add"),
                    listOf(KtArg(null, KtExpr.Name("route"))),
                ),
            ),
        ),
    )

    /** Pop and report, guarded so the root of the stack survives a back press. */
    private fun back(): KtDeclaration.Function = KtDeclaration.Function(
        name = "back",
        annotations = emptyList(),
        type = KtExpr.Name("Boolean"),
        params = emptyList(),
        body = listOf(
            KtStmt.Expr(
                KtExpr.IfElse(
                    KtExpr.Binary(
                        KtOp.Gt,
                        KtExpr.Member(KtExpr.Name("backStack"), "size"),
                        KtExpr.Literal("1"),
                    ),
                    KtExpr.Lambda(
                        emptyList(),
                        listOf(
                            KtStmt.Expr(
                                KtExpr.Call(
                                    KtExpr.Member(KtExpr.Name("backStack"), "removeAt"),
                                    listOf(KtArg(null, KtExpr.Member(KtExpr.Name("backStack"), "lastIndex"))),
                                ),
                            ),
                            returned(KtExpr.Literal("true")),
                        ),
                    ),
                    KtExpr.Lambda(emptyList(), listOf(returned(KtExpr.Literal("false")))),
                ),
            ),
        ),
    )

    private fun returned(value: KtExpr): KtStmt =
        KtStmt.Expr(KtExpr.Call(KtExpr.Name("return"), listOf(KtArg(null, value))))

    private companion object {
        private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
        private val stateList: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "mutableStateListOf")
        private val snapshotStateList: KotlinSymbol =
            KotlinSymbol("androidx.compose.runtime.snapshots", "SnapshotStateList")
        private val navKey: KotlinSymbol = KotlinSymbol("androidx.navigation3.runtime", "NavKey")
        private val navEntry: KotlinSymbol = KotlinSymbol("androidx.navigation3.runtime", "NavEntry")
        private val navDisplay: KotlinSymbol = KotlinSymbol("androidx.navigation3.ui", "NavDisplay")
    }
}
