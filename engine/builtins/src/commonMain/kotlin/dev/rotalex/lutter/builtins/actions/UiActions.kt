package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * `ui.showSnackbar`, the MVP's only presentation action.
 *
 * The plan names the id and says nothing about what it carries, so `message` is this module's
 * decision rather than a reading of a section: a snackbar with no text is not one, and a string
 * is all a type can say about text — the same reason `flow.if`'s condition is a parameter, which
 * is also what gives the analysis pass its typecheck for free.
 */
public object UiActions {

    /**
     * `ui.showSnackbar`: one required message.
     *
     * A parameter rather than a shape because `TypeRef.Str` says what a message is. Nothing else
     * belongs to the document: the length, the action behind the message and its dismissal are
     * not a document's to write, because a snackbar answers nothing and there is no reply a step
     * could read.
     */
    public val showSnackbar: ActionSpec = ActionSpec(
        id = ActionId("ui.showSnackbar"),
        metadata = ActionMetadata("Show snackbar"),
        params = listOf(prop<Nothing>("message", TypeRef.Str, required = true)),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(showSnackbar)
}
