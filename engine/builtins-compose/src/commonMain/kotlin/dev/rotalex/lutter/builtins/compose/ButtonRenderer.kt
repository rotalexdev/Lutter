package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.ButtonSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The button renderer: an inert press over the label.
 *
 * The label is read under the row scope its slot declares, because that is the receiver
 * Compose's `Button` introduces and the one a gated modifier on the label resolves against.
 */
internal object ButtonRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Button(onClick = { }, modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.Row(this)) { RenderSlot(node, ButtonSpec.content.name) }
        }
    }
}
