package dev.rotalex.lutter.generated.compile.thirdparty

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
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.interpreter.env.Destination
import dev.rotalex.lutter.interpreter.env.Navigator
import dev.rotalex.lutter.model.diagnostic.Severity
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.RuntimeEnvironment
import dev.rotalex.lutter.runtime.UiScreen
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The half of §36's rendering row that needs pixels.
 *
 * `ThirdPartyComponentTest` proves the third-party type is registered and that `UiRuntime` refuses
 * to start without its renderer. Neither is a drawing: the registry is a map, and coverage is a
 * set comparison. This file closes the gap with a real composition — a real schema, a real
 * analyzer, a real `UiScreen`, and the tree Compose produced — for a renderer declared in a test
 * source set of a module no engine file depends on.
 *
 * ### Why `desktopTest` and not `commonTest`
 *
 * `runComposeUiTest` is a desktop-only dependency of this module, and everything else in the
 * suite is `commonTest` so the Android host keeps running it too. The split is the same trade
 * `ComponentRuntimeTest` makes: a `commonTest` row cannot render, and a rendering row that runs
 * on one target is still a rendering row.
 *
 * ### What it cannot prove
 *
 * The renderer's Compose choices — the `Column`, the two `Text` calls — are the plugin's, and a
 * pixel comparison against a generated screen would be comparing a component nothing generates
 * here. What is asserted is narrower and is the part §36's row is about: **a component defined
 * outside the engine was reached, its own properties were read back through `RenderScope.props`,
 * its own slot was drawn, and the engine's runtime reported nothing while it happened.**
 */
@OptIn(ExperimentalTestApi::class)
class ThirdPartyRenderTest {

    @Test
    fun `the plugin's component draws its own label, its own count and its own slot`() {
        draw(badgeDocument()) { texts ->
            // The whole ordered list rather than three "is this present" checks, because the order
            // is half of what a renderer that ignored the slot would get wrong: a badge drawing
            // nothing for `content` would still put every word it does draw in the right order.
            assertEquals(listOf("Inbox", "3", "body"), texts)
        }
    }

    /**
     * The disagreement `ThirdPartyComponentTest` refuses to generate from.
     *
     * `md.typography.bodyLarge` carrying `TokenKind.Color` inside a property that declares
     * `Token(Color)` analyses clean, and `LiteralPrinter` refuses it because the name's
     * `md.<kind>.` segment is not the one the kind addresses. The runtime reads the *name*:
     * `DefaultThemeHandle.textStyle` asks `materialRoleOf` for `"typography"` whatever the
     * declared kind was, matches, and hands the renderer a real `TextStyle`.
     *
     * So one valid document renders here and is refused by the generator. That is §4.5's
     * consistency guarantee failing for a property a plugin declared, and it is worth a test
     * rather than a paragraph: a disagreement nobody renders is one nobody has noticed.
     */
    @Test
    fun `a token whose name its kind does not address still draws on the runtime`() {
        draw(badgeDocument(mismatchedAccent = true)) { texts ->
            assertEquals(listOf("Inbox", "3", "body"), texts)
        }
    }

    /**
     * The badge with a slot the document never filled.
     *
     * `ThirdPartyComponentTest` records that `ExactlyOne` is not enforced for a slot the node does
     * not write. The rendering consequence is the half that matters: the renderer calls
     * `RenderSlot` on a key pass 7 carries as an empty list, draws nothing for it, and reports
     * nothing. A renderer that assumed the key was present — or a plugin that read the declaration
     * as a guarantee — is what this catches.
     */
    @Test
    fun `the badge draws without a child when the document fills no slot`() {
        draw(badgeDocument(writeContentSlot = false)) { texts ->
            assertEquals(listOf("Inbox", "3"), texts)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Documents
    // -----------------------------------------------------------------------------------------

    /**
     * The fixture `ThirdPartyComponentTest` builds, at one node.
     *
     * Re-spelled rather than shared because the fixture there is private to it, and a fixture
     * shared between a `commonTest` file and a `desktopTest` assertion is a second thing to move
     * when the component's shape changes. What is *not* re-spelled is the claim: this is the same
     * schema, the same analyzer and the same engine assembly, or the composition would be proving
     * something about a different setup.
     */
    private fun badgeDocument(
        mismatchedAccent: Boolean = false,
        writeContentSlot: Boolean = true,
    ): UiDocument = buildDocument("ThirdPartyBadge") {
        page(name = "Home", route = "home", id = HOME) {
            node("core.Column") {
                // `slot` is what links the badge to the tree: an id nothing slots is an orphan,
                // and `resolve` below refuses a document carrying one. Same fixture as
                // `ThirdPartyComponentTest`, so the same shape.
                val badgeNode: NodeId = node(StatusBadgeSpec.TYPE) {
                    prop("label", Value.Str("Inbox"))
                    prop(
                        "accent",
                        if (mismatchedAccent) {
                            Value.Token(TokenKind.Color, "md.typography.bodyLarge")
                        } else {
                            Value.Token(TokenKind.Color, "md.color.primary")
                        },
                    )
                    prop("unread", Value.Int32(3))
                    if (writeContentSlot) {
                        slot("content", listOf(node("m3.Text") { prop("text", Value.Str("body")) }))
                    }
                }
                slot(CHILDREN, listOf(badgeNode))
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------------------------

    /**
     * Assemble, compose, read the tree, insist nothing was reported.
     *
     * The schema and the runtime are built **outside** [setContent], deliberately: they are
     * immutable values, and building them inside the composable lambda would re-run
     * `UiRuntime`'s coverage check on every recomposition and turn a wiring mistake into a
     * composition crash rather than a test failure.
     *
     * The empty-`reported` assertion is in here rather than in each row because it is the same
     * claim every time: the runtime reports rather than blanking, so a renderer that drew nothing
     * and said nothing is otherwise indistinguishable from one that drew.
     */
    private fun draw(document: UiDocument, assert: (List<String>) -> Unit) = runComposeUiTest {
        val reported = mutableListOf<RuntimeDiagnostic>()
        val resolved = resolve(document)
        val runtime = ThirdPartyEngine.runtime(ThirdPartyEngine.schema())
        val environment = RuntimeEnvironment(navigator = Nowhere, diagnostics = { reported += it })

        setContent {
            UiScreen(runtime, resolved, HOME, environment)
        }

        assert(texts())
        assertEquals(emptyList<RuntimeDiagnostic>(), reported, "the runtime reported while drawing")
    }

    private fun resolve(document: UiDocument): ResolvedDocument {
        val result = Analyzer<ActionSpec, FunctionSpec, TypeSpec>(ThirdPartyEngine.schema()).analyze(document)
        assertTrue(
            result.diagnostics.none { it.severity == Severity.Error },
            "the document does not resolve: ${result.diagnostics}",
        )
        return assertNotNull(result.resolved, "the document resolved nothing")
    }

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

    private object Nowhere : Navigator {
        override val current: Destination = Destination(PageId("p_nowhere"), emptyMap())
        override fun navigate(page: PageId, args: Map<ParamName, Value>): Unit = Unit
        override fun back(): Boolean = false
    }

    private companion object {
        val HOME: PageId = PageId("p_home")
        val CHILDREN: SlotName = SlotName("children")
    }
}

/**
 * `SemanticsConfiguration` has no optional read in this Compose version, so this is a `get` that
 * may throw and `runCatching` is the whole of the optionality.
 *
 * `ComponentRuntimeTest` and `ConformanceTest` each carry a private copy for the same reason: a
 * shared helper across three files is a fourth thing to change when the version moves, and two of
 * the three already are copies. A private top-level function is file-scoped, so this one shadows
 * nothing and is collided with by nothing.
 */
private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
    runCatching { get(key) }.getOrNull()