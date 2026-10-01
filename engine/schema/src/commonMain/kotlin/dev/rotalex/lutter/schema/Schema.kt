package dev.rotalex.lutter.schema

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.registry.Registry

/**
 * What consumers read: five registries, nothing mutable.
 *
 * Analysis, runtime and codegen take this rather than [Schema] so document components can
 * later resolve through an overlay without touching the static schema (§8.4, S4).
 */
public interface SchemaView<C : Any, M : Any, A : Any, F : Any, T : Any> {
    public val components: Registry<ComponentType, C>
    public val modifiers: Registry<ModifierType, M>
    public val actions: Registry<ActionId, A>
    public val functions: Registry<FunctionId, F>
    public val types: Registry<TypeId, T>
}

/**
 * The immutable schema: five registries built once through [SchemaBuilder].
 *
 * Generic over the spec types because they arrive in later units (S2–S4); binding them
 * here would drag those units into this one. The keys and the read-only shape are the
 * contract and do not move when the values do.
 */
public class Schema<C : Any, M : Any, A : Any, F : Any, T : Any> internal constructor(
    override public val components: Registry<ComponentType, C>,
    override public val modifiers: Registry<ModifierType, M>,
    override public val actions: Registry<ActionId, A>,
    override public val functions: Registry<FunctionId, F>,
    override public val types: Registry<TypeId, T>,
) : SchemaView<C, M, A, F, T> {
    public companion object {
        /** Builds a schema. Duplicates fail as [SchemaBuildException], never silently. */
        public fun <C : Any, M : Any, A : Any, F : Any, T : Any> build(
            block: SchemaBuilder<C, M, A, F, T>.() -> Unit,
        ): Schema<C, M, A, F, T> = SchemaBuilder<C, M, A, F, T>().apply(block).build()
    }
}
