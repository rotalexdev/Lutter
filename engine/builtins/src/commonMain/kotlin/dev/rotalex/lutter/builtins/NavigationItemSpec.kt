package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.NavigationItem` spec, the navigation wave's leaf: a selection, a press and two slots.
 *
 * The state follows [RadioButtonSpec]'s `selected` idiom: a Bool prop read, required because an
 * item without a state renders nothing meaningful, and Material's nav press hands its handler no
 * argument (`onClick: () -> Unit`) — which entry is selected is the bar's decision, not the
 * press's — so the event carries no args, the same shape [ButtonSpec] binds, and the generator
 * emits `{}` for an item whose press the document left unbound. The two slots are declared in
 * argument order and both are receiverless: Compose's `icon` and `label` lambdas are
 * `() -> Unit`, so neither provides a scope and neither opens a handle — the icon is exactly
 * one, because an item without an icon is not a nav entry, and the label is optional, the one
 * thing a document may leave off. The item composes inside the row scope its bar provides —
 * `NavigationBarItem` is a `RowScope` extension, so a rail or drawer entry is not this leaf:
 * those compose `NavigationRailItem`/`NavigationDrawerItem`, which v1 does not bind, and an
 * item rendered outside a bar reports and draws nothing. `NavigationBarItem` is a stable M3
 * API, so there is no opt-in to spell.
 */
public object NavigationItemSpec {
    /** Whether this item is the one selected. Required: an item without a state renders nothing meaningful. */
    public val selected: PropertySpec<Boolean> = prop("selected", TypeRef.Bool, required = true)

    /** The press. No argument: the selected item is the container's decision, not the press's. */
    public val onClick: EventSpec = EventSpec(EventKey("onClick"))

    /** The item's icon, exactly one; the one slot a document may not leave off. */
    public val icon: SlotSpec = SlotSpec(SlotName("icon"), Cardinality.ExactlyOne)

    /** The item's label, optional; the one slot a document may leave off. */
    public val label: SlotSpec = SlotSpec(SlotName("label"), Cardinality.ZeroOrOne)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.NavigationItem"), version = 1) {
        metadata(displayName = "NavigationItem", category = Category.Basic)
        property(selected)
        event(onClick)
        slot(icon)
        slot(label)
        composeCall(KotlinSymbol("androidx.compose.material3", "NavigationBarItem")) {
            param("selected", from = selected)
            event(onClick, "onClick")
            slot("icon", LambdaTarget.NamedParam("icon"))
            slot("label", LambdaTarget.NamedParam("label"))
        }
    }
}
