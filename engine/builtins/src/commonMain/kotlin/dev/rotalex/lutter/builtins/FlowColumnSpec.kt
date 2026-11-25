package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.VerticalArrangementSpec
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
 * The `core.FlowColumn` spec: [ColumnSpec]'s property shape on Compose Foundation's wrapping column.
 *
 * Children wrap onto the next column instead of scrolling or squeezing — no virtualization, the
 * wave's own rule (§29.1's `Lazy*` row names the only virtualizing layout). The children
 * provide [LayoutScopes.Column]: the wrap scope's only gated-modifier overlap is `weight`, whose
 * spelling is the column's, and the wave adds no second scope for it.
 */
public object FlowColumnSpec {
    /** The document enum id; the vertical arrangement is [ColumnSpec]'s own. */
    public val ArrangementId: TypeId = VerticalArrangementSpec.Id

    /** Gap between children. Shorthand for `Arrangement.spacedBy`. */
    public val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))

    /** Named arrangement. The enum lives in the document, under [ArrangementId]. */
    public val verticalArrangement: PropertySpec<String?> = prop(
        "verticalArrangement",
        TypeRef.Nullable(TypeRef.Enum(ArrangementId)),
        doc = "One of Top, Center, Bottom",
    )

    /** The children, providing the column scope to gated modifiers. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Column),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.FlowColumn"), version = 1) {
        metadata(displayName = "FlowColumn", category = Category.Layout)
        property(spacing)
        property(verticalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, verticalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "FlowColumn")) {
            param(
                "verticalArrangement",
                from = listOf(spacing.key, verticalArrangement.key),
                emit = ValueEmit.Cases(
                    listOf(
                        EmitCase(
                            setOf(spacing.key),
                            "Arrangement.spacedBy({spacing})",
                            // Without this the snippet names a symbol nothing imports.
                            listOf(KotlinSymbol("androidx.compose.foundation.layout", "Arrangement")),
                        ),
                        EmitCase(setOf(verticalArrangement.key), "{verticalArrangement}"),
                    ),
                ),
            )
            slot("children", LambdaTarget.Trailing)
        }
    }
}
