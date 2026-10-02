package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The spacer renderer: the modifier chain is the whole node.
 *
 * It reads no property because the spec declares none — `layout.width` and `layout.height`
 * are what say how much space a document is asking for.
 */
internal object SpacerRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Spacer(modifier = scope.modifierFor(node))
    }
}
