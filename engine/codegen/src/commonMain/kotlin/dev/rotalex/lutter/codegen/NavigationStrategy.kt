package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.schema.component.KotlinSymbol

/**
 * Which navigation the emitted code runs, and the three declarations it is made of.
 *
 * An interface rather than an enum so a plugin can supply one: the `Route` hierarchy and the
 * `navigate` call a handler writes are the same decision, and one document cannot have two of
 * them. Components learn none of this — only a `nav.*` action and the generated `AppRoot` do.
 */
public interface NavigationStrategy {

    /**
     * The top-level names this strategy emits whole, which a document may therefore not declare.
     *
     * A property rather than something a caller reads off emitted files, because the answer has
     * to exist before the first file is written: `ReservedCodegenNames` checks a document's names
     * against this set, and a strategy that declared fewer names than it emits would hand back a
     * document that only fails later.
     */
    public val reservedTopLevelNames: Set<String>

    /** The sealed `Route` and the navigator over it, as the declarations of `Navigation.kt`. */
    public fun emitRoutes(pages: List<ResolvedPage>, ctx: FileContext): List<KtDeclaration>

    /**
     * The statement a `nav.navigate` handler carries.
     *
     * [args] is the route's arguments, and this strategy reads none of them: the navigator takes
     * a route alone, so a step carrying arguments is refused before it reaches here.
     */
    public fun emitNavigateCall(target: ResolvedPage, args: List<KtExpr>): KtStmt

    /** `App.kt` whole: the root composable, the receivers it is given and the screen each route reaches. */
    public fun emitAppRoot(document: ResolvedDocument, ctx: FileContext): KtFile
}

/**
 * What one emitted file needs from the run emitting it.
 *
 * A strategy is a value and a document is not, so the facts that differ per run travel beside the
 * strategy rather than inside it. [pkg] qualifies what this file declares; a symbol the file only
 * *calls* is qualified with the strategy's own base package instead, because that one is read
 * from a screen's file and not from this one.
 */
public class FileContext(

    /** The package the file is written into, which qualifies every declaration it holds. */
    public val pkg: String,

    /** The header comment above the package, or null under a policy that writes none. */
    public val header: String?,

    /**
     * What each screen emitted so far demands of its caller, keyed by the page it renders.
     *
     * Keyed rather than one list because a strategy's root may call every screen: a receiver
     * only one page's handlers used is not a parameter of another page's screen, and handing it
     * there would name an argument the call has no home for.
     */
    public val receivers: Map<PageId, List<ActionReceiver>>,

    /**
     * Wraps the file's content in §12.1's app-state provider.
     *
     * A function rather than the emitter itself because wrapping is all `AppRoot` needs of
     * §12.1, and identity is the whole of it for a document that declares no app state.
     */
    public val appState: (KtStmt) -> List<KtStmt>,
)

/**
 * The one strategy that exists: a back stack in a snapshot list, and no dependency at all.
 *
 * Sits beside [NavigationStrategy] rather than inside it for the reason `ComposeSnapshotState`
 * does: it is a whole implementation of the interface, not a piece of its vocabulary, and §16.8
 * spells the default as the bare name.
 *
 * [basePackage] is what the strategy cannot be asked for. `emitNavigateCall` writes into a
 * screen's file and gets no context, so the package `Route` is generated into is this field.
 */
public data class SimpleBackStack(public val basePackage: String) : NavigationStrategy {

    override val reservedTopLevelNames: Set<String> = setOf(RouteName, NavigatorName, RootName)

    /**
     * `sealed interface Route` then `class AppNavigator`.
     *
     * Pages arrive in the plan's order, by id, and the first of them is the route the stack is
     * seeded with — the same list `AppRoot` builds its arms from, so the two files cannot
     * disagree about which routes exist. A document with no page is refused by the generator,
     * which is why the first page is read without a fallback here.
     */
    override fun emitRoutes(pages: List<ResolvedPage>, ctx: FileContext): List<KtDeclaration> =
        listOf(route(pages), navigator(pages.first(), ctx))

    /**
     * `navigator.navigate(Route.Profile)`, written against [basePackage] rather than a file's
     * package: the statement lands in a screen, and `Route` is not in that screen's package.
     */
    override fun emitNavigateCall(target: ResolvedPage, args: List<KtExpr>): KtStmt = KtStmt.Expr(
        KtExpr.Call(
            KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "navigate"),
            listOf(KtArg(null, KtExpr.Ref(KtSymbolRef(KotlinSymbol(basePackage, RouteName), target.name)))),
        ),
    )

    /**
     * `when (navigator.current) { Route.Home -> HomeScreen(…) … }`, one arm per page.
     *
     * The arms are [document]'s pages in the plan's order and the routes are built from the same
     * list, so an arm cannot go missing: a route no arm matches is unreachable rather than a gap
     * to catch, and an `else` arm could only have shown the start page for it, which would hide
     * that gap rather than close it.
     */
    override fun emitAppRoot(document: ResolvedDocument, ctx: FileContext): KtFile {
        val pages: List<ResolvedPage> = document.pages.entries.sortedBy { it.key.value }.map { it.value }
        val body: List<KtStmt> = if (pages.isEmpty()) {
            emptyList()
        } else {
            ctx.appState(
                KtStmt.When(
                    subject = KtExpr.Member(KtExpr.Name(ActionReceiver.Navigator.member), "current"),
                    branches = pages.map { page -> screenArm(page, ctx) },
                    otherwise = null,
                ),
            )
        }
        val parameters: List<KtParam> = signature(ctx).map { receiver ->
            KtParam(
                receiver.member,
                KtExpr.Ref(KtSymbolRef(KotlinSymbol(ctx.pkg, checkNotNull(receiver.type)))),
                null,
            )
        } + listOf(modifierParam())
        return KtFile(
            ctx.pkg,
            ctx.header,
            listOf(
                KtDeclaration.Function(
                    name = RootName,
                    annotations = listOf(composable),
                    type = null,
                    params = parameters,
                    body = body,
                ),
            ),
        )
    }

    /**
     * One arm: the route of [page], and the call to the screen that page generated.
     *
     * Each receiver is handed down under the name the screen declared it as, so the screen's
     * parameter and this call cannot drift apart.
     */
    private fun screenArm(page: ResolvedPage, ctx: FileContext): KtStmt.When.Branch {
        val screen: KotlinSymbol = KotlinSymbol(
            basePackage + ".screens",
            page.name + ReservedCodegenNames.ScreenSuffix,
        )
        val forwarded: List<KtArg> = declaredBy(page, ctx).map { KtArg(it.member, KtExpr.Name(it.member)) }
        return KtStmt.When.Branch(
            KtExpr.Ref(KtSymbolRef(KotlinSymbol(basePackage, RouteName), page.name)),
            listOf(
                KtStmt.Expr(
                    KtExpr.Call(
                        KtExpr.Ref(KtSymbolRef(screen)),
                        forwarded + KtArg("modifier", KtExpr.Name("modifier")),
                    ),
                ),
            ),
        )
    }

    /**
     * The receivers `AppRoot` declares as parameters.
     *
     * The navigator is one of them whether or not any handler reached for it: the root dispatches
     * on `navigator.current`, so a root declaring none names a subject it holds nothing for. What
     * the screens asked for beside it, sorted so two runs over one document agree.
     *
     * §11.5's `scope` is the odd one out and is filtered out with the rest: the composition owns
     * it, so a screen reads it out of itself and `AppRoot` never declares it.
     */
    private fun signature(ctx: FileContext): List<ActionReceiver> =
        (listOf(ActionReceiver.Navigator) + ctx.receivers.values.flatten())
            .distinct()
            .filter { it.type != null }
            .sortedBy { it.member }

    /** The receivers [page]'s own screen declares, which are the only ones its call may hand down. */
    private fun declaredBy(page: ResolvedPage, ctx: FileContext): List<ActionReceiver> =
        ctx.receivers[page.id].orEmpty().filter { it.type != null }.sortedBy { it.member }

    /**
     * One `data object` per page, named by the page's own name so a handler can spell it.
     *
     * `data` because the stack reads a route back to compare it, and `sealed` because the page
     * list is the whole of the hierarchy — a screen's handler cannot name a route that is not a
     * page of this document.
     */
    private fun route(pages: List<ResolvedPage>): KtDeclaration.Interface = KtDeclaration.Interface(
        name = RouteName,
        annotations = emptyList(),
        sealed = true,
        members = pages.map { page ->
            KtDeclaration.Object(
                name = page.name,
                annotations = emptyList(),
                data = true,
                supertypes = listOf(KtExpr.Name(RouteName)),
            )
        },
    )

    /**
     * The stack, seeded with the start route and read as its last element.
     *
     * A constructor property rather than a parameter beside a member because the back stack is
     * the navigator's whole state, and `private` because nothing outside the three members below
     * reads or replaces it.
     */
    private fun navigator(start: ResolvedPage, ctx: FileContext): KtDeclaration.Class {
        val routeType: KotlinSymbol = KotlinSymbol(ctx.pkg, RouteName)
        return KtDeclaration.Class(
            name = NavigatorName,
            annotations = emptyList(),
            members = listOf(current(routeType), navigate(routeType), back()),
            constructorProperties = listOf(
                KtDeclaration.Property(
                    name = "backStack",
                    annotations = emptyList(),
                    type = KtExpr.TypeApplication(
                        KtExpr.Name("MutableList"),
                        listOf(KtExpr.Ref(KtSymbolRef(routeType))),
                    ),
                    mutable = false,
                    initializer = KtExpr.Call(
                        KtExpr.Ref(KtSymbolRef(stateList)),
                        listOf(KtArg(null, KtExpr.Ref(KtSymbolRef(routeType, start.name)))),
                    ),
                    delegate = null,
                    getter = null,
                    visibility = "private",
                ),
            ),
        )
    }

    /** `current` is the top of the stack, which is where a push lands. */
    private fun current(routeType: KotlinSymbol): KtDeclaration.Property = KtDeclaration.Property(
        name = "current",
        annotations = emptyList(),
        type = KtExpr.Ref(KtSymbolRef(routeType)),
        mutable = false,
        initializer = null,
        delegate = null,
        getter = KtExpr.Call(KtExpr.Member(KtExpr.Name("backStack"), "last"), emptyList()),
    )

    /** A push, so the route the handler named is the one `current` reads next. */
    private fun navigate(routeType: KotlinSymbol): KtDeclaration.Function = KtDeclaration.Function(
        name = "navigate",
        annotations = emptyList(),
        type = null,
        params = listOf(KtParam("route", KtExpr.Ref(KtSymbolRef(routeType)), null)),
        body = listOf(
            KtStmt.Expr(
                KtExpr.Call(
                    KtExpr.Member(KtExpr.Name("backStack"), "add"),
                    listOf(KtArg(null, KtExpr.Name("route"))),
                ),
            ),
        ),
    )

    /**
     * Pop and report, guarded so the root of the stack survives a back press.
     *
     * A block body with an explicit `return` because the IR has an expression body nowhere, which
     * is the same trade §12.1's `remember…State()` makes.
     */
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

    private fun modifierParam(): KtParam =
        KtParam("modifier", KtExpr.Ref(KtSymbolRef(modifierType)), KtExpr.Ref(KtSymbolRef(modifierType)))

    private companion object {
        private val composable: KotlinSymbol = KotlinSymbol("androidx.compose.runtime", "Composable")
        private val modifierType: KotlinSymbol = KotlinSymbol("androidx.compose.ui", "Modifier")
        private val stateList: KotlinSymbol =
            KotlinSymbol("androidx.compose.runtime", "mutableStateListOf")

        /** §30.6's three names, which the plan, this strategy and a handler's call all agree on. */
        public const val RouteName: String = "Route"
        public const val NavigatorName: String = "AppNavigator"
        public const val RootName: String = "AppRoot"
    }
}
