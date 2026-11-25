package dev.rotalex.lutter.builtins.compose

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.ScaffoldSpec
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The scaffold renderer: Material's screen frame around three bare-lambda slots.
 *
 * No slot renders inside a scope: `Scaffold`'s lambdas carry no receiver, and the content
 * lambda's `PaddingValues` is a parameter v1 ignores — the document places children, not
 * insets, which is the same shape the generated call spells with named `topBar` and
 * `floatingActionButton` arguments (Compose's parameter; the document's slot stays `fab`)
 * and a trailing body. Colour and elevation stay Material's defaults through
 * `MaterialTheme`, because the spec declares no property that could override them, and `Scaffold`
 * is a stable M3 API so there is no opt-in to spell.
 */
internal object ScaffoldRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        Scaffold(
            topBar = { scope.RenderSlot(node, ScaffoldSpec.topBar.name) },
            floatingActionButton = { scope.RenderSlot(node, ScaffoldSpec.fab.name) },
            modifier = scope.modifierFor(node),
        ) {
            scope.RenderSlot(node, ScaffoldSpec.content.name)
        }
    }
}
