package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.FlowRowSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The flow row renderer: [RowRenderer]'s mapping on Compose Foundation's wrapping row.
 *
 * Spacing is a dp, the named arrangement resolves through `ArrangementMaps`'s horizontal axis,
 * and spacing wins when both read — [RowSpec]'s own rule. The row scope is opened for the
 * children because `weight`'s spelling is the row's.
 */
internal object FlowRowRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[FlowRowSpec.spacing]
        FlowRow(
            modifier = scope.modifierFor(node),
            horizontalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else ArrangementMaps.horizontal(props[FlowRowSpec.horizontalArrangement]),
        ) {
            scope.withScope(ScopeHandle.Row(this)) { RenderSlot(node, FlowRowSpec.children.name) }
        }
    }
}
