package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.SurfaceSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The surface renderer: Material's container drawn with the document's color and elevations.
 *
 * Every property is optional, and each absence falls back to the value Compose's own default
 * resolves to — the surface color for `color`, `0.dp` for both elevations — so the rendered
 * surface never distinguishes an absent property from an omitted parameter. The single content
 * slot renders one child deep inside the surface's own scope.
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
            RenderSlot(node, SurfaceSpec.content.name)
        }
    }
}
