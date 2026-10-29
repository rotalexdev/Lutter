package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The progress-indicator renderer: Material 3's indeterminate bar, sized by modifiers.
 *
 * It reads no property because the spec declares none — a determinate value arrives with
 * wave 2's state wiring.
 */
internal object ProgressIndicatorRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        LinearProgressIndicator(modifier = scope.modifierFor(node))
    }
}
