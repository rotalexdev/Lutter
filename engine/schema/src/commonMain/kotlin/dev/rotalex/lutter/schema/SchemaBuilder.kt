package dev.rotalex.lutter.schema

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.registry.DuplicateKeyException
import dev.rotalex.lutter.schema.registry.RegistryBuilder

/**
 * A schema build failure: always a duplicate key, always naming the registry and the key.
 * Wraps the [DuplicateKeyException] that found it.
 */
public class SchemaBuildException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Assembles a [Schema]. One method per registry; duplicates throw immediately.
 *
 * Immediate rather than at build(): the failure names the registry being added to, which
 * a deferred check could only guess at.
 */
public class SchemaBuilder<C : Any, M : Any, A : Any, F : Any, T : Any> {
    private val components: RegistryBuilder<ComponentType, C> = RegistryBuilder()
    private val modifiers: RegistryBuilder<ModifierType, M> = RegistryBuilder()
    private val actions: RegistryBuilder<ActionId, A> = RegistryBuilder()
    private val functions: RegistryBuilder<FunctionId, F> = RegistryBuilder()
    private val types: RegistryBuilder<TypeId, T> = RegistryBuilder()

    /** Registers a component spec. Only the key and value; S2 adds the spec-typed overload. */
    public fun component(type: ComponentType, spec: C): Unit {
        add("components", components, type, spec)
    }

    /** Registers a modifier spec. See [component] for why the signature stays generic. */
    public fun modifier(type: ModifierType, spec: M): Unit {
        add("modifiers", modifiers, type, spec)
    }

    /** Registers an action spec. See [component] for why the signature stays generic. */
    public fun action(id: ActionId, spec: A): Unit {
        add("actions", actions, id, spec)
    }

    /** Registers a function spec. See [component] for why the signature stays generic. */
    public fun function(id: FunctionId, spec: F): Unit {
        add("functions", functions, id, spec)
    }

    /** Registers a type entry. S4 replaces this with the real `TypeRegistry` shape. */
    public fun type(id: TypeId, entry: T): Unit {
        add("types", types, id, entry)
    }

    /** Freezes the schema. Infallible: duplicates already threw at registration. */
    public fun build(): Schema<C, M, A, F, T> = Schema(
        components = components.build(),
        modifiers = modifiers.build(),
        actions = actions.build(),
        functions = functions.build(),
        types = types.build(),
    )

    private fun <K : Any, V : Any> add(
        registryName: String,
        builder: RegistryBuilder<K, V>,
        key: K,
        value: V,
    ): Unit {
        try {
            builder.register(key, value)
        } catch (e: DuplicateKeyException) {
            throw SchemaBuildException("Duplicate key '$key' in $registryName registry", e)
        }
    }
}
