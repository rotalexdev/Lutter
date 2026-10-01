package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.RowSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * PLAN §7.3's row renderer, minus the enum maps: spacing is a dp, the named arrangement is
 * three documented strings, and spacing wins when both read.
 *
 * The row scope is opened for the children because that receiver is what `layout.weight`
 * applies against; the implicit `RowScope` of the lambda does not reach a modifier applier.
 */
internal object RowRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        val spacing = props[RowSpec.spacing]
        Row(
            modifier = scope.modifierFor(node),
            horizontalArrangement =
            if (spacing != null) Arrangement.spacedBy(spacing.dp)
            else horizontalArrangementOf(props[RowSpec.horizontalArrangement]),
        ) {
            scope.withScope(ScopeHandle.Row(this)) { RenderSlot(node, RowSpec.children.name) }
        }
    }
}

/** The documented entries; anything else is `Start`, which is also the default. */
private fun horizontalArrangementOf(entry: String?): Arrangement.Horizontal =
    if (entry == "End") Arrangement.End
    else if (entry == "Center") Arrangement.Center
    else Arrangement.Start
