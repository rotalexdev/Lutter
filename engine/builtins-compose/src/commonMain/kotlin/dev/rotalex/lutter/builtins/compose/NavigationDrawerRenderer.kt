package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.NavigationDrawerSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The navigation drawer renderer: Material's modal drawer, a bare drawer column and a bare body.
 *
 * Neither slot renders inside a scope: the resolved material3's `drawerContent` lambda carries
 * no receiver, so its entries draw bare — the same shape [ScaffoldRenderer]'s slots carry, not
 * the column the platform sources' variant would suggest — and the body lambda is receiverless
 * too. The state stays Material's default
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
                scope.RenderSlot(node, NavigationDrawerSpec.drawerContent.name)
            },
        ) {
            scope.RenderSlot(node, NavigationDrawerSpec.content.name)
        }
    }
}
