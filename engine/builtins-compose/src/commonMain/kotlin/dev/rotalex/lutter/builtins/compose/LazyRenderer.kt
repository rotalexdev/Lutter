package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.LazyColumnSpec
import dev.rotalex.lutter.builtins.LazyRowSpec
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * Compose's virtualizing column, with the document's list as the iteration source.
 *
 * The one `Column` answer that needs more than an axis: the slot is not a silent wrapping
 * children list, it is the iteration frame's own children, drawn `items(elements)` and bound
 * one element at a time. Everything else is `ColumnRenderer` on the vertical axis — spacing is
 * a dp, the named arrangement resolves through `ArrangementMaps`, and spacing wins when both
 * read.
 *
 * No [dev.rotalex.lutter.runtime.ScopeHandle]: a lazy slot provides no scope, so the children
 * render exactly as a column's do and `layout.weight` inside one is a missing-scope modifier
 * that the analysis has already refused as non-applicable.
 */
internal object LazyColumnRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[LazyColumnSpec.spacing]
        LazyColumn(
            modifier = scope.modifierFor(node),
            verticalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else ArrangementMaps.vertical(props[LazyColumnSpec.verticalArrangement]),
        ) {
            itemScope(node, scope, LazyColumnSpec.items, LazyColumnSpec.iteration.itemName, ::renderItem)
        }
    }
}

/** [LazyRowSpec]'s row pass: `LazyColumnRenderer` on the other axis, `RowRenderer`'s map. */
internal object LazyRowRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[LazyRowSpec.spacing]
        LazyRow(
            modifier = scope.modifierFor(node),
            horizontalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else ArrangementMaps.horizontal(props[LazyRowSpec.horizontalArrangement]),
        ) {
            itemScope(node, scope, LazyRowSpec.items, LazyRowSpec.iteration.itemName, ::renderItem)
        }
    }
}

/**
 * The list drives the frame loop: one element, one binding, the slot's children.
 *
 * The elements arrive as strings ([LazyColumnSpec.items] carries a `List<String>`), so the
 * bound value re-wraps the text as `Value.Str` — the exact value pass 3 checked the list for,
 * and the read `ExpressionSource.withItem` answers for [itemName] inside the frame.
 */
private fun LazyListScope.itemScope(
    node: ResolvedNode,
    scope: RenderScope,
    items: PropertySpec<List<String>>,
    itemName: String,
    renderItem: (ResolvedNode, RenderScope, String, String) -> Unit,
) {
    val elements: List<String> = scope.props(node)[items] ?: emptyList()
    items(elements) { element -> renderItem(node, scope, itemName, element) }
}

/** One item's frame: bind, then render the slot's children as the renderer registry does. */
private fun renderItem(
    node: ResolvedNode,
    scope: RenderScope,
    itemName: String,
    text: String,
) = scope.withItem(itemName, Value.Str(text)) {
    RenderSlot(node, LazyColumnSpec.children.name)
}
