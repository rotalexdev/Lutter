package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.HorizontalArrangementSpec
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.IterationSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.LambdaTarget
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `core.LazyRow` spec: [LazyColumnSpec] across the horizontal axis, [RowSpec]'s
 * arrangement vocabulary instead of the vertical one.
 *
 * The iteration frame is the same [IterationSpec] both virtualizing layouts declare: one
 * loop variable per element of the property [items] names, in order. No [LayoutScopes.Row]
 * for the same reason [LazyColumnSpec] provides none — a lazy slot opens no receiver for
 * `layout.weight` to apply against.
 */
public object LazyRowSpec {
    /** The document enum id; the horizontal arrangement is [RowSpec]'s own. */
    public val ArrangementId: TypeId = HorizontalArrangementSpec.Id

    /** The list `children` iterates: one item per element, in order, each a string. */
    public val items: PropertySpec<List<String>> = prop(
        "items",
        TypeRef.ListOf(TypeRef.Str),
        required = true,
    )

    /** Gap between children. Shorthand for `Arrangement.spacedBy`. */
    public val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))

    /** Named arrangement. The enum lives in the document, under [ArrangementId]. */
    public val horizontalArrangement: PropertySpec<String?> = prop(
        "horizontalArrangement",
        TypeRef.Nullable(TypeRef.Enum(ArrangementId)),
        doc = "One of Start, Center, End",
    )

    /** The children, one per element of [items], each able to read [iteration]'s variable. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        iteration = iteration,
    )

    /** The whole frame: the loop variable and the element type [items] carries. */
    public val iteration: IterationSpec = IterationSpec("item", TypeRef.Str, items.key)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.LazyRow"), version = 1) {
        metadata(displayName = "LazyRow", category = Category.Layout)
        property(items)
        property(spacing)
        property(horizontalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, horizontalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.lazy", "LazyRow")) {
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
