package dev.rotalex.lutter.schema.modifier

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ScopeId

/**
 * A layout modifier's static contract: identity, params, scope gate and emission.
 *
 * PLAN §7.3 field for field. A plain class, as the plan spells it: specs are filed by key
 * and never compared structurally, so data-class equality would promise what nothing uses.
 */
public class ModifierSpec(
    public val type: ModifierType,
    public val metadata: ModifierMetadata,
    public val params: List<PropertySpec<*>>,
    public val requiresScope: Set<ScopeId> = emptySet(),
    public val emit: ModifierEmit,
)

/**
 * Editor-facing facts about a modifier: naming and introduction version.
 *
 * Display data only; validation and codegen never read it.
 */
public data class ModifierMetadata(
    public val displayName: String,
    public val description: String = "",
    public val since: Int = 1,
)

/**
 * How a modifier becomes Kotlin: the chained function plus per-shape cases.
 *
 * Cases reuse [EmitCase] (§7.2's shape): `padding` picks `padding({all})` or
 * `padding(horizontal = {horizontal}, vertical = {vertical})` by which params are present.
 * No cases means one unconditional call (`fillMaxSize()`).
 */
public data class ModifierEmit(
    public val function: KotlinSymbol,
    public val cases: List<EmitCase> = emptyList(),
)

/**
 * The S1 joint, bound: S1 left [Schema] generic over five value types because these specs
 * did not exist yet. The modifier registry is now `Registry<ModifierType, ModifierSpec>`.
 */
public typealias ModifierSchemaView<C, A, F, T> =
    SchemaView<C, ModifierSpec, A, F, T>

/** A [Schema] whose modifier registry holds [ModifierSpec]. The other units bind separately. */
public typealias ModifierSchema<C, A, F, T> =
    Schema<C, ModifierSpec, A, F, T>

/**
 * Registers an already-built spec under its own type. An overload, not a member: S1's
 * generic mechanics stay untouched and its stub-typed tests keep compiling.
 */
public fun <C : Any, A : Any, F : Any, T : Any> SchemaBuilder<C, ModifierSpec, A, F, T>.modifier(
    spec: ModifierSpec,
): Unit = modifier(spec.type, spec)
