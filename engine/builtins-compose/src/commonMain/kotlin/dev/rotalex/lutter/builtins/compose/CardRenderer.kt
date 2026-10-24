package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.CardSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The card renderer: a themed surface around the body.
 *
 * The body reads through `withScope`, because `Card`'s lambda is a `ColumnScope` and the align
 * projections inside it answer to the column's axis, both at render time and in generated code.
 */
internal object CardRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Card(modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, CardSpec.content.name) }
        }
    }
}
