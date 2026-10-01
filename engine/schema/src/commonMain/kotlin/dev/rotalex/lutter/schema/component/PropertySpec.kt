package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value

/**
 * One typed property of a component. `T` is phantom: renderer access types itself through it.
 *
 * A plain class, as PLAN §7.1 spells it. Bounds live in [PropertyRule], not here: §9.2 puts
 * length and range limits on the spec, and a type says what a value is rather than how much
 * of it one field accepts.
 */
public class PropertySpec<T>(
    public val key: PropertyKey,
    public val type: TypeRef,
    public val default: Value? = null,
    public val required: Boolean = false,
    public val bindable: Boolean = true,
    public val editor: EditorHints = EditorHints.None,
    public val doc: String = "",
)

/**
 * Which editor affordance a property gets. One variant today; the column it answers (§9.2)
 * grows with the editor, not with the engine.
 */
public sealed interface EditorHints {
    public data object None : EditorHints
}

/**
 * Declarative cross-property checks (§17.4): exclusivity, presence, numeric bounds, slot needs.
 *
 * Sets, not varargs: the keys are value classes, which Kotlin forbids as vararg elements.
 */
public sealed interface PropertyRule {
    /** At most one of [keys] may be present (§7.2's spacing-vs-arrangement case). */
    public data class MutuallyExclusive(public val keys: Set<PropertyKey>) : PropertyRule

    /** At least one of [keys] must be present. */
    public data class RequiresOneOf(public val keys: Set<PropertyKey>) : PropertyRule

    /** A numeric bound. Doubles, not values: a bound is author data, not a document literal. */
    public data class Range(
        public val key: PropertyKey,
        public val min: Double? = null,
        public val max: Double? = null,
    ) : PropertyRule

    /** Filling [slot] requires [keys] to be present. */
    public data class SlotRequires(public val slot: SlotName, public val keys: Set<PropertyKey>) :
        PropertyRule
}
