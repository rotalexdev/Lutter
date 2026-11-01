package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.SliderSpec
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The slider renderer: the document's position drawn, the drag dispatched with its new value.
 *
 * The dispatch carries the argument Material handed over — the new position — as the one value
 * the event's spec declares, the same move [CheckboxRenderer] makes. An unbound handler
 * dispatches into nothing, the same inert move [ButtonRenderer] makes.
 */
internal object SliderRenderer : ComponentRenderer {
    /** The key [SliderSpec] binds; read from the spec rather than repeated here. */
    private val ON_VALUE_CHANGE: EventKey = SliderSpec.onValueChange.key

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        Slider(
            value = props[SliderSpec.value] ?: 0f,
            onValueChange = { value ->
                scope.Dispatch(node, ON_VALUE_CHANGE, mapOf("value" to Value.Float32(value)))
            },
            modifier = scope.modifierFor(node),
        )
    }
}
