package dev.rotalex.lutter.builtins

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
 * The `core.Row` spec: `core.Column` across the other axis, plus the scope `weight` is gated on.
 *
 * The arrangement entries are document vocabulary (`Start`, `Center`, `End`); the renderer
 * maps those three names and falls back to `Start`, the Compose default.
 */
public object RowSpec {
    /** The document enum id the arrangement entries are validated against. First: used below. */
    public val ArrangementId: TypeId = TypeId("HorizontalArrangement")

    /** Gap between children. Shorthand for `Arrangement.spacedBy`. */
    public val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))

    /** Named arrangement. The enum lives in the document, under [ArrangementId]. */
    public val horizontalArrangement: PropertySpec<String?> = prop(
        "horizontalArrangement",
        TypeRef.Nullable(TypeRef.Enum(ArrangementId)),
        doc = "One of Start, Center, End",
    )

    /** The children, providing the row scope `layout.weight` requires. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        provides = setOf(LayoutScopes.Row),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.Row"), version = 1) {
        metadata(displayName = "Row", category = Category.Layout)
        property(spacing)
        property(horizontalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, horizontalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Row")) {
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
