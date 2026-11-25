package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The divider renderer: the modifier chain plus Material 3's default styling.
 *
 * It reads no property because the spec declares none — thickness and color arrive later as
 * wave-2 binding properties, and until then the document sizes it through layout modifiers.
 */
internal object DividerRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        HorizontalDivider(modifier = scope.modifierFor(node))
    }
}
