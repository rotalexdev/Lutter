@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

// The opt-in above is for exactly one thing: `@JsonClassDiscriminator`, still experimental,
// which pins the wire key to `type` rather than leaving it to the library default. File scope,
// because the annotation must precede the package declaration to compile at all.

package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * How a component becomes Kotlin: a direct call, an escape hatch, or engine-owned.
 *
 * PLAN §7.2 record for record. Serializable, unlike the spec records: bindings travel with
 * synthesized specs and fixtures, so their tags are contract. Every variant spells its own.
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface CodegenBinding {
    /**
     * A call into `function`, wiring properties, events and slots to its parameters.
     */
    @Serializable
    @SerialName("composeCall")
    public data class ComposeCall(
        public val function: KotlinSymbol,
        public val modifierParam: String? = "modifier",
        public val params: List<ParamBinding> = emptyList(),
        public val events: List<EventBinding> = emptyList(),
        public val slots: List<SlotBinding> = emptyList(),
    ) : CodegenBinding

    /** Plugin-owned emission, looked up in `CodegenExtensions` by [emitterId]. */
    @Serializable
    @SerialName("custom")
    public data class Custom(public val emitterId: EmitterId) : CodegenBinding

    /** Handled by the generator itself (`SlotOutlet`, document instances). No payload. */
    @Serializable
    @SerialName("intrinsic")
    public data object Intrinsic : CodegenBinding
}

/**
 * One parameter of the call: which properties feed it, how the value emits, and whether a
 * lone property shortens to a positional argument (`Text("Continue")`).
 */
@Serializable
public data class ParamBinding(
    public val param: String,
    public val from: List<PropertyKey>,
    public val emit: ValueEmit = ValueEmit.Direct,
    public val positional: Positional = Positional.Never,
    public val omitWhenDefault: Boolean = true,
)

/**
 * How a parameter's value emits. Direct delegates to the value kind; cases pick a template
 * by which properties are present (§7.2's arrangement-vs-spacing shape, D7).
 */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface ValueEmit {
    @Serializable
    @SerialName("direct")
    public data object Direct : ValueEmit

    @Serializable
    @SerialName("cases")
    public data class Cases(public val cases: List<EmitCase>) : ValueEmit
}

/**
 * One emission case: when [whenPresent] holds, fill [pattern]'s `{key}` placeholders.
 */
@Serializable
public data class EmitCase(
    public val whenPresent: Set<PropertyKey>,
    public val pattern: String,
    public val imports: List<KotlinSymbol> = emptyList(),
)

/** Where a slot's content goes: trailing lambda, or a named parameter, with an optional receiver. */
@Serializable
public data class SlotBinding(
    public val slot: SlotName,
    public val target: LambdaTarget,
    public val receiver: KotlinSymbol? = null,
)

/** Which parameter an event handler becomes, and the lambda's own parameter names. */
@Serializable
public data class EventBinding(
    public val event: EventKey,
    public val param: String,
    public val lambdaParams: List<String> = emptyList(),
)

/** Trailing lambda syntax, or an explicitly named content parameter. */
@Serializable
@JsonClassDiscriminator("type")
public sealed interface LambdaTarget {
    @Serializable
    @SerialName("trailing")
    public data object Trailing : LambdaTarget

    @Serializable
    @SerialName("namedParam")
    public data class NamedParam(public val name: String) : LambdaTarget
}

/** Shortens to positional only when it is the sole property set. */
@Serializable
public enum class Positional {
    @SerialName("never")
    Never,

    @SerialName("whenSole")
    WhenSole,
}

/** A Kotlin reference: its package and its name. Two strings, because that is the whole fact. */
@Serializable
public data class KotlinSymbol(
    public val packageName: String,
    public val name: String,
)

/**
 * Names a plugin-owned emitter. Declared here, not in `:engine:model`: §33.2 gives this file
 * the binding records, and no document carries an emitter id, so the model has no claim on it.
 */
@JvmInline
@Serializable
public value class EmitterId(public val value: String) {
    init {
        require(value.isNotBlank()) { "Invalid EmitterId: '$value'" }
    }

    override fun toString(): String = value
}
