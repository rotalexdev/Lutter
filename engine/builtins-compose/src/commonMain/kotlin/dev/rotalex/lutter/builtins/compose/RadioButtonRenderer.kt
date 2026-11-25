package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.RadioButtonSpec
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The radio renderer: the document's selection drawn, the press dispatched with no argument.
 *
 * Material's radio press hands its handler nothing — which button is selected is the group's
 * decision — so the dispatch is [ButtonRenderer]'s shape exactly: event key, no args.
 */
internal object RadioButtonRenderer : ComponentRenderer {
    /** The key [RadioButtonSpec] binds; read from the spec rather than repeated here. */
    private val ON_CLICK: EventKey = RadioButtonSpec.onClick.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        RadioButton(
            selected = props[RadioButtonSpec.selected] ?: false,
            onClick = { scope.Dispatch(node, ON_CLICK) },
            modifier = scope.modifierFor(node),
        )
    }
}
