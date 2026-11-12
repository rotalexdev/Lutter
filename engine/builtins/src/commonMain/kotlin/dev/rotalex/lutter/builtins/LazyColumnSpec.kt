package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.VerticalArrangementSpec
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
 * The `core.LazyColumn` spec: [ColumnSpec]'s axis over Foundation's virtualizing list.
 *
 * The one virtue it has over a `Column` is virtualization — the large-column `perf.large_column`
 * warning names this as the fix — and the iteration frame is what makes the children repeated:
 * the slot declares the loop variable [IterationSpec.itemName] and the type its list [items]
 * carries, so a child inside it may read one. The list is a plain string property rather than a
 * slot expansion because a spec is metadata: the "source list is a property" is what makes the
 * row above a [PropertySpec] and the element type exactly [IterationSpec.itemType].
 *
 * No [LayoutScopes.Column]: `weight` has no `LazyListScope` receiver to apply against and a
 * slot that provides none takes no gated modifier, so a `layout.weight` inside one is the
 * `scope_missing` finding a column slot would refuse with.
 */
public object LazyColumnSpec {
    /** The document enum id; the vertical arrangement is [ColumnSpec]'s own. */
    public val ArrangementId: TypeId = VerticalArrangementSpec.Id

    /** The list `children` iterates: one item per element, in order, each a string. */
    public val items: PropertySpec<List<String>> = prop(
        "items",
        TypeRef.ListOf(TypeRef.Str),
        required = true,
    )

    /** Gap between children. Shorthand for `Arrangement.spacedBy`. */
    public val spacing: PropertySpec<Float?> = prop("spacing", TypeRef.Nullable(TypeRef.Dp))

    /** Named arrangement. The enum lives in the document, under [ArrangementId]. */
    public val verticalArrangement: PropertySpec<String?> = prop(
        "verticalArrangement",
        TypeRef.Nullable(TypeRef.Enum(ArrangementId)),
        doc = "One of Top, Center, Bottom",
    )

    /** The children, one per element of [items], each able to read [iteration]'s variable. */
    public val children: SlotSpec = SlotSpec(
        SlotName("children"),
        Cardinality.Many,
        iteration = iteration,
    )

    /** The whole frame: the loop variable, the element type [items] carries, and its source. */
    public val iteration: IterationSpec = IterationSpec("item", TypeRef.Str, items.key)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.LazyColumn"), version = 1) {
        metadata(displayName = "LazyColumn", category = Category.Layout)
        property(items)
        property(spacing)
        property(verticalArrangement)
        rule(PropertyRule.MutuallyExclusive(setOf(spacing.key, verticalArrangement.key)))
        slot(children)
        composeCall(KotlinSymbol("androidx.compose.foundation.lazy", "LazyColumn")) {
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
