package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.analysis.resolved.ResolvedPage
import dev.rotalex.lutter.analysis.resolved.ResolvedTheme
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §15.5's seam before any container exists: `NoInjection` must be the state strategy's own answer.
 *
 * The test is about *identity*, not about text: a container strategy replaces who builds a holder,
 * and the only way for the default to be provably a no-op is for it to forward the same `KtParam`
 * the state strategy builds. The goldens then prove the emission as a whole did not move.
 */
class InjectionStrategyTest {

    private val screens: String = "com.example.app.screens"

    /** Selecting nothing is the default, and it adds no dependency. */
    @Test
    fun `no injection is the default and needs no library`() {
        assertEquals(NoInjection, CodegenOptions("com.example.app").injection)
        assertTrue(NoInjection.libraries.isEmpty(), "the default generated app stays dependency-free")
    }

    /**
     * Both state strategies, because the forwarding must not assume one: `rememberHomeScreenState()`
     * and `viewModel()` are different parameters of different types with different defaults.
     */
    @Test
    fun `no injection forwards the state strategy's own parameter`() {
        assertEquals(
            ComposeSnapshotState.pageParameter("Home", screens),
            NoInjection.holderParameter("Home", ComposeSnapshotState, screens),
        )
        assertEquals(
            ViewModelState.pageParameter("Home", screens),
            NoInjection.holderParameter("Home", ViewModelState, screens),
        )
    }

    /**
     * `StateEmitter` asks the option rather than the strategy, so a strategy a caller supplied is
     * what reaches the screen: a seam that only forwarded through the default would pass the test
     * above and change nothing.
     */
    @Test
    fun `the emitter takes the holder parameter from the selected strategy`() {
        val document = documentOf(page())
        val provided = KtParam("state", KtExpr.Name("HomeScreenState"), KtExpr.Name("providedState"))
        val strategy: InjectionStrategy = object : InjectionStrategy {
            override fun holderParameter(page: String, state: StateStrategy, screensPackage: String): KtParam =
                provided
        }

        val emitter = StateEmitter(
            document,
            CodegenOptions("com.example.app", injection = strategy),
            functions(),
        )

        assertEquals(provided, emitter.pageParameter(page()))
    }

    /** The default pair, through the emitter: the state strategy's parameter, unchanged. */
    @Test
    fun `the emitter's default is the state strategy's parameter`() {
        val emitter = StateEmitter(documentOf(page()), CodegenOptions("com.example.app"), functions())

        assertEquals(ComposeSnapshotState.pageParameter("Home", screens), emitter.pageParameter(page()))
    }

    /** No function is read: the holder lookup names no document-declared function. */
    private fun functions(): Registry<FunctionId, FunctionSpec> = RegistryBuilder<FunctionId, FunctionSpec>().build()

    private fun page(): ResolvedPage {
        val root = ResolvedNode(
            NodeId("n_root"),
            ComponentType("core.Column"),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptySet(),
        )
        return ResolvedPage(PageId("p_home"), "Home", "home", root)
    }

    /** Only what `pageParameter` reads: a page's name is the whole of it. */
    private fun documentOf(page: ResolvedPage): ResolvedDocument = ResolvedDocument(
        mapOf(page.id to page),
        emptyMap(),
        mapOf(page.root.id to page.root),
        ResolvedTheme(null),
        emptyList(),
        emptyMap(),
    )
}
