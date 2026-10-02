package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope
import dev.rotalex.lutter.runtime.toCompose

/**
 * PLAN §7.4's renderer transcribed: reads, colour fallback, style fallback, modifiers.
 */
internal object TextRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        Text(
            text = props[TextSpec.text] ?: "",
            color = props[TextSpec.color]?.toCompose() ?: Color.Unspecified,
            style = props[TextSpec.style]?.let { scope.theme.textStyle(it) } ?: LocalTextStyle.current,
            modifier = scope.modifierFor(node),
        )
    }
}
