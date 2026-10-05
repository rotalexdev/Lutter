package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec

/**
 * §11.4's `host.call`, the escape hatch §11.6 defines and the only MVP action whose callee the
 * document declares.
 *
 * §11.6 specifies the callee — `HostFunctionDecl(name, params: List<ParamDecl>, returns, suspend)`
 * — and §11.5 gives the suspend rule that reads it, but neither names the argument keys of the
 * *step* that calls it. That decl is a document field (§6's `UiDocument.hostFunctions`), so its
 * params are the document's `ParamDecl`s: the same split §13.1 makes for route arguments.
 */
public object HostActions {

    /** `host.call`: names one declared host function; §11.6 leaves the step's argument keys unnamed. */
    public val call: ActionSpec = ActionSpec(
        id = ActionId("host.call"),
        metadata = ActionMetadata("Call host function"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(call)
}
