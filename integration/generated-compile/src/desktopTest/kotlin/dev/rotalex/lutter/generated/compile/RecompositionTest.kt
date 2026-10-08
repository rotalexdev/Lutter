package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.Analyzer
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.generated.text_field_state.screens.HomeScreen
import dev.rotalex.lutter.generated.text_field_state.screens.HomeScreenState
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.StateId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.SnapshotStateStore
import dev.rotalex.lutter.runtime.UiScreen
import dev.rotalex.lutter.serialization.JsonDocumentCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN §32's recomposition test: writing state re-renders the text that read it.
 *
 * ### Why this module
 *
 * `:engine:runtime` holds the store and the evaluator this exercises, and it has no Compose UI
 * test dependency at all — `engine/runtime/build.gradle.kts` declares none and its `src` has no
 * `desktopTest` — so a test there would mean re-earning the configuration and the headless
 * runner this module already has at `integration/generated-compile/build.gradle.kts:31-34`.
 * Both CI jobs run this source set: `conformance` names `:integration:generated-compile:desktopTest`
 * outright and `check` runs `gradle check` across every module.
 *
 * ### Both halves, because they write through different mechanisms
 *
 * The generated screen is written by assigning a `by mutableStateOf` property — code this phase
 * emitted and nothing has executed yet. The runtime screen is written through a
 * [SnapshotStateStore] the test holds, which is the other `mutableStateOf` behind
 * `MapPropertyReader.get` → `source.eval`. The store's own `set`/`get` is unit-tested in
 * `:engine:runtime`; what is new here is that either write reaches a composition.
 *
 * The runtime document is declared here rather than as a fixture because it reads **app** state,
 * and the conformance registry composes a bare screen: a generated read of app state is
 * `LocalAppState.current`, which `registryFile`'s `{ modifier -> HomeScreen(modifier) }` never
 * provides. The generated half therefore takes the `text_field_state` fixture and its page state,
 * which reaches the screen through the `state` parameter instead.
 */
@OptIn(ExperimentalTestApi::class)
class RecompositionTest {

    private companion object {
        val HOME: PageId = PageId("p_home")
        val THEME: StateId = StateId("s_theme")

        /**
         * One page, one `Text`, and one app-state read.
         *
         * App state because that is the store a host writes to: §12.2 puts the page's own store
         * inside the screen, and `rememberPageStateStore` is internal, so nothing outside
         * `:engine:runtime` can reach it.
         */
        val DOCUMENT: String =
            """
            {
              "format": "forge.ui-document",
              "formatVersion": 1,
              "schemaVersion": 1,
              "payload": {
                "app": {"packageName": "com.example.conformance", "startPage": "p_home"},
                "appState": [
                  {"id": "s_theme", "name": "theme", "type": {"type": "str"},
                   "initial": {"type": "str", "v": "light"}}
                ],
                "components": {},
                "meta": {"name": "Recomposition"},
                "nodes": {
                  "n_a": {
                    "id": "n_a",
                    "props": {"text": {"type": "expr", "expr": {"type": "ref",
                      "target": {"type": "state", "id": "s_theme"}}}},
                    "type": "m3.Text"
                  },
                  "n_root": {
                    "id": "n_root",
                    "slots": {"children": ["n_a"]},
                    "type": "core.Column"
                  }
                },
                "pages": {
                  "p_home": {"id": "p_home", "name": "Home", "root": "n_root", "route": "home"}
                }
              }
            }
            """.trimIndent()
    }

    @Test
    fun `writing the generated screen's state re-renders its text`() = runComposeUiTest {
        val held: HomeScreenState = HomeScreenState()
        setContent { HomeScreen(modifier = Modifier, state = held) }
        assertEquals(listOf("Clicks", "Total"), renderedTexts(), "initial")

        held.label = "Taps"
        waitForIdle()

        assertEquals(listOf("Taps", "Total"), renderedTexts(), "after the write")
    }

    @Test
    fun `writing app state re-renders the runtime screen's text`() = runComposeUiTest {
        val resolved: ResolvedDocument = analyzed()
        val store: SnapshotStateStore = SnapshotStateStore.seeded(resolved.appState)
        setContent {
            UiScreen(
                ConformanceHarness.runtime(ConformanceHarness.schema()),
                resolved,
                HOME,
                RuntimeEnvironment(StubNavigator, appState = store)
            )
        }
        assertEquals(listOf("light"), renderedTexts(), "initial")

        store.set(THEME, Value.Str("dark"))
        waitForIdle()

        assertEquals(listOf("dark"), renderedTexts(), "after the write")
    }

    /** Every text in the composed tree, in tree order. */
    private fun ComposeUiTest.renderedTexts(): List<String> {
        val found = mutableListOf<String>()
        collect(onRoot().fetchSemanticsNode(), found)
        return found
    }

    private fun collect(node: SemanticsNode, into: MutableList<String>) {
        read(node.config, SemanticsProperties.Text)?.forEach { into += it.text }
        for (child in node.children) collect(child, into)
    }

    // `SemanticsConfiguration` has no optional read in this Compose version, so this is a `get`
    // that may throw and `runCatching` is the whole of the optionality.
    private fun <T> read(config: SemanticsConfiguration, key: SemanticsPropertyKey<T>): T? =
        runCatching { config.get(key) }.getOrNull()

    /** [DOCUMENT] decoded and analyzed; an errored document resolves nothing. */
    private fun analyzed(): ResolvedDocument {
        val schema = ConformanceHarness.schema()
        val result = Analyzer(schema).analyze(
            JsonDocumentCodec.decode(DOCUMENT.encodeToByteArray()).document,
        )
        assertTrue(result.diagnostics.isEmpty(), "document: ${result.diagnostics}")
        return assertNotNull(result.resolved, "document resolved nothing")
    }

    private object StubNavigator : Navigator {
        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }
}