package dev.rotalex.lutter.schema.component

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import kotlin.jvm.JvmInline

/**
 * One named child area of a component: how many children, which ones, and what scopes exist.
 *
 * PLAN §7.1 field for field. A document slot (`SlotDecl`) carries only the name; the other
 * four are synthesis output when the S4 overlay turns a declaration into a spec.
 */
public class SlotSpec(
    public val name: SlotName,
    public val cardinality: Cardinality,
    public val accepts: ChildFilter = ChildFilter.Any,
    public val provides: Set<ScopeId> = emptySet(),
    public val iteration: IterationSpec? = null,
)

/** How many children a slot holds. */
public enum class Cardinality {
    ExactlyOne,
    ZeroOrOne,
    Many,
}

/** Which component types a slot admits. Sets, for the same reason as [PropertyRule]. */
public sealed interface ChildFilter {
    public data object Any : ChildFilter
    public data class Only(public val types: Set<ComponentType>) : ChildFilter
    public data class Except(public val types: Set<ComponentType>) : ChildFilter
}

/**
 * A layout scope such as `compose.RowScope`. A value class, not a model id: §7.3's scopes
 * are codegen receivers, not document vocabulary, so they live with the specs that provide
 * and require them.
 */
@JvmInline
public value class ScopeId(public val value: String) {
    init {
        require(value.isNotBlank()) { "Invalid ScopeId: '$value'" }
    }

    override fun toString(): String = value
}

/**
 * The item template of an iterating slot (`LazyColumn`): the loop variable, its type, and the
 * list that drives it.
 *
 * [source] is the node property the slot iterates; the generator reads its computed expression
 * as the `items(...)` argument the renderer evaluates per frame. The type is the element type
 * that list carries — [LazyColumnSpec]'s string list is the wave's own shape.
 */
public data class IterationSpec(
    public val itemName: String,
    public val itemType: TypeRef,
    public val source: PropertyKey,
)
