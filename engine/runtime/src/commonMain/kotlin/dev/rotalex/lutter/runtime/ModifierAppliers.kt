package dev.rotalex.lutter.runtime

import androidx.compose.ui.Modifier
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.component.ScopeId

/** A node's effective modifier arguments, in application order. */
public typealias ResolvedArgs = Map<PropertyKey, ResolvedProp>

/**
 * What a modifier applier sees of the layout context: the node's scope set plus
 * the active scope handle, if a renderer opened one above it.
 */
public class ScopeBag(
    public val scopes: Set<ScopeId>,
    public val handle: ScopeHandle?,
)

/**
 * One modifier's live form: folds [args] onto [modifier] left to right.
 *
 * Scope-gated modifiers read [scopes] for the receiver the analyzer proved present.
 */
public interface ModifierApplier {
    public fun apply(modifier: Modifier, args: ResolvedArgs, scopes: ScopeBag): Modifier
}

/**
 * The live half of the modifier pair: modifier type to applier.
 *
 * Same shape as [RendererRegistry]: built once, misses return null, never throw.
 */
public class ModifierApplierRegistry internal constructor(
    private val entries: Map<ModifierType, ModifierApplier>,
) {
    /** The applier for [type], or null. Never throws. */
    public operator fun get(type: ModifierType): ModifierApplier? = entries[type]

    /** Whether [type] is registered. Total; never throws. */
    public operator fun contains(type: ModifierType): Boolean = entries.containsKey(type)
}

/**
 * Assembles a [ModifierApplierRegistry]. Duplicates fail, like the renderers'.
 */
public class ModifierApplierRegistryBuilder {
    private val entries: MutableMap<ModifierType, ModifierApplier> = mutableMapOf()

    /** Registers [applier] under [type]. A second registration for one type fails. */
    public fun register(type: ModifierType, applier: ModifierApplier): Unit {
        require(!entries.containsKey(type)) { "Duplicate modifier applier for '$type'" }
        entries[type] = applier
    }

    /** Freezes the registry. Later registrations change nothing already built. */
    public fun build(): ModifierApplierRegistry = ModifierApplierRegistry(entries.toMap())
}
