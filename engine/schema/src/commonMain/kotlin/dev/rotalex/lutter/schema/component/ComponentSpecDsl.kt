package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value

/**
 * Declares one property outside the spec body, so the spec and its renderer share the handle.
 *
 * The §7.4 shape: `val text = prop<String>("text", TypeRef.Str, required = true)`.
 */
public fun <T> prop(
    key: String,
    type: TypeRef,
    default: Value? = null,
    required: Boolean = false,
    bindable: Boolean = true,
    editor: EditorHints = EditorHints.None,
    doc: String = "",
): PropertySpec<T> = PropertySpec(
    key = PropertyKey(key),
    type = type,
    default = default,
    required = required,
    bindable = bindable,
    editor = editor,
    doc = doc,
)

/**
 * Authors a [ComponentSpec]: metadata, surface, rules, then exactly one codegen binding.
 *
 * The §7.4 shape. Repeats and omissions fail at `build`, not at read time: a spec missing
 * its binding, or carrying two, is an author mistake no consumer should interpret.
 */
public fun componentSpec(
    type: ComponentType,
    version: Int,
    block: ComponentSpecBuilder.() -> Unit,
): ComponentSpec = ComponentSpecBuilder(type, version).apply(block).build()

/** The receiver of [componentSpec]. One method per spec part; duplicates fail. */
public class ComponentSpecBuilder internal constructor(
    private val type: ComponentType,
    private val version: Int,
) {
    private var metadata: ComponentMetadata? = null
    private var availability: Set<PlatformTag> = PlatformTag.ALL
    private var modifiers: ModifierPolicy = ModifierPolicy.All
    private var codegen: CodegenBinding? = null
    private val properties: MutableList<PropertySpec<*>> = mutableListOf()
    private val slots: MutableList<SlotSpec> = mutableListOf()
    private val events: MutableList<EventSpec> = mutableListOf()
    private val rules: MutableList<PropertyRule> = mutableListOf()

    /** Display facts. Once: a spec has one name, and a second call is a copy-paste slip. */
    public fun metadata(
        displayName: String,
        category: Category,
        description: String = "",
        keywords: List<String> = emptyList(),
        icon: String? = null,
        since: Int = 1,
    ): Unit {
        check(this.metadata == null) { "Component '${type}' declares metadata twice" }
        this.metadata = ComponentMetadata(displayName, category, description, keywords, icon, since)
    }

    /** One declared property, shared with the renderer by handle. */
    public fun property(spec: PropertySpec<*>): Unit {
        properties += spec
    }

    /** One child area, either prebuilt or from parts. */
    public fun slot(spec: SlotSpec): Unit {
        slots += spec
    }

    public fun slot(
        name: String,
        cardinality: Cardinality,
        accepts: ChildFilter = ChildFilter.Any,
        provides: Set<ScopeId> = emptySet(),
        iteration: IterationSpec? = null,
    ): Unit = slot(SlotSpec(SlotName(name), cardinality, accepts, provides, iteration))

    /** One event, either prebuilt or from parts. */
    public fun event(spec: EventSpec): Unit {
        events += spec
    }

    public fun event(key: String, args: List<EventArgSpec> = emptyList()): Unit =
        event(EventSpec(EventKey(key), args))

    /** One declarative cross-property check. */
    public fun rule(rule: PropertyRule): Unit {
        rules += rule
    }

    /** Where the component ships. The default is everywhere; this narrows it. */
    public fun availability(tags: Set<PlatformTag>): Unit {
        availability = tags
    }

    /** Which modifiers the component takes. The default takes all. */
    public fun modifiers(policy: ModifierPolicy): Unit {
        modifiers = policy
    }

    /** A direct Compose call binding. Once, like [metadata]: two calls disagree by definition. */
    public fun composeCall(
        function: KotlinSymbol,
        modifierParam: String? = "modifier",
        block: ComposeCallBuilder.() -> Unit = {},
    ): Unit {
        check(codegen == null) { "Component '${type}' declares its codegen twice" }
        codegen = ComposeCallBuilder().apply(block).build(function, modifierParam)
    }

    /** A plugin-owned binding. Same single-binding rule as [composeCall]. */
    public fun custom(emitterId: EmitterId): Unit {
        check(codegen == null) { "Component '${type}' declares its codegen twice" }
        codegen = CodegenBinding.Custom(emitterId)
    }

    /** A generator-owned binding. Same single-binding rule as [composeCall]. */
    public fun intrinsic(): Unit {
        check(codegen == null) { "Component '${type}' declares its codegen twice" }
        codegen = CodegenBinding.Intrinsic
    }

    internal fun build(): ComponentSpec = ComponentSpec(
        type = type,
        version = version,
        metadata = checkNotNull(metadata) { "Component '${type}' has no metadata" },
        availability = availability,
        properties = properties.toList(),
        slots = slots.toList(),
        events = events.toList(),
        modifiers = modifiers,
        rules = rules.toList(),
        codegen = checkNotNull(codegen) { "Component '${type}' has no codegen binding" },
    )
}

/** The receiver of [ComponentSpecBuilder.composeCall]: one method per binding part. */
public class ComposeCallBuilder internal constructor() {
    private val params: MutableList<ParamBinding> = mutableListOf()
    private val events: MutableList<EventBinding> = mutableListOf()
    private val slots: MutableList<SlotBinding> = mutableListOf()

    /** One parameter fed by a single property handle. */
    public fun param(
        param: String,
        from: PropertySpec<*>,
        emit: ValueEmit = ValueEmit.Direct,
        positional: Positional = Positional.Never,
        omitWhenDefault: Boolean = true,
    ): Unit = param(param, listOf(from.key), emit, positional, omitWhenDefault)

    /** One parameter fed by several properties (D7: arrangement-or-spacing into one argument). */
    public fun param(
        param: String,
        from: List<PropertyKey>,
        emit: ValueEmit = ValueEmit.Direct,
        positional: Positional = Positional.Never,
        omitWhenDefault: Boolean = true,
    ): Unit {
        params += ParamBinding(param, from.toList(), emit, positional, omitWhenDefault)
    }

    /** One event handler parameter, keyed by id or taken from the event spec. */
    public fun event(event: EventKey, param: String, lambdaParams: List<String> = emptyList()): Unit {
        events += EventBinding(event, param, lambdaParams.toList())
    }

    public fun event(spec: EventSpec, param: String, lambdaParams: List<String> = emptyList()): Unit =
        event(spec.key, param, lambdaParams)

    /** One slot wiring, named by id or taken from the slot spec. */
    public fun slot(slot: String, target: LambdaTarget, receiver: KotlinSymbol? = null): Unit =
        slot(SlotName(slot), target, receiver)

    public fun slot(slot: SlotName, target: LambdaTarget, receiver: KotlinSymbol? = null): Unit {
        slots += SlotBinding(slot, target, receiver)
    }

    public fun slot(spec: SlotSpec, target: LambdaTarget, receiver: KotlinSymbol? = null): Unit =
        slot(spec.name, target, receiver)

    internal fun build(function: KotlinSymbol, modifierParam: String?): CodegenBinding.ComposeCall =
        CodegenBinding.ComposeCall(
            function = function,
            modifierParam = modifierParam,
            params = params.toList(),
            events = events.toList(),
            slots = slots.toList(),
        )
}
