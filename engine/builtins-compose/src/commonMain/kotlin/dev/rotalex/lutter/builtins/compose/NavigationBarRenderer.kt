package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.NavigationBarSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The navigation bar renderer: Material's bottom bar around the row of items.
 *
 * The items read through `withScope`, because `NavigationBar`'s lambda is a `RowScope` and the
 * align projections inside it answer to the row's axis, both at render time and in generated
 * code — the same shape [CardRenderer] opens for `Card`'s column, across the other axis. Colour
 * and indicator stay Material's defaults through `MaterialTheme`, because the spec declares no
 * property that could override them, and `NavigationBar` is a stable M3 API so there is no
 * opt-in to spell.
 */
internal object NavigationBarRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        NavigationBar(modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.Row(this)) { RenderSlot(node, NavigationBarSpec.content.name) }
        }
    }
}
