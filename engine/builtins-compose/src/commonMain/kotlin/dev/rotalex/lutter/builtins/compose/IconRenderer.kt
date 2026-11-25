package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.IconSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The icon renderer: the (set, name) the document writes resolves to the vector the
 * schema's set declared. Unresolvable names never get here — pass 3 refused them with the
 * same registry — so the map read is total over what validation allowed.
 */
internal object IconRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val reader = scope.props(node)
        val name: String = reader.get(IconSpec.name) ?: return
        val vector: ImageVector = MaterialIconImages.images[name] ?: return
        Icon(imageVector = vector, contentDescription = null, modifier = scope.modifierFor(node))
    }
}
