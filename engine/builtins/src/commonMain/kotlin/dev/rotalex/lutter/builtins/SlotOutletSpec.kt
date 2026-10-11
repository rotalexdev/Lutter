package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * `core.SlotOutlet`: where a declaration's body draws the content an instance filled in (§5.5).
 *
 * The one builtin that only makes sense inside a document component, and the reason §33.2 put
 * `SlotOutlet` in the plan's vocabulary without a spec of its own until now: a body that
 * composes its slot lambdas has to name them somewhere, and a node is the only thing the
 * document already has a way to express.
 *
 * Intrinsic rather than a Compose call because there is no Compose function for it — the runtime
 * draws the open instance's children and the generator writes `content()`, and neither is a call
 * into a library. It declares no slots and no events, which is what keeps [dev.rotalex.lutter.
 * codegen.CodegenCoverage] from refusing an intrinsic spec it has nothing to emit.
 */
public object SlotOutletSpec {

    /** The declared slot's name. The value is read at render time, so it may be computed. */
    public val slot: PropertySpec<String> = prop(
        "slot",
        TypeRef.Str,
        required = true,
        doc = "The name of the slot this outlet draws",
    )

    /** The spec the renderer, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("core.SlotOutlet"), version = 1) {
        metadata(displayName = "Slot outlet", category = Category.Layout)
        property(slot)
        intrinsic()
    }
}
