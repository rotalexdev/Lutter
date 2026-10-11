package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.component.ComponentSpec

/**
 * The components the engine emits itself, and what each one becomes.
 *
 * A table keyed by type rather than a branch over it, for exactly the reason
 * [IntrinsicActions] spells for actions: the specs declaring them live in `:engine:builtins`,
 * which `ModuleGraphRules.ALLOWED` does not let this module depend on, so the type is the only
 * thing the spec and this side share. `core.SlotOutlet` is the whole list today.
 *
 * A `doc.*` instance is deliberately **not** here. It is not an enumeration at all: the
 * `doc.` namespace says what it is, so the generator reads the declaration id off the type and
 * looks the spec up in §8.4's synthesized set. Putting it in this table would be the one entry a
 * document author could not extend without editing this file.
 */
internal object IntrinsicNodes {

    /**
     * `SlotOutletSpec.slot`, the property that names the slot an outlet draws.
     *
     * The same key spelled out rather than imported, for the reason above: `PropertyKey` validates
     * itself, so a typo here is a load-time failure naming this property, and the renderer in
     * `:engine:builtins-compose` reads its own handle for the same key.
     */
    val slotName: PropertyKey = PropertyKey("slot")

    private val table: Map<ComponentType, IntrinsicNode> = mapOf(
        ComponentType("core.SlotOutlet") to IntrinsicNode.SlotOutlet,
    )

    /** What [spec] becomes, or null when the engine has no emitter for it. */
    fun kindOf(spec: ComponentSpec): IntrinsicNode? = table[spec.type]
}

/** What one intrinsic component becomes, once its type has been looked up. */
internal enum class IntrinsicNode {
    SlotOutlet,
}