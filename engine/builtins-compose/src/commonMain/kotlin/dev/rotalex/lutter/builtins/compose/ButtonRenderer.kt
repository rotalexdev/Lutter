package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.ButtonSpec
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The button renderer: a press that runs whatever handler the document wrote.
 *
 * The label is read under the row scope its slot declares, because that is the receiver
 * Compose's `Button` introduces and the one a gated modifier on the label resolves against.
 */
internal object ButtonRenderer : ComponentRenderer {

    /** The key [ButtonSpec] binds; read from the spec rather than repeated here. */
    private val ON_CLICK: EventKey = ButtonSpec.onClick.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        // Material's `Button` hands its press no argument, so a handler that reads one is refused
        // with a diagnostic rather than handed a value Compose never produced.
        Button(onClick = { scope.Dispatch(node, ON_CLICK) }, modifier = scope.modifierFor(node)) {
            scope.withScope(ScopeHandle.Row(this)) { RenderSlot(node, ButtonSpec.content.name) }
        }
    }
}
