package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Navigation Compose: a `NavHost` and the controller it runs on, one destination per page.
 *
 * The second strategy, and the first that owns a library. §16:1071's rule is what keeps it this
 * small: components never learn navigation, so the whole of what changes is the type a screen
 * receives and the three declarations a handler, a route and the root read.
 *
 * ### Routes are strings, and named
 *
 * `NavHost` addresses a destination by its route string, so `Route` here is an object of `String`
 * constants rather than [SimpleBackStack]'s sealed hierarchy. The *name* of each constant is the
 * page's — the same name [SimpleBackStack] gives its route object — and its value is the page's
 * own `route`, which is the document's text and not this strategy's invention. That is what keeps
 * the two strategies' call sites reading alike: both spell `Route.Profile`.
 *
 * [basePackage] is what the strategy cannot be asked for, for [SimpleBackStack]'s reason:
 * `emitNavigateCall` writes into a screen's file and is handed no context, so the package `Route`
 * lives in has to be a field.
 */
public data class NavigationCompose(public val basePackage: String) : NavigationStrategy {

    /**
     * `Route` and `AppRoot`, the two names this strategy emits whole.
     *
     * `AppNavigator` is absent on purpose: this strategy emits no such class, so a document that
     * declares one is not colliding with anything. [SimpleBackStack] keeps it reserved, which is
     * why a document meant for both stays away from it.
     */
    override val reservedTopLevelNames: Set<String> =
        setOf(SimpleBackStack.RouteName, SimpleBackStack.RootName)

    /** What a screen receives: the controller, not a generated stack. */
    override val navigatorType: KotlinSymbol = NavigateController

    /**
     * `object Route { val Home: String = "home" … }`, one constant per page.
     *
     * Pages arrive in the plan's order, by id, so two runs over one document print the same file.
     */
    override fun emitRoutes(pages: List<ResolvedPage>, ctx: FileContext): List<KtDeclaration> =
        listOf(routes(pages))

    /**
     * `navigator.navigate(Route.Profile)`, which is the same statement [SimpleBackStack] emits.
     *
     * The controller's `navigate` takes the route string, and `Route.Profile` is that string: the
     * call reads the same under both strategies because the route object is what a handler names.
     */
    override fun emitNavigateCall(target: ResolvedPage, args: List<KtExpr>): KtStmt = KtStmt.Expr(
        KtExpr.Call(
            KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "navigate"),
            listOf(KtArg(null, routeOf(target))),
        ),
    )

    /**
     * `App.kt`: one `NavHost` whose graph declares every page as a destination.
     *
     * The graph is the root's body, so the composition that dispatches on the controller is the
     * one `NavHost` starts: there is no separate `when` to fall out of step with it. Each
     * destination calls its screen with the receivers that screen asked for — the controller
     * among them when a handler used it — and no modifier, because the modifier already went on
     * the `NavHost` and a screen inside the graph has nothing left to size.
     */
    override fun emitAppRoot(document: ResolvedDocument, ctx: FileContext): KtFile {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        val body: List<KtStmt> = if (pages.isEmpty()) {
            emptyList()
        } else {
            ctx.appState(
                KtStmt.Expr(
                    KtExpr.Call(
                        KtExpr.Ref(KtSymbolRef(NavHost)),
                        listOf(
                            KtArg("navController", KtExpr.Name(ActionReceiver.Navigator.member)),
                            KtArg("startDestination", routeOf(pages.first())),
                            KtArg("modifier", KtExpr.Name("modifier")),
                        ),
                        KtExpr.Lambda(emptyList(), pages.map { destination(it, ctx) }),
                    ),
                ),
            )
        }
        val parameters: List<KtParam> = ctx.appRootParameters()
        return KtFile(
            ctx.pkg,
            ctx.header,
            listOf(
                KtDeclaration.Function(
                    name = SimpleBackStack.RootName,
                    annotations = listOf(composable),
                    type = null,
                    params = parameters,
                    body = body,
                ),
            ),
        )
    }

    /** `object Route` over [pages], each constant named for the page and valued with its route. */
    private fun routes(pages: List<ResolvedPage>): KtDeclaration = KtDeclaration.Object(
        name = SimpleBackStack.RouteName,
        annotations = emptyList(),
        data = false,
        supertypes = emptyList(),
        members = pages.map { page ->
            KtDeclaration.Property(
                name = page.name,
                annotations = emptyList(),
                type = KtExpr.Ref(KtSymbolRef(stringType)),
                mutable = false,
                initializer = KtExpr.Literal("\"" + page.route + "\""),
                delegate = null,
                getter = null,
            )
        },
    )

    /**
     * One graph entry: `composable(Route.Home) { HomeScreen(navigator = navigator) }`.
     *
     * The content lambda is a composable with a `NavBackStackEntry` receiver; this emission takes
     * neither, because a screen is handed what it declared and the entry is not one of those.
     */
    private fun destination(page: ResolvedPage, ctx: FileContext): KtStmt = KtStmt.Expr(
        KtExpr.Call(
            KtExpr.Ref(KtSymbolRef(composableDestination)),
            listOf(KtArg(null, routeOf(page))),
            KtExpr.Lambda(emptyList(), listOf(KtStmt.Expr(screenCall(page, ctx)))),
        ),
    )

    /** The call to [page]'s own screen, handing down exactly the receivers it declared. */
    private fun screenCall(page: ResolvedPage, ctx: FileContext): KtExpr = KtExpr.Call(
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(basePackage + ".screens", page.name + ReservedCodegenNames.ScreenSuffix))),
        ctx.receiversOf(page).map { KtArg(it.member, KtExpr.Name(it.member)) },
    )

    /** `Route.<page>`, written against [basePackage] rather than the calling file's package. */
    private fun routeOf(page: ResolvedPage): KtExpr =
        KtExpr.Ref(KtSymbolRef(KotlinSymbol(basePackage, SimpleBackStack.RouteName), page.name))

    private companion object {
        private val NavHost: KotlinSymbol = KotlinSymbol("androidx.navigation.compose", "NavHost")
        private val composableDestination: KotlinSymbol =
            KotlinSymbol("androidx.navigation.compose", "composable")
        private val NavigateController: KotlinSymbol = KotlinSymbol("androidx.navigation", "NavHostController")
        private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
        private val stringType: KotlinSymbol = KotlinSymbol("kotlin", "String")
    }
}
