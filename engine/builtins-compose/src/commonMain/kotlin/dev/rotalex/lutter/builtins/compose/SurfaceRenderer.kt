package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.SurfaceSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.toCompose

/**
 * The surface renderer: Material's container drawn with the document's color and elevations.
 *
 * Every property is optional, and each absence falls back to the value Compose's own default
 * resolves to — the surface color for `color`, `0.dp` for both elevations — so the rendered
 * surface never distinguishes an absent property from an omitted parameter. The content lambda
 * Surface takes carries no receiver — Compose stacks its children box-style inside — so the
 * slot renders with no scope opened and the spec provides none.
 */
internal object SurfaceRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        Surface(
            color = props[SurfaceSpec.color]?.toCompose() ?: MaterialTheme.colorScheme.surface,
            tonalElevation = props[SurfaceSpec.tonalElevation]?.dp ?: 0.dp,
            shadowElevation = props[SurfaceSpec.shadowElevation]?.dp ?: 0.dp,
            modifier = scope.modifierFor(node),
        ) {
            scope.RenderSlot(node, SurfaceSpec.content.name)
        }
    }
}
