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
 * The `m3.BottomSheet` spec, the wave-3 declarative window over `ModalBottomSheet`: content
 * shows while `visible` holds.
 *
 * The whole call renders behind the guard, exactly as [DialogSpec] does — Material spells the
 * visibility of a sheet as conditional composition, not as a parameter. The body differs from
 * the dialog's: `ModalBottomSheet`'s content lambda carries a `ColumnScope`, so alignments the
 * scope reads apply inside it. No `sheetState` yet: Compose's default builds a fresh one per
 * composition, which is the unconfigured sheet the document asks for.
 */
public object BottomSheetSpec {
    /** Whether the sheet is open. Required: a window without a state is an unconditional window. */
    public val visible: PropertySpec<Boolean> = prop("visible", TypeRef.Bool, required = true)

    /** The body. `ModalBottomSheet` puts a `ColumnScope` in that lambda, which alignments read. */
    public val content: SlotSpec = SlotSpec(
        SlotName("content"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Column),
    )

    /** The dismissal, drag-to-dismiss and scrim taps included; optional, unbound runs nothing. */
    public val onDismiss: EventSpec = EventSpec(EventKey("onDismiss"), emptyList())

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.BottomSheet"), version = 1) {
        metadata(displayName = "BottomSheet", category = Category.Layout)
        property(visible)
        event(onDismiss)
        slot(content)
        composeCall(KotlinSymbol("androidx.compose.material3", "ModalBottomSheet")) {
            event(onDismiss, param = "onDismissRequest")
            slot("content", LambdaTarget.Trailing)
            guard(visible)
        }
    }
}
