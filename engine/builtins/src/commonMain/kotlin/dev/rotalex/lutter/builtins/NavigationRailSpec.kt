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
 * The `m3.NavigationRail` spec: the screen's side rail, a header and a column of items, and no
 * property of its own.
 *
 * Colour, elevation and the selected item's indicator stay with `MaterialTheme`, exactly as
 * [NavigationBarSpec]'s no-property rule reads: the document places, the theme dresses. The two
 * slots are declared in argument order with the items last, so the generated call's trailing
 * lambda is the content — the same last-slot rule a document component's own emission follows.
 * They disagree on scope, and each spells its own: the items read through a `ColumnScope`, so
 * [content] provides [LayoutScopes.Column] and a gated modifier on an item resolves against the
 * rail's axis, and Compose's `header` lambda is a `ColumnScope` lambda too —
 * `header: @Composable (ColumnScope.() -> Unit)?` — so [header] provides the column as well:
 * its receiver goes unused by a bare lambda, but a brand inside it is still drawn on the
 * rail's axis, which is the truth the slot records. `NavigationRail` is a stable M3 API, so
 * there is no opt-in to spell.
 */
public object NavigationRailSpec {
    /** The block above the items — a brand, a logo; the column scope is what its aligns read through. */
    public val header: SlotSpec = SlotSpec(
        SlotName("header"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Column),
    )

    /** The rail's items, drawn top to bottom; the column scope is what their aligns read through. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Column),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.NavigationRail"), version = 1) {
        metadata(displayName = "NavigationRail", category = Category.Layout)
        slot(header)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "NavigationRail")) {
            slot("header", LambdaTarget.NamedParam("header"))
            slot("content", LambdaTarget.Trailing)
        }
    }
}
