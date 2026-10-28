package dev.rotalex.lutter.runtime

import androidx.compose.ui.Modifier
import dev.rotalex.lutter.analysis.resolved.ResolvedProp
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.component.ScopeId

/** A node's effective modifier arguments, in application order. */
public typealias ResolvedArgs = Map<PropertyKey, ResolvedProp>

/**
 * Typed reads over one modifier entry's arguments.
 *
 * The same kind table node properties read through, so an applier decodes a `dp` by handing
 * it the [PropertySpec] the document declared rather than by casting the value itself.
 *
 * No evaluation, and the signature says so: §15.3 folds arguments through the applier and an
 * applier is handed no scope, so a computed argument is refused by name. Threading one would mean
 * a parameter on [ModifierApplier.apply], which every registered applier would then need.
 */
public fun ResolvedArgs.reader(): PropertyReader = MapPropertyReader(this, null)

/**
 * What a modifier applier sees of the layout context: the node's scope set plus
 * the active scope handle, if a renderer opened one above it.
 *
 * The two are not redundant. [scopes] is what analysis proved and is available before
 * anything is composed; [handle] is the live receiver, and it is absent wherever no
 * renderer opened a scope.
 */
public class ScopeBag(
    public val scopes: Set<ScopeId>,
    public val handle: ScopeHandle?,
)

/**
 * One modifier's live form: folds the entry's arguments onto the modifier left to right.
 *
 * A scope-gated applier reads the receiver from [ScopeBag.handle] and returns the modifier
 * untouched when there is none.
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
