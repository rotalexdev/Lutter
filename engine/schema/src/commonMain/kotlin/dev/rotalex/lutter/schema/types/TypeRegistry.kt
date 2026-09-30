package dev.rotalex.lutter.schema.types

import dev.rotalex.lutter.model.doc.FieldDecl
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder

/**
 * A data-defined type: the schema side of the `enums`/`dataModels` maps on `UiDocument`.
 *
 * One value space (§5.5:366): an enum and an object never share a `TypeId`, because a
 * single registry holds both and a duplicate key fails there rather than here.
 */
public sealed interface TypeSpec {
    public val id: TypeId
}

/**
 * A plugin-declared enum: its entries plus each entry's Kotlin symbol (§16.6:1301).
 *
 * The document's `EnumTypeDecl` carries bare names; the symbol lives here because only
 * the schema knows how an entry emits.
 */
public class EnumTypeSpec(
    override public val id: TypeId,
    public val entries: List<EnumEntrySpec>,
) : TypeSpec

/**
 * One enum entry: the name a document writes, and the symbol generated code uses.
 */
public data class EnumEntrySpec(
    public val name: String,
    public val kotlin: KotlinSymbol,
)

/**
 * A plugin-declared structured type: the schema side of the document's `DataModelDecl`.
 *
 * Fields reuse the model's `FieldDecl`, so a `Value.Obj` validates against the same
 * element type the document declares with.
 */
public class ObjectTypeSpec(
    override public val id: TypeId,
    public val fields: List<FieldDecl>,
) : TypeSpec

/**
 * A named icon set: icon names to their Kotlin symbols (§20:1560).
 *
 * Keyed by a bare name, not an id: no id type declares icon sets, and set names are
 * plugin vocabulary rather than document vocabulary.
 */
public class IconSet(
    public val name: String,
    public val icons: Map<String, KotlinSymbol>,
) {
    /** The symbol for [name], or null when this set has no such icon. */
    public operator fun get(name: String): KotlinSymbol? = icons[name]
}

/**
 * The data-defined types: enums and objects in one namespace, icon sets in another.
 *
 * Built once through [TypeRegistryBuilder]. The single types map is the one-value-space
 * enforcement Phase 1 left open: a `TypeId` in both maps is one duplicate key, not two.
 */
public class TypeRegistry(
    public val types: Registry<TypeId, TypeSpec>,
    public val icons: Registry<String, IconSet>,
) {
    /** The enum type for [id], or null when absent or an object type. */
    public fun enumType(id: TypeId): EnumTypeSpec? = types[id] as? EnumTypeSpec

    /** The object type for [id], or null when absent or an enum type. */
    public fun objectType(id: TypeId): ObjectTypeSpec? = types[id] as? ObjectTypeSpec

    /** The icon set for [name], or null when no plugin registered one. */
    public fun iconSet(name: String): IconSet? = icons[name]
}

/**
 * Builds one [TypeRegistry]. Duplicates fail in the underlying builders, never silently.
 */
public class TypeRegistryBuilder {
    private val types: RegistryBuilder<TypeId, TypeSpec> = RegistryBuilder()
    private val icons: RegistryBuilder<String, IconSet> = RegistryBuilder()

    /** Registers an enum or object type under its own id. */
    public fun register(spec: TypeSpec): Unit {
        types.register(spec.id, spec)
    }

    /** Registers an icon set under its own name. */
    public fun iconSet(set: IconSet): Unit {
        icons.register(set.name, set)
    }

    /** Freezes the registry. Later registrations change nothing already built. */
    public fun build(): TypeRegistry = TypeRegistry(types.build(), icons.build())
}

/**
 * The S1 joint, bound: S1 left [Schema] generic over five value types because these specs
 * did not exist yet. The types registry is now `Registry<TypeId, TypeSpec>`.
 */
public typealias TypeSchemaView<C, M, A, F> =
    SchemaView<C, M, A, F, TypeSpec>

/** A [Schema] whose types registry holds [TypeSpec]. The other four units bound earlier. */
public typealias TypeSchema<C, M, A, F> =
    Schema<C, M, A, F, TypeSpec>

/**
 * Registers an already-built spec under its own id. An overload, not a member: S1's
 * generic mechanics stay untouched and its stub-typed tests keep compiling.
 */
public fun <C : Any, M : Any, A : Any, F : Any> SchemaBuilder<C, M, A, F, TypeSpec>.type(
    spec: TypeSpec,
): Unit = type(spec.id, spec)
