package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.NavigationDrawerSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The navigation drawer renderer: Material's modal drawer, a column of entries and a bare body.
 *
 * The two slots disagree on scope and the renderer spells each: the drawer's entries read
 * through `withScope`, because `ModalNavigationDrawer`'s `drawerContent` lambda is a
 * `ColumnScope` and the align projections inside it answer to the column's axis, both at render
 * time and in generated code — the same shape [CardRenderer] opens for `Card`'s body — while
 * the body lambda is a receiverless `() -> Unit`, so it renders bare, the same shape
 * [ScaffoldRenderer]'s content carries. The state stays Material's default
 * `rememberDrawerState(DrawerValue.Closed)`, because the spec declares no property that could
 * drive it: a document renders its drawer closed and its body is what shows. Colour and scrim
 * stay `MaterialTheme`'s for the same no-property rule, and `ModalNavigationDrawer` is a stable
 * M3 API so there is no opt-in to spell.
 */
internal object NavigationDrawerRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        ModalNavigationDrawer(
            modifier = scope.modifierFor(node),
            drawerContent = {
                scope.withScope(ScopeHandle.Column(this)) {
                    RenderSlot(node, NavigationDrawerSpec.drawerContent.name)
                }
            },
        ) {
            scope.RenderSlot(node, NavigationDrawerSpec.content.name)
        }
    }
}
