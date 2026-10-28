package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle
import dev.rotalex.lutter.analysis.resolved.ResolvedNode

/**
 * PLAN §15.4's renderer: spacing is a dp, the named arrangement resolves through
 * `ArrangementMaps`'s vertical axis, and spacing wins when both read.
 */
internal object ColumnRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[ColumnSpec.spacing]
        Column(
            modifier = scope.modifierFor(node),
            verticalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else ArrangementMaps.vertical(props[ColumnSpec.verticalArrangement]),
        ) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, ColumnSpec.children.name) }
        }
    }
}
