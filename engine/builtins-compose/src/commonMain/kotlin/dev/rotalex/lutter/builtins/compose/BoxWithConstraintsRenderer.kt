package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.BoxWithConstraintsSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The constrained-box renderer: the box shape with the scope its constraint members read.
 *
 * The same move [BoxRenderer] makes — nothing but the modifier chain and the children — plus
 * the handle swap: the scope opened for them is `BoxWithConstraintsScope`, so a child's
 * adaptive expression resolves against the constraints the container was given.
 */
internal object BoxWithConstraintsRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        BoxWithConstraints(modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.BoxWithConstraints(this)) {
                RenderSlot(node, BoxWithConstraintsSpec.content.name)
            }
        }
    }
}
