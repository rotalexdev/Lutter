package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.CheckboxSpec
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The checkbox renderer: the document's state drawn, the change dispatched with its new value.
 *
 * The dispatch carries the argument Material handed over — the new checked state — as the one
 * value the event's spec declares. An unbound handler dispatches into nothing, the same inert
 * move [ButtonRenderer] makes.
 */
internal object CheckboxRenderer : ComponentRenderer {
    /** The key [CheckboxSpec] binds; read from the spec rather than repeated here. */
    private val ON_CHECKED_CHANGE: EventKey = CheckboxSpec.onCheckedChange.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        Checkbox(
            checked = props[CheckboxSpec.checked] ?: false,
            onCheckedChange = { checked ->
                scope.Dispatch(node, ON_CHECKED_CHANGE, mapOf("checked" to Value.Bool(checked)))
            },
            modifier = scope.modifierFor(node),
        )
    }
}
