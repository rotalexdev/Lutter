package dev.rotalex.lutter.runtime

import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.model.ids.ComponentType

/**
 * One component's live form. Reads only resolved nodes, never raw documents.
 *
 * Registry-held, so a new component needs no engine change: G2's whole point.
 */
public interface ComponentRenderer {
    @Composable
    public fun Render(node: ResolvedNode, scope: RenderScope)
}

/**
 * The live half of the plugin pair: component type to renderer.
 *
 * Built once, then immutable. A miss returns null and renders nothing; the
 * coverage check in [UiRuntime] is what turns a missing renderer into a failure.
 */
public class RendererRegistry internal constructor(
    private val entries: Map<ComponentType, ComponentRenderer>,
) {
    /** The renderer for [type], or null. Never throws; use [require] to fail. */
    public operator fun get(type: ComponentType): ComponentRenderer? = entries[type]

    /** The renderer for [type]. Throws [NoSuchElementException] naming it. */
    public fun require(type: ComponentType): ComponentRenderer =
        entries[type] ?: throw NoSuchElementException("No renderer for '$type'")

    /** Whether [type] is registered. Total; never throws. */
    public operator fun contains(type: ComponentType): Boolean = entries.containsKey(type)

    /** Every registered type, sorted. The coverage report reads this, nothing else. */
    public fun types(): List<ComponentType> = entries.keys.sortedBy { it.value }
}

/**
 * Assembles a [RendererRegistry]. Duplicates fail, like the schema builder's.
 */
public class RendererRegistryBuilder {
    private val entries: MutableMap<ComponentType, ComponentRenderer> = mutableMapOf()

    /** Registers [renderer] under [type]. A second registration for one type fails. */
    public fun register(type: ComponentType, renderer: ComponentRenderer): Unit {
        require(!entries.containsKey(type)) { "Duplicate renderer for '$type'" }
        entries[type] = renderer
    }

    /** Freezes the registry. Later registrations change nothing already built. */
    public fun build(): RendererRegistry = RendererRegistry(entries.toMap())
}
