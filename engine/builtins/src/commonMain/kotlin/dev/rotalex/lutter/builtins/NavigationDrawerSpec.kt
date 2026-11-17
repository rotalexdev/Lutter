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
 * The `m3.NavigationDrawer` spec: the modal drawer's frame, a drawer column and a body, and no
 * property of its own.
 *
 * The document places, the theme dresses: the drawer's colour and scrim stay with
 * `MaterialTheme`, as [NavigationBarSpec]'s no-property rule reads. The two slots are declared
 * in argument order with the body last, so the generated call's trailing lambda is the content —
 * the same last-slot rule a document component's own emission follows. Each spells its own
 * scope: Compose's `drawerContent` lambda is a `ColumnScope`, so [drawerContent] provides
 * [LayoutScopes.Column] and a gated modifier on a drawer entry resolves against its axis,
 * while the body lambda is a receiverless `() -> Unit`, so [content] provides nothing — the
 * same split [NavigationRailSpec] draws between its items and its header. V1 does not drive the
 * drawer's state: Material's default `rememberDrawerState(DrawerValue.Closed)` stands, so a
 * document renders its drawer closed and its body is what shows — opening it is a v2 property,
 * the one thing the slot shape hides.
 */
public object NavigationDrawerSpec {
    /** The drawer's entries, drawn top to bottom; the column scope is what their aligns read through. */
    public val drawerContent: SlotSpec = SlotSpec(
        SlotName("drawerContent"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Column),
    )

    /** The body behind the drawer; no receiver, and v1 reads no drawer state. */
    public val content: SlotSpec = SlotSpec(SlotName("content"), Cardinality.Many)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.NavigationDrawer"), version = 1) {
        metadata(displayName = "NavigationDrawer", category = Category.Layout)
        slot(drawerContent)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "ModalNavigationDrawer")) {
            slot("drawerContent", LambdaTarget.NamedParam("drawerContent"))
            slot("content", LambdaTarget.Trailing)
        }
    }
}
