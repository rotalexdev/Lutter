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
 * The `m3.Scaffold` spec: the screen's frame, three named slots and no property of its own.
 *
 * Colour and elevation stay with `MaterialTheme`, exactly as [CardSpec]'s no-property rule
 * reads: the document places, the theme dresses, and a spec property that named either would be
 * a second path to one decision. The three slots are declared in argument order with the body
 * last, so the generated call's trailing lambda is the content — the same last-slot rule a
 * document component's own emission follows. None of the three provides a scope: Compose's
 * `Scaffold` lambdas carry no receiver (`topBar` and `fab` are `() -> Unit`, and the content
 * lambda takes `PaddingValues` as a *parameter*, not a receiver), so unlike `Card`'s
 * `ColumnScope` there is no scope handle to open and none to read inside. V1 ignores that
 * `PaddingValues` — the document cannot ask for the insets yet, which is the one thing the slot
 * shape hides and the first thing a v2 would surface.
 */
public object ScaffoldSpec {
    /** The top bar, drawn in the scaffold's top area; no receiver, no scope. */
    public val topBar: SlotSpec = SlotSpec(SlotName("topBar"), Cardinality.Many)

    /** The floating action button, drawn in the corner; no receiver, no scope. */
    public val fab: SlotSpec = SlotSpec(SlotName("fab"), Cardinality.Many)

    /** The body, last so the trailing lambda is it; no receiver, and v1 reads no `PaddingValues`. */
    public val content: SlotSpec = SlotSpec(SlotName("content"), Cardinality.Many)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Scaffold"), version = 1) {
        metadata(displayName = "Scaffold", category = Category.Layout)
        slot(topBar)
        slot(fab)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "Scaffold")) {
            slot("topBar", LambdaTarget.NamedParam("topBar"))
            slot("fab", LambdaTarget.NamedParam("fab"))
            slot("content", LambdaTarget.Trailing)
        }
    }
}
