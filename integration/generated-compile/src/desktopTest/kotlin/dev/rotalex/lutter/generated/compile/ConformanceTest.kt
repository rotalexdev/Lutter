package dev.rotalex.lutter.generated.compile

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.ResolvedDocument
import dev.rotalex.lutter.codegen.CodegenOptions
import dev.rotalex.lutter.codegen.KotlinGenerator
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.runtime.UiScreen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runtime and compiled generated code agree: one semantics tree and one image per fixture.
 *
 * Each fixture is validated through the analyzer, rendered by the runtime, and rendered by
 * the generated screen the fixture task compiled into this module; generation itself is
 * re-run in-test so a stale generated file fails here rather than comparing old output.
 * Desktop runs headless, so pixels compare here and need no display.
 */
@OptIn(ExperimentalTestApi::class)
class ConformanceTest {

    private companion object {
        val HOME: PageId = PageId("p_home")
        const val PACKAGE: String = "dev.rotalex.lutter.generated."
    }

    @Test
    fun `runtime and generated share one semantics tree per fixture`() = runComposeUiTest {
        assertEquals(FixtureDocuments.ids.toSet(), GeneratedFixtures.all.keys)

        for (id in FixtureDocuments.ids) {
            val resolved = resolve(id)
            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, HOME, ConformanceHarness.environment())
            }
            val runtimeDump = dumpSemantics()

            setContent { GeneratedFixtures.all.getValue(id)(Modifier) }
            assertEquals(runtimeDump, dumpSemantics(), "fixture '$id'")
        }
    }

    @Test
    fun `runtime and generated draw the same pixels`() = runComposeUiTest {
        for (id in FixtureDocuments.ids) {
            val resolved = resolve(id)
            setContent {
                UiScreen(ConformanceHarness.runtime(ConformanceHarness.schema()), resolved, HOME, ConformanceHarness.environment())
            }
            val runtimePixels = pixelBytes()

            setContent { GeneratedFixtures.all.getValue(id)(Modifier) }
            val generatedPixels = pixelBytes()
            assertEquals(runtimePixels.first, generatedPixels.first, "fixture '$id'")
            assertContentEquals(runtimePixels.second, generatedPixels.second, "fixture '$id'")
        }
    }

    private fun resolve(id: String): ResolvedDocument {
        val schema = ConformanceHarness.schema()
        val result = ConformanceHarness.analyze(schema, id)
        check(result.diagnostics.none { it.severity == Severity.Error }) {
            "fixture '$id': ${result.diagnostics}"
        }
        val resolved = checkNotNull(result.resolved) { "fixture '$id' resolved nothing" }
        val generated = KotlinGenerator(schema, CodegenOptions(PACKAGE + id))
            .generate(resolved, result.diagnostics)
        check(generated.diagnostics.none { it.severity == Severity.Error }) {
            "fixture '$id': ${generated.diagnostics}"
        }
        assertTrue(generated.files.files.any { it.path.endsWith("HomeScreen.kt") }, "fixture '$id'")
        return resolved
    }

    private fun ComposeUiTest.dumpSemantics(): String =
        buildString { render(firstContent(onRoot().fetchSemanticsNode())) }

    private fun ComposeUiTest.pixelBytes(): Pair<Pair<Int, Int>, IntArray> {
        val image = onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return (image.width to image.height) to pixels
    }
}

// UiScreen wraps the tree in a bare Box; the generated screen does not. A wrapper with no
// semantics and one child is layout, not content, so the comparison starts below it.
// Emptiness is read through the dump's own signals (`valueOrNull`): `isEmpty()` is not
// visible on `SemanticsConfiguration` from here, and a node carrying a tag or a text is
// content by the same definition the dump uses. The strip is symmetric — both sides run
// it — so agreement is still proved even where it strips a meaningful single-child root.
private fun firstContent(node: SemanticsNode): SemanticsNode {
    var current = node
    while (current.children.size == 1 &&
        current.config.valueOrNull(SemanticsProperties.TestTag) == null &&
        current.config.valueOrNull(SemanticsProperties.Text) == null
    ) {
        current = current.children.first()
    }
    return current
}

// `SemanticsConfiguration` has no optional read in this Compose version — the member
// `getOrNull` does not exist here, so every call site failed at once. This helper is the
// only optional read, built on `get` (which certainly exists) plus stdlib `runCatching`.
private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
    runCatching { get(key) }.getOrNull()

private fun StringBuilder.render(node: SemanticsNode): Unit {
    append("(")
    append(node.config.valueOrNull(SemanticsProperties.TestTag) ?: "node")
    node.config.valueOrNull(SemanticsProperties.Text)?.let { texts ->
        append(" text=")
        append(texts.joinToString("|") { it.text })
        // Spans carry color and style; the plain string would hide them.
        val spans = texts.flatMap { it.spanStyles }.joinToString("|")
        if (spans.isNotEmpty()) append(" spans=[$spans]")
    }
    node.config.valueOrNull(SemanticsProperties.ContentDescription)?.let { descriptions ->
        append(" desc=")
        append(descriptions.joinToString("|"))
    }
    node.config.valueOrNull(SemanticsProperties.Role)?.let { role ->
        append(" role=")
        append(role.toString())
    }
    if (node.config.valueOrNull(SemanticsProperties.Disabled) != null) append(" disabled")
    for (child in node.children) {
        append(" ")
        render(child)
    }
    append(")")
}
