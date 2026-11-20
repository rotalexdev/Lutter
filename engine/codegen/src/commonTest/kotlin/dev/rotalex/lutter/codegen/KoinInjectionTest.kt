package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.schema.component.KotlinSymbol
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Koin's three answers, printed rather than described.
 *
 * The module and the root are IR, so the assertions are over the text `KtPrinter` produces: a
 * strategy can build a tree that never prints (`viewModel<HomeViewModel>` as a callee, a named
 * argument around a lambda) and only the text shows it.
 */
class KoinInjectionTest {

    private val screens: String = "com.example.app.screens"

    /** The container replaces the default and nothing else: same name, same type, same reads. */
    @Test
    fun `koin keeps the state strategy's parameter and changes only who builds it`() {
        val declared: KtParam = ViewModelState.pageParameter("Home", screens)
        val injected: KtParam = Koin.holderParameter("Home", ViewModelState, screens)

        assertEquals(declared.name, injected.name)
        assertEquals(declared.type, injected.type)
        assertEquals("koinViewModel()", printedDefault(injected))
    }

    /**
     * A remembered holder is refused rather than resolved: `koinInject()` would move the page's
     * state out of the page's lifetime, which is a different policy and not a lookup change.
     */
    @Test
    fun `koin refuses a holder the composition remembers`() {
        assertNull(Koin.refusal(ViewModelState))
        assertNotNull(Koin.refusal(ComposeSnapshotState))
    }

    @Test
    fun `koin carries the artifacts its own source imports`() {
        assertEquals(
            setOf(GeneratedLibrary.KoinCore, GeneratedLibrary.KoinComposeViewModel),
            Koin.libraries,
        )
    }

    /** One declaration per page, keyed by the exact class the screen asks for. */
    @Test
    fun `the module declares every page holder`() {
        val printed: String = printDeclarations(Koin.module(listOf(HOME, SETTINGS)))

        assertContains(printed, "val LutterModule = module {")
        assertContains(printed, "viewModel<HomeViewModel> {")
        assertContains(printed, "viewModel<SettingsViewModel> {")
        assertContains(printed, "import org.koin.core.module.dsl.viewModel")
        assertContains(printed, "import org.koin.dsl.module")
        assertContains(printed, "import com.example.app.screens.HomeViewModel")
    }

    /** Started above the content, and only when there is content to provide for. */
    @Test
    fun `the root starts the container around the app's body`() {
        val content: List<KtStmt> = listOf(KtStmt.Expr(KtExpr.Name("HomeScreen")))
        val printed: String = printRoot(Koin.appRoot(content))

        assertContains(printed, "KoinApplication(")
        assertContains(printed, "configuration = koinConfiguration {")
        assertContains(printed, "modules(LutterModule)")
        assertContains(printed, "HomeScreen")
        assertContains(printed, "import org.koin.compose.KoinApplication")
        assertContains(printed, "import org.koin.dsl.koinConfiguration")
        assertTrue(
            Koin.appRoot(emptyList()).isEmpty(),
            "a document with no content has nothing for a container to provide for",
        )
    }

    /** The plan, not the emitter, decides the file list: a wrapper that emits one plans one. */
    @Test
    fun `a container plans its own file`() {
        val empty: ResolvedDocument =
            ResolvedDocument(emptyMap(), emptyMap(), emptyMap(), ResolvedTheme(null), emptyList(), emptyMap())

        assertEquals(
            listOf("App.kt", "AppHost.kt", "Navigation.kt"),
            plans(planDocument(empty, "com.example.app", Koin)),
            "a document with no pages has no holder to declare",
        )
        assertEquals(
            listOf("App.kt", "AppHost.kt", "Navigation.kt"),
            plans(planDocument(empty, "com.example.app", NoInjection)),
            "selecting nothing plans nothing new",
        )
    }

    private fun plans(plan: GenPlan): List<String> = plan.files.map(PlannedFile::path)

    private fun printedDefault(param: KtParam): String {
        val expression: KtExpr = param.default ?: error("a holder parameter always has a default")
        return KtPrinter().print(KtFile("com.example.app.screens", null, listOf(property(expression))))
            .lineSequence()
            .first { it.contains("private val probe") }
            .substringAfter("= ")
            .trim()
    }

    private fun property(expression: KtExpr): KtDeclaration = KtDeclaration.Property(
        name = "probe",
        annotations = emptyList(),
        type = null,
        mutable = false,
        initializer = expression,
        delegate = null,
        getter = null,
        visibility = "private",
    )

    private fun printDeclarations(declarations: List<KtDeclaration>): String =
        KtPrinter().print(KtFile("com.example.app.di", null, declarations))

    private fun printRoot(body: List<KtStmt>): String {
        val function: KtDeclaration = KtDeclaration.Function(
            name = "AppRoot",
            annotations = emptyList(),
            type = null,
            params = emptyList(),
            body = body,
        )
        return KtPrinter().print(KtFile("com.example.app", null, listOf(function)))
    }

    private companion object {
        private val HOME: KotlinSymbol = KotlinSymbol("com.example.app.screens", "HomeViewModel")
        private val SETTINGS: KotlinSymbol = KotlinSymbol("com.example.app.screens", "SettingsViewModel")
    }
}
