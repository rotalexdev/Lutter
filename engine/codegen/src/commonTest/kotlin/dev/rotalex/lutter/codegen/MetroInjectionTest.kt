package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.KotlinSymbol
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Metro's answers, printed rather than described.
 *
 * The graph is the one container this seam emits whose body a compiler plugin has to accept: a
 * `@DependencyGraph` interface with a `@Provides` body, a `@Multibinds` map contributed into by
 * `@IntoMap`/`@ViewModelKey`, and a `MetroViewModelFactory` subclass the graph binds itself. Every
 * assertion below is text the plugin reads, so a change the plugin would reject shows here first.
 */
class MetroInjectionTest {

    private val screens: String = "com.example.app.screens"

    /** Same name, same type — only who builds the holder changes. */
    @Test
    fun `metro keeps the state strategy's parameter and changes only who builds it`() {
        val declared: KtParam = ViewModelState.pageParameter("Home", screens)
        val injected: KtParam = Metro.holderParameter("Home", ViewModelState, screens)

        assertEquals(declared.name, injected.name)
        assertEquals(declared.type, injected.type)
        assertEquals("metroViewModel()", printedDefault(injected))
    }

    @Test
    fun `metro refuses a holder the composition remembers`() {
        assertNull(Metro.refusal(ViewModelState))
        assertNotNull(Metro.refusal(ComposeSnapshotState))
    }

    /** The plugin is an artifact too: `metro-runtime` alone compiles and resolves nothing. */
    @Test
    fun `metro carries the artifacts and the plugin its own source needs`() {
        assertEquals(
            setOf(GeneratedLibrary.MetroRuntime, GeneratedLibrary.MetroxViewModelCompose),
            Metro.libraries,
        )
        assertEquals(setOf(GeneratedPlugin.Metro), Metro.plugins)
    }

    /**
     * One contribution per page holder, and the factory that reads them.
     *
     * The contribution returns the `ViewModel` rather than `() -> ViewModel`: Metro treats a
     * parameter-less function type as a provider of its own and refuses it as a unique binding.
     */
    @Test
    fun `the graph contributes every page holder and binds its factory`() {
        val printed: String = printDeclarations(Metro.module(listOf(HOME, SETTINGS)))

        assertContains(printed, "@DependencyGraph")
        assertContains(printed, "public interface LutterGraph : ViewModelGraph")
        assertContains(printed, "@ViewModelKey(HomeViewModel::class)")
        assertContains(printed, "public fun provideHomeViewModel(): HomeViewModel {")
        assertContains(printed, "return HomeViewModel()")
        assertContains(printed, "@Provides")
        assertContains(printed, "@IntoMap")
        assertContains(printed, "public class LutterViewModelFactory(")
        assertContains(printed, "override val viewModelProviders:")
        assertContains(printed, ") : MetroViewModelFactory()")
        assertContains(printed, "public fun provideLutterViewModelFactory(")
        assertContains(printed, "import dev.zacsweers.metro.DependencyGraph")
        assertContains(printed, "import dev.zacsweers.metrox.viewmodel.ViewModelGraph")
        assertContains(printed, "import com.example.app.screens.HomeViewModel")
    }

    /**
     * A graph with nothing contributed is still a graph.
     *
     * `AppRoot` names `LutterGraph` unconditionally, so a document with no holder needs a file to
     * name; without it the emitted app does not compile at all.
     */
    @Test
    fun `a document with no holder still gets an empty graph`() {
        val printed: String = printDeclarations(Metro.module(emptyList()))

        assertContains(printed, "public interface LutterGraph : ViewModelGraph")
        assertContains(printed, "public class LutterViewModelFactory(")
        assertTrue("@ViewModelKey" !in printed, printed)
    }

    /** Built above the content, installed into the composition local a lookup reads. */
    @Test
    fun `the root creates the graph and provides the factory`() {
        val content: List<KtStmt> = listOf(KtStmt.Expr(KtExpr.Name("HomeScreen")))
        val printed: String = printRoot(Metro.appRoot(content, DI))

        assertContains(printed, "val graph = createGraph<LutterGraph>()")
        assertContains(printed, "CompositionLocalProvider(")
        assertContains(printed, "LocalMetroViewModelFactory provides graph.metroViewModelFactory")
        assertContains(printed, "import dev.zacsweers.metro.createGraph")
        assertContains(printed, "import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory")
        assertTrue(
            Metro.appRoot(emptyList(), DI).isEmpty(),
            "a document with no content has nothing for a container to provide for",
        )
    }

    private fun printedDefault(param: KtParam): String {
        val expression: KtExpr = param.default ?: error("a holder parameter always has a default")
        val property: KtDeclaration = KtDeclaration.Property(
            name = "probe",
            annotations = emptyList(),
            type = null,
            mutable = false,
            initializer = expression,
            delegate = null,
            getter = null,
            visibility = "private",
        )
        return KtPrinter().print(KtFile("com.example.app.screens", null, listOf(property)))
            .lineSequence()
            .first { it.contains("private val probe") }
            .substringAfter("= ")
            .trim()
    }

    private fun printDeclarations(declarations: List<KtDeclaration>): String =
        KtPrinter().print(KtFile(DI, null, declarations))

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
        private const val DI: String = "com.example.app.di"

        private val HOME: KotlinSymbol = KotlinSymbol("com.example.app.screens", "HomeViewModel")
        private val SETTINGS: KotlinSymbol = KotlinSymbol("com.example.app.screens", "SettingsViewModel")
    }
}
