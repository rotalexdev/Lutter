package dev.rotalex.lutter.schema.plugin

import dev.rotalex.lutter.model.doc.PluginRequirement
import dev.rotalex.lutter.model.ids.PluginId
import dev.rotalex.lutter.schema.SchemaBuilder

/**
 * A plugin: its identity, its version, and what it was built against (§27.1).
 *
 * A plain class, as the plan spells it: descriptors are filed, never compared.
 * There is no `VersionRange` because the plan defines none — no section gives a range
 * grammar, and the comparison behind `plugin.version_mismatch` belongs to the loader.
 */
public class PluginDescriptor(
    public val id: PluginId,
    public val version: String,
    public val apiVersion: Int,
    public val dependsOn: List<PluginRequirement> = emptyList(),
)

/**
 * The schema facet of a plugin: what it contributes to a [SchemaBuilder] (§8.2, §27.1).
 *
 * Builtins are plugins through this interface, which is what makes a test-only component
 * pass validation without touching engine modules. Generic over the five registries, as
 * the builder is: a facet contributes to whichever registries it owns.
 */
public interface SchemaContribution<C : Any, M : Any, A : Any, F : Any, T : Any> {
    /** Which plugin contributes, and what it needs (§27.2 checks this at load). */
    public val descriptor: PluginDescriptor

    /** Registers this plugin's specs. Duplicates fail in the builder, never silently. */
    public fun contribute(builder: SchemaBuilder<C, M, A, F, T>): Unit
}
