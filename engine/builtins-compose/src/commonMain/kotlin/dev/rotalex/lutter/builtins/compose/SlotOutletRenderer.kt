package dev.rotalex.lutter.builtins.compose

import androidx.compose.runtime.Composable
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.SlotOutletSpec
import dev.rotalex.lutter.interpreter.RuntimeDiagnostic
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.RenderScope

/**
 * The slot outlet: the open instance's children for the named slot (§5.5).
 *
 * Nothing here draws anything of its own — the content is nodes the instance carries, so the
 * outlet asks the scope for them and the ordinary slot path renders them. The one thing it must
 * not do is guess: an outlet with no instance open, or a name that is not a slot, reports rather
 * than renders an empty column that reads on screen as a component that drew nothing.
 */
internal object SlotOutletRenderer : ComponentRenderer {

    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val name = scope.props(node)[SlotOutletSpec.slot] ?: return
        val instance = scope.instance
        if (instance == null) {
            scope.environment.diagnostics(
                RuntimeDiagnostic("core.SlotOutlet draws slot '$name' outside a component body", node.id),
            )
            return
        }
        val slot = try {
            SlotName(name)
        } catch (_: IllegalArgumentException) {
            scope.environment.diagnostics(
                RuntimeDiagnostic("core.SlotOutlet names '$name', which is not a slot name", node.id),
            )
            return
        }
        scope.RenderSlot(instance, slot)
    }
}
