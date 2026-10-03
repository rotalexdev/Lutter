package dev.rotalex.lutter.generated.compile

import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.CodegenResult
import dev.rotalex.lutter.codegen.KotlinGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §12.1's page and app rows, as the fixture task compiled them into this module.
 *
 * The conformance suite cannot compare app state: `registryFile` composes the screen, never
 * `AppRoot`, and §12.1:967 makes `AppRoot` the only place `LocalAppState` is provided — so a
 * screen reading it throws at composition on the generated side alone. What *is* provable here is
 * that all three files were generated, that they compile (the module would not build otherwise),
 * and that the declarations §12.1 names are in them.
 */
class StateFixtureTest {

    private companion object {
        const val PACKAGE: String = "dev.rotalex.lutter.generated."
        const val FIXTURE: String = "text_field_state"
    }

    @Test
    fun `a page's state becomes a stable class its screen takes`() {
        val screen = generate(FIXTURE).content("screens/HomeScreen.kt")

        assertTrue(screen.contains("@Stable\npublic class HomeScreenState {"), screen)
        assertTrue(screen.contains("public var count: Int by mutableStateOf(0)"), screen)
        assertTrue(screen.contains("public var label: String by mutableStateOf(\"Clicks\")"), screen)
        assertTrue(screen.contains("public fun rememberHomeScreenState(): HomeScreenState {"), screen)
        assertTrue(screen.contains("state: HomeScreenState = rememberHomeScreenState()"), screen)
    }

    @Test
    fun `a derived declaration is a getter over its own sibling`() {
        val screen = generate(FIXTURE).content("screens/HomeScreen.kt")

        // The assertion is the absence of `state.` in the getter: the class body has no such
        // parameter, and a read that resolved through the screen's spelling would not compile.
        assertTrue(screen.contains("get() = count + 1"), screen)
        assertTrue(!screen.contains("state.count"), screen)
    }

    @Test
    fun `app state is its own file and AppRoot provides it`() {
        val files = generate(FIXTURE)

        assertEquals(
            listOf("App.kt", "screens/HomeScreen.kt", "state/AppState.kt"),
            files.files.files.map { it.path },
        )
        val holder = files.content("state/AppState.kt")
        assertTrue(holder.contains("public class AppState {"), holder)
        assertTrue(
            holder.contains(
                "public val LocalAppState: ProvidableCompositionLocal<AppState> = " +
                    "staticCompositionLocalOf<AppState> {",
            ),
            holder,
        )
        val root = files.content("App.kt")
        assertTrue(root.contains("CompositionLocalProvider("), root)
        assertTrue(root.contains("LocalAppState.provides(state),"), root)
    }

    @Test
    fun `a document with no app state keeps AppRoot unwrapped and its screen unparameterised`() {
        val files = generate("column_text")

        assertEquals(listOf("App.kt", "screens/HomeScreen.kt"), files.files.files.map { it.path })
        val root = files.content("App.kt")
        val screen = files.content("screens/HomeScreen.kt")
        assertTrue(!root.contains("CompositionLocalProvider"), root)
        assertTrue(!screen.contains("ScreenState"), screen)
    }

    /** One fixture decoded, analyzed and generated; the task's own output asserted, not trusted. */
    private fun generate(id: String): CodegenResult {
        val schema = ConformanceHarness.schema()
        val analysis = ConformanceHarness.analyze(schema, id)
        assertTrue(analysis.diagnostics.isEmpty(), "fixture '$id': ${analysis.diagnostics}")
        val resolved = assertNotNull(analysis.resolved, "fixture '$id' resolved nothing")
        val files = KotlinGenerator(schema, CodegenOptions(PACKAGE + id))
            .generate(resolved, analysis.diagnostics)
        assertTrue(
            files.diagnostics.none { it.severity == Severity.Error },
            "fixture '$id': ${files.diagnostics}",
        )
        return files
    }

    private fun CodegenResult.content(path: String): String =
        files.files.firstOrNull { it.path == path }?.content
            ?: error("no '$path' in ${files.files.map { it.path }}")
}