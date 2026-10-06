package dev.rotalex.lutter.schema.action

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec

/**
 * An action step's static contract: identity, params, control-flow arms and emission.
 *
 * PLAN §11.3 field for field, plus [argRules] for the arguments §11.3 could not type. A plain
 * class, as the plan spells it: specs are filed by key and never compared structurally, so
 * data-class equality would promise what nothing uses.
 */
public class ActionSpec(
    public val id: ActionId,
    public val metadata: ActionMetadata,
    public val params: List<PropertySpec<*>>,

    /**
     * Arguments [params] cannot type, as shapes rather than types.
     *
     * Separate from [params] because the two answer different questions: a [PropertySpec] says
     * what a value *is*, and these say what the step does with it. `state.set`'s `target` names
     * a state and its `value` is typed by whatever that state declares, so neither is a type
     * this spec could carry.
     */
    public val argRules: List<ArgRule> = emptyList(),
    public val branches: List<BranchSpec> = emptyList(),
    public val emit: ActionEmit,
)

/**
 * Editor-facing facts about an action: naming and introduction version.
 *
 * Display data only; execution and codegen never read it.
 */
public data class ActionMetadata(
    public val displayName: String,
    public val description: String = "",
    public val since: Int = 1,
)

/**
 * One named control-flow arm an action accepts (`then`/`else` on `flow.if`).
 *
 * Names only: the arm bodies are document data (`ActionStep.branches`), so the spec says
 * which arms exist and whether the step is incomplete without them.
 */
public data class BranchSpec(
    public val name: BranchName,
    public val required: Boolean = false,
    public val doc: String = "",
)

/**
 * How an action becomes Kotlin: engine-owned, or a plugin template.
 *
 * Intrinsic covers state, navigation, flow, dialogs and host calls (§11.4): both sides stay
 * in sync by construction because the engine writes both. Anything else ships a pattern.
 */
public sealed interface ActionEmit {
    public data object Intrinsic : ActionEmit

    /** Plugin-owned emission: fill [pattern]'s `{key}` placeholders, adding [imports]. */
    public data class Template(
        public val pattern: String,
        public val imports: List<KotlinSymbol> = emptyList(),
    ) : ActionEmit
}

/**
 * The S1 joint, bound: S1 left [Schema] generic over five value types because these specs
 * did not exist yet. The action registry is now `Registry<ActionId, ActionSpec>`.
 */
public typealias ActionSchemaView<C, M, F, T> =
    SchemaView<C, M, ActionSpec, F, T>

/** A [Schema] whose action registry holds [ActionSpec]. The other units bind separately. */
public typealias ActionSchema<C, M, F, T> =
    Schema<C, M, ActionSpec, F, T>

/**
 * Registers an already-built spec under its own id. An overload, not a member: S1's
 * generic mechanics stay untouched and its stub-typed tests keep compiling.
 */
public fun <C : Any, M : Any, F : Any, T : Any> SchemaBuilder<C, M, ActionSpec, F, T>.action(
    spec: ActionSpec,
): Unit = action(spec.id, spec)
