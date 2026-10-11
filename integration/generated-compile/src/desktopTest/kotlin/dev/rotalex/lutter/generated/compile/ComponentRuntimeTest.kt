package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.builtins.compose.BuiltinEngine
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.doc.ParamDecl
import dev.rotalex.lutter.model.doc.SlotDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.DocumentScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.Expr
import dev.rotalex.lutter.model.expr.RefTarget
import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.SlotName
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
 * The documents are built here rather than added to `fixtures/` because two of the three are
 * meant to be *reported*, and `generateFixtures` runs the CLI over that directory where the CLI
 * exits non-zero on an analysis error. The happy path reaches both backends through
 * `ConformanceTest`'s own fixture list; what is asserted here is the runtime half on its own,
 * because that has to be true before there is anything to compare it against.
 */
@OptIn(ExperimentalTestApi::class)
class ComponentRuntimeTest {

    private val home: PageId = PageId("p_home")

    @Test
    fun `an instance draws its declaration body with the instance's params and its slot`() =
        runComposeUiTest {
            val reported = mutableListOf<RuntimeDiagnostic>()
            val resolved = resolve(badge())

            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, home, host(reported))
            }

            // The param, bound at the call site and read inside the declaration's body: what
            // reaches the screen is the value the instance carried.
            assertTrue(onAllNodes(hasText("Live")).fetchSemanticsNodes().isNotEmpty())
            // The slot, filled at the call site and drawn where the outlet stands. The two are
            // written in different trees, and a working `core.SlotOutlet` is the only thing that
            // joins them.
            assertTrue(onAllNodes(hasText("body")).fetchSemanticsNodes().isNotEmpty())
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

    /** `doc.c_badge` with `label = "Live"` and one child in its `content` slot. */
    private fun badge(): UiDocument = buildDocument("Badge") {
        declareBadge(this)
        page(name = "Home", route = "home", id = home) {
            node("doc.c_badge") {
                prop("label", Value.Str("Live"))
                slot("content", listOf(node("m3.Text") { prop("text", Value.Str("body")) }))
            }
        }
    }

    /** The declaration the badge fixture shares: a param, a slot, and the outlet that draws it. */
    private fun declareBadge(scope: DocumentScope) {
        scope.component(
            id = ComponentDeclId("c_badge"),
            name = "Badge",
            params = listOf(ParamDecl(ParamName("label"), TypeRef.Str)),
            slots = listOf(SlotDecl(SlotName("content"))),
        ) {
            node("core.Column") {
                val heading = node("m3.Text") {
                    computed("text", Expr.Ref(RefTarget.Param(ParamName("label"))))
                }
                val outlet = node("core.SlotOutlet") { prop("slot", Value.Str("content")) }
                slot("children", listOf(heading, outlet))
            }
        }
    }

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
}