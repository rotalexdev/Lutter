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

    /**
     * `ui.showDialog`: a modal with a title and a message.
     *
     * Two `Str` parameters rather than shapes, because both spell text a type can name — the
     * same reading `showSnackbar` made of a snackbar's message. The content slot, the buttons
     * and the dismissal are wave-3's to add: a v1 dialog a step can address is text shown
     * somewhere modal, and the `DialogHost` contract says exactly that.
     */
    public val showDialog: ActionSpec = ActionSpec(
        id = ActionId("ui.showDialog"),
        metadata = ActionMetadata("Show dialog"),
        params = listOf(
            prop<Nothing>("title", TypeRef.Str, required = true),
            prop<Nothing>("message", TypeRef.Str, required = true),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /**
     * `ui.showBottomSheet`: the sheet form of `ui.showDialog`.
     *
     * The same two parameters, because the presentation differs and the payload does not: a
     * sheet is where the host draws it, which is the host's business and not the document's.
     */
    public val showBottomSheet: ActionSpec = ActionSpec(
        id = ActionId("ui.showBottomSheet"),
        metadata = ActionMetadata("Show bottom sheet"),
        params = listOf(
            prop<Nothing>("title", TypeRef.Str, required = true),
            prop<Nothing>("message", TypeRef.Str, required = true),
        ),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(showSnackbar, showDialog, showBottomSheet)
}
