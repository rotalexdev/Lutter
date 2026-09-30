package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.SchemaView

/**
 * A component's static contract: identity, editor metadata, typed surface and codegen.
 *
 * PLAN §7.1 field for field. A plain class, as the plan spells it: specs are filed by key
 * and never compared structurally, so data-class equality would promise what nothing uses.
 */
public class ComponentSpec(
    public val type: ComponentType,
    public val version: Int,
    public val metadata: ComponentMetadata,
    public val availability: Set<PlatformTag> = PlatformTag.ALL,
    public val properties: List<PropertySpec<*>>,
    public val slots: List<SlotSpec>,
    public val events: List<EventSpec>,
    public val modifiers: ModifierPolicy = ModifierPolicy.All,
    public val rules: List<PropertyRule> = emptyList(),
    public val codegen: CodegenBinding,
    public val origin: SpecOrigin = SpecOrigin.Static,
)

/**
 * Editor-facing facts about a component: naming, grouping, search and introduction version.
 *
 * Display data only; validation and codegen never read it.
 */
public data class ComponentMetadata(
    public val displayName: String,
    public val category: Category,
    public val description: String = "",
    public val keywords: List<String> = emptyList(),
    public val icon: String? = null,
    public val since: Int = 1,
)

/**
 * Editor grouping for a component. Three buckets cover the MVP waves; later waves widen it.
 */
public enum class Category {
    Basic,
    Layout,
    Input,
}

/**
 * Where a component ships. A set, because most components ship everywhere.
 */
public enum class PlatformTag {
    Android,
    Ios,
    Desktop,
    Web;

    public companion object {
        /** Every platform: the default, so a portable component says nothing. */
        public val ALL: Set<PlatformTag> = enumValues<PlatformTag>().toSet()
    }
}

/**
 * Where a spec came from: compiled in, or synthesized from a document declaration.
 *
 * The second arm is S4's overlay; it is declared here so the field's type is complete now.
 */
public sealed interface SpecOrigin {
    public data object Static : SpecOrigin
    public data class Document(public val id: ComponentDeclId) : SpecOrigin
}

/**
 * Which modifiers a component accepts. Mirrors [ChildFilter]: layout takes all, leaf takes none.
 */
public sealed interface ModifierPolicy {
    public data object All : ModifierPolicy
    public data object None : ModifierPolicy
    public data class Only(public val types: Set<ModifierType>) : ModifierPolicy
    public data class Except(public val types: Set<ModifierType>) : ModifierPolicy
}

/**
 * The S1 joint, bound: S1 left [Schema] generic over five value types because these specs
 * did not exist yet. The component registry is now `Registry<ComponentType, ComponentSpec>`.
 */
public typealias ComponentSchemaView<M : Any, A : Any, F : Any, T : Any> =
    SchemaView<ComponentSpec, M, A, F, T>

/** A [Schema] whose component registry holds [ComponentSpec]. The other four units bind later. */
public typealias ComponentSchema<M : Any, A : Any, F : Any, T : Any> =
    Schema<ComponentSpec, M, A, F, T>

/**
 * Registers an already-built spec under its own type. An overload, not a member: S1's
 * generic mechanics stay untouched and its stub-typed tests keep compiling.
 */
public fun <M : Any, A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, M, A, F, T>.component(
    spec: ComponentSpec,
): Unit = component(spec.type, spec)
