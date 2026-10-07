package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape
import dev.rotalex.lutter.schema.component.prop

/**
 * §11.4's `host.call`, the escape hatch §11.6 defines and the only MVP action whose callee the
 * document declares.
 *
 * §11.6 declares the callee — `HostFunctionDecl(name, params: List<ParamDecl>, returns, suspend)`
 * — and §11.5 gives the suspend rule that reads it, but neither names the argument keys of the
 * *step* that calls it. That decl is a document field (§6's `UiDocument.hostFunctions`), so its
 * params are the document's `ParamDecl`s: the same split §13.1 makes for route arguments.
 */
public object HostActions {

    /**
     * `host.call`: names one declared host function, and passes it arguments in its order.
     *
     * `name` is a typed parameter rather than a shape because `TypeRef.Str` is all a name is:
     * §11.6's declaration types the *arguments*, not the callee, so nothing there constrains it.
     * `args` is a shape for the opposite reason — the declaration types each position on its own,
     * which no single `TypeRef` can spell.
     *
     * `args` is not required, because a declared function may take no parameters at all and
     * refusing the key would make a zero-argument call spell an empty list.
     */
    public val call: ActionSpec = ActionSpec(
        id = ActionId("host.call"),
        metadata = ActionMetadata("Call host function"),
        params = listOf(prop<Nothing>("name", TypeRef.Str, required = true)),
        argRules = listOf(
            ArgRule(
                PropertyKey("args"),
                ArgShape.Positional(PropertyKey("name")),
                doc = "The arguments, in the order the declaration lists them",
            ),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(call)
}
