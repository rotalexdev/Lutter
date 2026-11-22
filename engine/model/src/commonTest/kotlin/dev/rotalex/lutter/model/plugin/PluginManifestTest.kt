package dev.rotalex.lutter.model.plugin

import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.ids.PluginId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manifest's pure half (ADR-018): what a container must contain, and what makes it unloadable.
 *
 * The build ↔ payload rule is checked here rather than in the reader because it is a rule about a
 * manifest and a set of payload kinds — passing the set in is what lets it be tested without a ZIP.
 */
class PluginManifestTest {

    private val material3: PluginManifest = PluginManifest(
        id = PluginId("forge.material3"),
        version = "1.4.0",
        apiVersion = PluginApiVersion.Supported,
        build = PluginBuild.Full,
        dependsOn = listOf(PluginRequirement(PluginId("forge.builtins"), "1.0")),
        entryPoints = PluginEntryPoints(
            schema = "forge.material3.Material3Schema",
            runtime = "forge.material3.Material3Runtime#install",
        ),
    )

    @Test
    fun `a manifest round-trips through json with the wire names the format fixes`() {
        val text: String = Json.encodeToString(material3)

        // The three build names are part of the format: a `PluginBuild` entry renamed in Kotlin
        // must not silently rename the wire value a container already on disk carries.
        assertTrue("\"full\"" in text, text)
        assertEquals(material3, Json.decodeFromString<PluginManifest>(text))
    }

    @Test
    fun `the three builds require the payloads they declare`() {
        assertEquals(
            setOf(PluginPayload.JvmJar, PluginPayload.AndroidDex),
            PluginBuild.Full.requiredPayloads(),
        )
        assertEquals(setOf(PluginPayload.AndroidDex), PluginBuild.AndroidOnly.requiredPayloads())
        assertEquals(setOf(PluginPayload.JvmJar), PluginBuild.DesktopOnly.requiredPayloads())
    }

    @Test
    fun `a build whose payloads the container does not contain is a named problem`() {
        assertEquals(
            listOf(PluginManifestProblem.PayloadMissing),
            material3.problems(setOf(PluginPayload.JvmJar)),
        )
        assertEquals(
            emptyList(),
            material3.problems(setOf(PluginPayload.JvmJar, PluginPayload.AndroidDex)),
        )
    }

    @Test
    fun `an api version the engine does not implement is refused naming the code`() {
        val future = material3.copy(apiVersion = PluginApiVersion.Supported + 1)

        assertEquals(
            listOf(PluginManifestProblem.ApiVersionMismatch),
            future.problems(setOf(PluginPayload.JvmJar, PluginPayload.AndroidDex)),
        )
        assertEquals("plugin.api_version_mismatch", PluginManifestProblem.ApiVersionMismatch.code)
        assertEquals("plugin.payload_missing", PluginManifestProblem.PayloadMissing.code)
        assertEquals("plugin.manifest_invalid", PluginManifestProblem.EntryPointMissing.code)
    }

    @Test
    fun `a manifest that names nothing to load is a problem even when its payloads are there`() {
        val empty = material3.copy(entryPoints = PluginEntryPoints())

        assertEquals(
            listOf(PluginManifestProblem.EntryPointMissing),
            empty.problems(setOf(PluginPayload.JvmJar, PluginPayload.AndroidDex)),
        )
    }

    /**
     * Every problem is reportable on its own: a container can be wrong in more than one way, and a
     * host that stopped at the first would make the second a discovery for the next run.
     */
    @Test
    fun `problems accumulate rather than the first one hiding the rest`() {
        val wrong = material3.copy(apiVersion = 0, entryPoints = PluginEntryPoints())

        assertEquals(
            listOf(
                PluginManifestProblem.ApiVersionMismatch,
                PluginManifestProblem.PayloadMissing,
                PluginManifestProblem.EntryPointMissing,
            ),
            wrong.problems(emptySet()),
        )
    }

    @Test
    fun `the container layout names one set of paths`() {
        assertEquals("META-INF/lutter/plugin.json", PluginContainerLayout.ManifestPath)
        assertEquals("classes/jvm/", PluginContainerLayout.JvmPayloadPrefix)
        assertEquals("classes/android/", PluginContainerLayout.AndroidPayloadPrefix)
        assertEquals(1, PluginApiVersion.Supported)
    }
}
