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
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `core.Column` spec: one spacing, one arrangement, never both (D7).
 *
 * The arrangement entries are document vocabulary (`Top`, `Center`, `Bottom`);
 * the renderer maps those three names and falls back to `Top`.
 */
public object ColumnSpec {
    /** The document enum id the arrangement entries are validated against. First: used below. */
    public val ArrangementId: TypeId = TypeId("VerticalArrangement")

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
        provides = setOf(ScopeId("compose.ColumnScope")),
    )

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.Column"), version = 1) {
        metadata(displayName = "Column", category = Category.Layout)
        property(spacing)
        property(verticalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, verticalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column")) {
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
