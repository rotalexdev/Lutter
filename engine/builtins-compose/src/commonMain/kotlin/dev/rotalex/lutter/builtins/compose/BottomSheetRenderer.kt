package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.BottomSheetSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.ScopeHandle

/**
 * The bottom-sheet renderer: Material's modal sheet shown while the document's `visible` holds.
 *
 * The visibility is the renderer's half of the guard the codegen spells: the generated screen
 * wraps a slot-positioned call in `if (visible)`, and this renderer answers the same rule for a
 * runtime draw — a false read renders nothing, so neither backend places the sheet. The
 * dismissal is unconditionally bound: `ModalBottomSheet`'s `onDismissRequest` takes no default,
 * and an unbound event is the running no-op `Dispatch` answers — nothing happens, the sheet
 * stays. No `sheetState`: Compose's default builds a fresh one per composition, which is the
 * unconfigured sheet the document asks for. The content lambda carries a `ColumnScope`, so the
 * slot renders inside the scope opened and the spec provides.
 */
internal object BottomSheetRenderer : ComponentRenderer {
    /** The key [BottomSheetSpec] binds; read from the spec rather than repeated here. */
    private val ON_DISMISS: dev.rotalex.lutter.model.ids.EventKey = BottomSheetSpec.onDismiss.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        if (props[BottomSheetSpec.visible] != true) return
        ModalBottomSheet(onDismissRequest = { scope.Dispatch(node, ON_DISMISS) }) {
            scope.withScope(ScopeHandle.Column(this)) { RenderSlot(node, BottomSheetSpec.content.name) }
        }
    }
}
