package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a `doc.*` instance actually draws, on the runtime, with a real composition.
 *
 * `:engine:runtime`'s own tests stop at `DefaultRenderScope.expand`, which decides everything
 * about the document and nothing about pixels. These close that half: a real schema, a real
 * analyzer, a real `UiScreen`, and the tree Compose produced.
 *
 * ### Why this file exists at all, when `ConformanceTest` compares the two backends
 *
 * A comparison says the two sides agree. It does not say either of them is right, and a bug both
 * sides share is invisible to it by construction — which is the hole R3 is about. So the first
 * test here is the independent half: the **runtime's own** reading of the fixture's params and
 * slots, with no generated code anywhere in the test. It is the reference the conformance
 * comparison is measured against, and the reason two differently-`label`ed instances of one
 * declaration are worth having in the fixture at all.
 *
 * It therefore reads the **same document** `ConformanceTest` does, rather than a second one built
 * here that would answer the same question twice and could drift from the first. The other two are
 * built here because they are meant to be *reported*, and `generateFixtures` runs the CLI over
 * `fixtures/` where the CLI exits non-zero on an analysis error.
 */
@OptIn(ExperimentalTestApi::class)
class ComponentRuntimeTest {

    private val home: PageId = PageId("p_home")

    /**
     * The one fixture's tree, read straight off the composition.
     *
     * The whole ordered list rather than a set of "is this text present" checks, because the order
     * is half of what is being proved: each instance's label has to arrive **before** its own
     * slot's content, so a runtime that filled the slots from the wrong instance would still have
     * every word on screen and would fail here.
     *
     * The five strings are three different mechanisms. `Live` and `Idle` are the declaration's
     * `label` parameter, read inside a body that declares it once. `amber body` and `green body`
     * are the call sites' slot content, drawn through an outlet in a different tree.
     * `Tap` is the page's own button label, the element that is *not* inside any component and
     * anchors the order.
     */
    @Test
    fun `an instance draws its declaration body with the instance's params and its slot`() =
        runComposeUiTest {
            val reported = mutableListOf<RuntimeDiagnostic>()
            val resolved = resolveFixture()

            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, home, host(reported))
            }

            assertEquals(
                listOf("Live", "amber body", "Idle", "green body", "Tap"),
                texts(),
                "two instances, two labels, two slots",
            )
            // Nothing was reported, which is the other half of "it drew": the runtime reports
            // rather than blanking, so a silent tree and a correct one look identical otherwise.
            assertEquals(emptyList<RuntimeDiagnostic>(), reported)
        }

    @Test
    fun `a slot outlet outside a component body reports and draws nothing`() = runComposeUiTest {
        val reported = mutableListOf<RuntimeDiagnostic>()
        val resolved = resolve(outletOutsideBody())

        setContent {
            UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, home, host(reported))
        }
        waitForIdle()

        // An outlet with no instance open has no children to draw. Reporting is what tells that
        // apart from a component that drew an empty column, which reads on screen as a bug.
        assertEquals(1, reported.size)
        assertTrue(reported.single().message.contains("outside a component body"), reported.single().message)
        assertNotNull(reported.single().nodeId, "the finding must name the node it is about")
    }

    @Test
    fun `a component that instantiates itself reports instead of recursing forever`() =
        runComposeUiTest {
            val reported = mutableListOf<RuntimeDiagnostic>()
            // Pass 1 walks nodes and the inner instance is not a descendant of the outer one, so
            // analysis has nothing to say about it: a self-instantiating component resolves
            // cleanly and is only caught when it is drawn. That is the whole of R1.
            val resolved = resolve(recursive())

            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, home, host(reported))
            }
            waitForIdle()

            // One finding and a composition that finished: the alternative is a stack overflow,
            // which no assertion survives.
            assertEquals(1, reported.size)
            assertTrue(reported.single().message.contains("instantiates itself"), reported.single().message)
        }

    // -------------------------------------------------------------------------------------
    // Documents
    // -------------------------------------------------------------------------------------

    /** An outlet in a page's own tree: there is no instance, so there is nothing to draw. */
    private fun outletOutsideBody(): UiDocument = buildDocument("OutletOutsideBody") {
        page(name = "Home", route = "home", id = home) {
            node("core.Column") {
                val outlet = node("core.SlotOutlet") { prop("slot", Value.Str("content")) }
                slot("children", listOf(outlet))
            }
        }
    }

    /** `c_loop`'s body contains an instance of `c_loop`, a tree below any cycle pass walks. */
    private fun recursive(): UiDocument = buildDocument("Recursive") {
        component(
            id = ComponentDeclId("c_loop"),
            name = "Loop",
            params = listOf(ParamDecl(ParamName("label"), TypeRef.Str)),
        ) {
            node("core.Column") {
                val again = node("doc.c_loop") { prop("label", Value.Str("again")) }
                slot("children", listOf(again))
            }
        }
        page(name = "Home", route = "home", id = home) {
            node("doc.c_loop") { prop("label", Value.Str("start")) }
        }
    }

    // -------------------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------------------

    /** Every text in the composed tree, in tree order. */
    private fun ComposeUiTest.texts(): List<String> {
        val found = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.valueOrNull(SemanticsProperties.Text)?.forEach { found += it.text }
            for (child in node.children) walk(child)
        }
        walk(onRoot().fetchSemanticsNode())
        return found
    }

    /** The conformance fixture, decoded and analyzed — the same document `ConformanceTest` uses. */
    private fun resolveFixture(): ResolvedDocument {
        val result = ConformanceHarness.analyze(ConformanceHarness.schema(), COMPONENT)
        assertTrue(
            result.diagnostics.none { it.severity == Severity.Error },
            "fixture '$COMPONENT' does not resolve: ${result.diagnostics}",
        )
        return assertNotNull(result.resolved) { "fixture '$COMPONENT' resolved nothing" }
    }

    private fun resolve(document: UiDocument): ResolvedDocument {
        val result = BuiltinEngine.analyze(ConformanceHarness.schema(), document)
        assertTrue(
            result.diagnostics.none { it.severity == Severity.Error },
            "the document does not resolve: ${result.diagnostics}",
        )
        return assertNotNull(result.resolved) { "the document resolved nothing" }
    }

    private fun host(reported: MutableList<RuntimeDiagnostic>): RuntimeEnvironment =
        RuntimeEnvironment(navigator = Nowhere, diagnostics = { finding -> reported += finding })

    private object Nowhere : Navigator {
        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }

    private companion object {
        const val COMPONENT: String = "component_param_slot"
    }
}

// `SemanticsConfiguration` has no optional read in this Compose version, so this is a `get` that
// may throw and `runCatching` is the whole of the optionality. The same helper exists privately
// in `ConformanceTest` and `RecompositionTest`; a shared one is a fourth file to change when the
// version moves, and two of the three are already copies.
private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
    runCatching { get(key) }.getOrNull()