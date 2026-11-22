package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.schema.plugin.PluginDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §27.2 against a document's `meta.plugins` (S5, S7): a requirement the schema cannot honour is an
 * error naming the plugin, and versions compare by exact string equality.
 */
class PluginRequirementTest {

    private fun descriptor(id: String, version: String): PluginDescriptor =
        PluginDescriptor(id = PluginId(id), version = version, apiVersion = 1)

    private fun analyzing(vararg installed: PluginDescriptor): Analyzer<String, String, String> =
        Analyzer(pluginSchema(*installed))

    /** The walking-skeleton document, with the requirements the test is about. */
    private fun homeDocumentRequiring(vararg requirements: PluginRequirement): UiDocument {
        val document = validDocument()
        return document.copy(meta = document.meta.copy(plugins = requirements.toList()))
    }

    private fun requirement(id: String, version: String): PluginRequirement =
        PluginRequirement(PluginId(id), version)

    @Test
    fun `a required plugin this schema does not provide is a missing plugin`() {
        val result = analyzing().analyze(homeDocumentRequiring(requirement("forge.badge", "1.0.0")))

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.PluginMissing }
        assertTrue(finding.message.contains("forge.badge"), finding.message)
        assertTrue(finding.message.contains("1.0.0"), finding.message)
        assertEquals(null, result.resolved, "a document naming a plugin nowhere will not be lowered")
    }

    @Test
    fun `a required plugin installed at another version is a version mismatch naming both`() {
        val result = analyzing(descriptor("forge.badge", "1.0.0"))
            .analyze(homeDocumentRequiring(requirement("forge.badge", "2.0.0")))

        val finding = result.diagnostics.single { it.code == DiagnosticCodes.PluginVersionMismatch }
        assertTrue(finding.message.contains("2.0.0"), finding.message)
        assertTrue(finding.message.contains("1.0.0"), finding.message)
    }

    /** ADR-018: equality, and only equality. Anything else — including a newer build — is refused. */
    @Test
    fun `the required version must match exactly`() {
        val result = analyzing(descriptor("forge.badge", "1.0.0"))
            .analyze(homeDocumentRequiring(requirement("forge.badge", "1.0")))

        assertTrue(result.diagnostics.any { it.code == DiagnosticCodes.PluginVersionMismatch })
    }

    @Test
    fun `a satisfied requirement reports nothing`() {
        val result = analyzing(descriptor("forge.badge", "1.0.0"))
            .analyze(homeDocumentRequiring(requirement("forge.badge", "1.0.0")))

        assertTrue(result.diagnostics.isEmpty(), "got ${result.diagnostics.map { it.code.value }}")
        assertTrue(result.resolved != null, "a clean document lowers")
    }

    @Test
    fun `a document with no requirements reports nothing about plugins`() {
        val result = analyzing().analyze(validDocument())

        val codes = result.diagnostics.map { it.code.value }
        // One line per code: the catalog census reads test sources line by line, and a negative
        // spelling only counts on the line that names the code.
        assertTrue(result.diagnostics.none { it.code == DiagnosticCodes.PluginMissing }, "got $codes")
        assertTrue(result.diagnostics.none { it.code == DiagnosticCodes.PluginVersionMismatch }, "got $codes")
    }

    @Test
    fun `every unsatisfied requirement is reported, not only the first`() {
        val result = analyzing(descriptor("forge.badge", "1.0.0")).analyze(
            homeDocumentRequiring(
                requirement("forge.badge", "2.0.0"),
                requirement("forge.absent", "3.0.0"),
            ),
        )

        assertEquals(
            listOf(DiagnosticCodes.PluginMissing, DiagnosticCodes.PluginVersionMismatch),
            result.diagnostics.map { it.code }.sortedBy { it.value },
        )
    }
}
