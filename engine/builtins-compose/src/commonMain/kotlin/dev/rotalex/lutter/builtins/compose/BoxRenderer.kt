package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.BoxSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * PLAN §7.3's box renderer: nothing but the modifier chain and the children.
 *
 * The box scope is opened for them because it is what `layout.align` resolves its two-axis
 * form against; a child carrying no alignment reads Compose's own `TopStart` default.
 */
internal object BoxRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Box(modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.Box(this)) { RenderSlot(node, BoxSpec.children.name) }
        }
    }
}
