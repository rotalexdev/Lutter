package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.componentSpec

/**
 * The `m3.NavigationBar` spec: the screen's bottom bar, one slot and no property of its own.
 *
 * Colour, elevation and the selected item's indicator stay with `MaterialTheme`, exactly as
 * [ScaffoldSpec]'s no-property rule reads: the document places, the theme dresses, and a spec
 * property that named either would be a second path to one decision. The bar's items are
 * declared in its one slot, and Compose's lambda is a `RowScope`, so the slot provides
 * [LayoutScopes.Row] — the same rule §7.3's `Row.children` follows, and the reason a gated
 * modifier on an item resolves against the bar's axis rather than being refused: the slot that
 * provides a scope and the modifier that requires one answer to the same value, both at render
 * time and in generated code. The slot is the only one, so the generated call's trailing lambda
 * is it — the same last-slot rule a document component's own emission follows. `NavigationBar`
 * is a stable M3 API, so there is no opt-in to spell.
 */
public object NavigationBarSpec {
    /** The bar's items, drawn left to right; the row scope is what their aligns read through. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Row),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.NavigationBar"), version = 1) {
        metadata(displayName = "NavigationBar", category = Category.Layout)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "NavigationBar")) {
            slot("content", LambdaTarget.Trailing)
        }
    }
}
