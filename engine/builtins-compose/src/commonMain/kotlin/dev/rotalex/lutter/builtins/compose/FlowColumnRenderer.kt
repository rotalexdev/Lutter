package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.FlowColumnSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The flow column renderer: [ColumnRenderer]'s mapping on Compose Foundation's wrapping column.
 *
 * Spacing is a dp, the named arrangement resolves through `ArrangementMaps`'s vertical axis,
 * and spacing wins when both read — [ColumnSpec]'s own rule. The column scope is opened for the
 * children because `weight`'s spelling is the column's.
 */
internal object FlowColumnRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[FlowColumnSpec.spacing]
        FlowColumn(
            modifier = scope.modifierFor(node),
            verticalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else ArrangementMaps.vertical(props[FlowColumnSpec.verticalArrangement]),
        ) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, FlowColumnSpec.children.name) }
        }
    }
}
