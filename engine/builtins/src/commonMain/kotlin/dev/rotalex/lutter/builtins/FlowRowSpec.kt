package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.HorizontalArrangementSpec
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `core.FlowRow` spec: [RowSpec]'s property shape on Compose Foundation's wrapping row.
 *
 * Children wrap onto the next line instead of scrolling or squeezing — no virtualization, the
 * wave's own rule (§29.1's `Lazy*` row names the only virtualizing layout). The children
 * provide [LayoutScopes.Row]: the wrap scope's only gated-modifier overlap is `weight`, whose
 * spelling is the row's, and the wave adds no second scope for it.
 */
public object FlowRowSpec {
    /** The document enum id; the horizontal arrangement is [RowSpec]'s own. */
    public val ArrangementId: TypeId = HorizontalArrangementSpec.Id

    /** Gap between children. Shorthand for `Arrangement.spacedBy`. */
    public val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))

    /** Named arrangement. The enum lives in the document, under [ArrangementId]. */
    public val horizontalArrangement: PropertySpec<String?> = prop(
        "horizontalArrangement",
        TypeRef.Nullable(TypeRef.Enum(ArrangementId)),
        doc = "One of Start, End, Center, SpaceBetween, SpaceAround, SpaceEvenly",
    )

    /** The children, providing the row scope to gated modifiers. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Row),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.FlowRow"), version = 1) {
        metadata(displayName = "FlowRow", category = Category.Layout)
        property(spacing)
        property(horizontalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, horizontalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "FlowRow")) {
            param(
                "horizontalArrangement",
                from = listOf(spacing.key, horizontalArrangement.key),
                emit = ValueEmit.Cases(
                    listOf(
                        EmitCase(
                            setOf(spacing.key),
                            "Arrangement.spacedBy({spacing})",
                            // Without this the snippet names a symbol nothing imports.
                            listOf(KotlinSymbol("androidx.compose.foundation.layout", "Arrangement")),
                        ),
                        EmitCase(setOf(horizontalArrangement.key), "{horizontalArrangement}"),
                    ),
                ),
            )
            slot("children", LambdaTarget.Trailing)
        }
    }
}
