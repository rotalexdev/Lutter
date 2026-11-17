package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.NavigationItemSpec
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The navigation item renderer: the document's selection drawn, the press dispatched with no
 * argument, and two bare slots.
 *
 * Material's `NavigationBarItem` is a `RowScope` extension — the one callee in the wave that
 * *is* one — so the renderer reads the row scope the enclosing bar's slot opened through
 * [RenderScope.handle] and calls `with` against it: the implicit receiver does not reach a
 * nested renderer body, the same reason the align appliers read the arm's receiver, and an
 * item outside a bar has no row to answer, so it reports and draws nothing rather than
 * composing against a scope that is not there. Material's nav press hands its handler nothing
 * — which item is selected is the bar's decision — so the dispatch is
 * [RadioButtonRenderer]'s shape exactly: event key, no args. Neither slot opens a handle:
 * `icon` and `label` are `() -> Unit` lambdas, so there is no scope to read and the item fills
 * whatever scope its container provided.
 */
internal object NavigationItemRenderer : ComponentRenderer {
    /** The key [NavigationItemSpec] binds; read from the spec rather than repeated here. */
    private val ON_CLICK: EventKey = NavigationItemSpec.onClick.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        when (val opened = scope.handle) {
            is ScopeHandle.Row -> with(opened.scope) {
                NavigationBarItem(
                    selected = props[NavigationItemSpec.selected] ?: false,
                    // Material's `NavigationBarItem` hands its press no argument, so a handler
                    // that reads one is refused with a diagnostic rather than handed a value
                    // Compose never produced.
                    onClick = { scope.Dispatch(node, ON_CLICK) },
                    modifier = scope.modifierFor(node),
                    icon = { scope.RenderSlot(node, NavigationItemSpec.icon.name) },
                    label = { scope.RenderSlot(node, NavigationItemSpec.label.name) },
                )
            }
            else -> scope.environment.diagnostics(
                RuntimeDiagnostic(
                    "m3.NavigationItem composes inside a m3.NavigationBar's row scope and found none",
                    node.id,
                ),
            )
        }
    }
}
