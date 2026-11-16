package dev.rotalex.lutter.builtins.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.DialogSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The dialog renderer: Material's window shown while the document's `visible` holds.
 *
 * The visibility is the renderer's half of the guard the codegen spells: the generated screen
 * wraps a slot-positioned call in `if (visible)`, and this renderer answers the same rule for a
 * runtime draw — a false read renders nothing, so neither backend places the window. The
 * dismissal is unconditionally bound: `Dialog`'s `onDismissRequest` takes no default, and an
 * unbound event is the running no-op `Dispatch` answers — nothing happens, the window stays.
 * The content lambda carries no receiver — Compose stacks its children box-style inside — so
 * the slot renders with no scope opened and the spec provides none.
 */
internal object DialogRenderer : ComponentRenderer {
    /** The key [DialogSpec] binds; read from the spec rather than repeated here. */
    private val ON_DISMISS: dev.rotalex.lutter.model.ids.EventKey = DialogSpec.onDismiss.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        if (props[DialogSpec.visible] != true) return
        Dialog(onDismissRequest = { scope.Dispatch(node, ON_DISMISS) }) {
            scope.RenderSlot(node, DialogSpec.content.name)
        }
    }
}
