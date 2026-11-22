package dev.rotalex.lutter.schema

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.plugin.PluginDescriptor
import dev.rotalex.lutter.schema.plugin.SchemaContribution
import dev.rotalex.lutter.schema.registry.DuplicateKeyException
import dev.rotalex.lutter.schema.registry.RegistryBuilder
import dev.rotalex.lutter.schema.types.IconSet

/**
 * A schema build failure: a duplicate key naming its registry, or a plugin [SchemaBuilder.install]
 * refused naming the reason. Both are configuration mistakes at build time, which is why they are
 * this one type rather than a result the caller is free to ignore.
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
    private val icons: RegistryBuilder<String, IconSet> = RegistryBuilder()
    private val contributors: MutableList<PluginDescriptor> = mutableListOf()

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

    /** Registers an icon set under its own name. §20:1599's TypeRegistry hook, now wired. */
    public fun iconSet(set: IconSet): Unit {
        add("icons", icons, set.name, set)
    }

    /**
     * Installs a plugin's schema facet (§8.2, §27.2): contributes its specs, then records its
     * descriptor.
     *
     * The two refusals are the ones a load order can create, and both happen **before**
     * [SchemaContribution.contribute] runs, so a refused plugin contributes nothing:
     *
     * - the same `id` installed twice — two payloads claiming one identity is a configuration
     *   mistake, and picking a winner would make the schema depend on link order;
     * - a `dependsOn` requirement no earlier install satisfies, or one satisfied by a different
     *   version. Versions compare by exact string equality, as ADR-018 records: there is no range
     *   grammar to implement or to get subtly wrong.
     *
     * Order is the caller's order and is preserved in [SchemaView.contributors], so a plugin set is
     * reproduced by installing it in the same sequence. A contribution that throws from
     * `contribute()` — a duplicate registry key, say — propagates and leaves the builder unusable
     * for the rest of the build: the same rule [component] already has, not a new one.
     */
    public fun install(contribution: SchemaContribution<C, M, A, F, T>): Unit {
        val descriptor: PluginDescriptor = contribution.descriptor
        val sameId: PluginDescriptor? = contributors.firstOrNull { it.id == descriptor.id }
        if (sameId != null) {
            throw SchemaBuildException(
                "Plugin '${descriptor.id}' is installed twice: version '${sameId.version}' is " +
                    "already installed and version '${descriptor.version}' is being installed",
            )
        }
        for (requirement in descriptor.dependsOn) {
            val satisfiedBy: PluginDescriptor? = contributors.firstOrNull { it.id == requirement.id }
            when {
                satisfiedBy == null -> throw SchemaBuildException(
                    "Plugin '${descriptor.id}' requires '${requirement.id}' " +
                        "'${requirement.version}', which is not installed before it",
                )

                satisfiedBy.version != requirement.version -> throw SchemaBuildException(
                    "Plugin '${descriptor.id}' requires '${requirement.id}' " +
                        "'${requirement.version}', but '${satisfiedBy.version}' is installed",
                )
            }
        }
        contribution.contribute(this)
        contributors += descriptor
    }

    /** Freezes the schema. Infallible: duplicates already threw at registration. */
    public fun build(): Schema<C, M, A, F, T> = Schema(
        components = components.build(),
        modifiers = modifiers.build(),
        actions = actions.build(),
        functions = functions.build(),
        types = types.build(),
        icons = icons.build(),
        contributors = contributors.toList(),
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
