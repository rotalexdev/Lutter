package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.NavigationRail
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.NavigationRailSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The navigation rail renderer: Material's side rail, a bare header and a column of items.
 *
 * The two slots disagree on scope and the renderer spells each: the items read through
 * `withScope`, because `NavigationRail`'s content lambda is a `ColumnScope` and the align
 * projections inside it answer to the column's axis, both at render time and in generated code
 * — the same shape [CardRenderer] opens for `Card`'s body — while the header lambda is a
 * receiverless `() -> Unit`, so it renders bare, the same shape [ScaffoldRenderer]'s slots
 * carry. Colour and indicator stay Material's defaults through `MaterialTheme`, because the
 * spec declares no property that could override them, and `NavigationRail` is a stable M3 API
 * so there is no opt-in to spell.
 */
internal object NavigationRailRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        NavigationRail(
            modifier = scope.modifierFor(node),
            header = { scope.RenderSlot(node, NavigationRailSpec.header.name) },
        ) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, NavigationRailSpec.content.name) }
        }
    }
}
