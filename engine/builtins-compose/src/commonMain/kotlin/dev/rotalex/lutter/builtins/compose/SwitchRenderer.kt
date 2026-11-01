package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.SwitchSpec
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The switch renderer: the document's state drawn, the change dispatched with its new value.
 *
 * The same move [CheckboxRenderer] makes — Material's switch signature is the checkbox's, so the
 * dispatch carries the new checked state as the one value the event's spec declares, and an
 * unbound handler dispatches into nothing.
 */
internal object SwitchRenderer : ComponentRenderer {
    /** The key [SwitchSpec] binds; read from the spec rather than repeated here. */
    private val ON_CHECKED_CHANGE: EventKey = SwitchSpec.onCheckedChange.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        Switch(
            checked = props[SwitchSpec.checked] ?: false,
            onCheckedChange = { checked ->
                scope.Dispatch(node, ON_CHECKED_CHANGE, mapOf("checked" to Value.Bool(checked)))
            },
            modifier = scope.modifierFor(node),
        )
    }
}
