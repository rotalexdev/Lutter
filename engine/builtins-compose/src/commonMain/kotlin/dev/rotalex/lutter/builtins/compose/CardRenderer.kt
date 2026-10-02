package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.CardSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The card renderer: a themed surface around the body.
 *
 * The body is read through `scope` rather than `withScope`, because `Card`'s lambda is a
 * `ColumnScope` and no `ScopeHandle` arm names one.
 */
internal object CardRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Card(modifier = scope.modifierFor(node)) {
            scope.RenderSlot(node, CardSpec.content.name)
        }
    }
}
