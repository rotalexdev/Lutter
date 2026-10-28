package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.TextFieldSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The text field renderer: the document's text and nothing else.
 *
 * No `readOnly` flag, because it is a second way to say what the inert handler already says and
 * a codegen binding would have to carry it too.
 */
internal object TextFieldRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val props = scope.props(node)
        TextField(
            value = props[TextFieldSpec.value] ?: "",
            // Inert: the engine holds no state to write to until Phase 6, so an edit goes nowhere.
            onValueChange = { },
            modifier = scope.modifierFor(node),
        )
    }
}
